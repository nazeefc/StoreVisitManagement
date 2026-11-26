package com.example.storevisitmanagement.data.repository

import android.content.Context
import com.example.storevisitmanagement.data.SessionManager
import com.example.storevisitmanagement.data.local.AppDatabase
import com.example.storevisitmanagement.data.local.entity.PhotoEntity
import com.example.storevisitmanagement.data.local.entity.StoreEntity
import com.example.storevisitmanagement.data.local.entity.TaskEntity
import com.example.storevisitmanagement.data.sync.SyncManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

class TaskRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val taskDao = database.taskDao()
    private val storeDao = database.storeDao()
    private val photoDao = database.photoDao()

    fun getTasksFlow(storeId: String, employeeId: String): Flow<List<TaskEntity>> {
        return taskDao.getTasksForStore(storeId, employeeId)
    }

    suspend fun saveTask(task: TaskEntity) {
        withContext(Dispatchers.IO) {
            val employeeId = SessionManager.getEmployee()?.id ?: ""
            if (task.employeeId.isBlank() || task.employeeId != employeeId) {
                throw SecurityException("Unauthorized task creation attempt.")
            }
            val store = storeDao.getStoreById(task.storeId)
            if (store == null || store.assignedEmployeeId != employeeId) {
                throw SecurityException("Store does not exist or is not assigned to current employee.")
            }
            val pendingTask = task.copy(syncStatus = "PENDING")
            taskDao.insertTask(pendingTask)
            SyncManager.enqueueSyncWork(appContext)
        }
    }

    suspend fun updateTask(task: TaskEntity) {
        withContext(Dispatchers.IO) {
            val employeeId = SessionManager.getEmployee()?.id ?: ""
            val existing = taskDao.getTaskById(task.id)
            if (existing == null || existing.employeeId != employeeId || task.employeeId != employeeId) {
                throw SecurityException("Unauthorized task update attempt.")
            }
            val store = storeDao.getStoreById(existing.storeId)
            if (store == null || store.assignedEmployeeId != employeeId) {
                throw SecurityException("Store assignment invalid for current employee.")
            }

            val updated = existing.copy(
                title = task.title,
                description = task.description,
                category = task.category,
                isCompleted = task.isCompleted,
                syncStatus = "PENDING",
                updatedAt = maxOf(System.currentTimeMillis(), existing.updatedAt + 1L)
            )
            val affected = taskDao.updateTask(updated)
            if (affected <= 0) {
                throw Exception("Failed to update task in database.")
            }
            SyncManager.enqueueSyncWork(appContext)
        }
    }

    suspend fun deleteTask(context: Context, task: TaskEntity) {
        withContext(Dispatchers.IO) {
            val employeeId = SessionManager.getEmployee()?.id ?: ""
            val existing = taskDao.getTaskById(task.id)
            if (existing == null || existing.employeeId != employeeId || task.employeeId != employeeId) {
                throw SecurityException("Unauthorized task deletion attempt.")
            }
            val store = storeDao.getStoreById(existing.storeId)
            if (store == null || store.assignedEmployeeId != employeeId) {
                throw SecurityException("Store assignment invalid for current employee.")
            }

            // Keep a tombstone for every sync state until the server acknowledges it.
            val affected = taskDao.markTaskDeleted(
                task.id,
                employeeId,
                maxOf(System.currentTimeMillis(), existing.updatedAt + 1L)
            )
            if (affected <= 0) {
                throw Exception("Failed to mark task deleted.")
            }
            SyncManager.enqueueSyncWork(appContext)
        }
    }

    suspend fun getTaskById(taskId: String): TaskEntity? {
        return withContext(Dispatchers.IO) {
            val employeeId = SessionManager.getEmployee()?.id ?: ""
            val task = taskDao.getTaskById(taskId)
            if (task != null && task.employeeId == employeeId) {
                task
            } else {
                null
            }
        }
    }

    suspend fun cacheStores(stores: List<StoreEntity>) {
        withContext(Dispatchers.IO) {
            storeDao.upsertStores(stores)
        }
    }

    fun getStoresFlow(employeeId: String): Flow<List<StoreEntity>> {
        return storeDao.getStoresForEmployee(employeeId)
    }

    // Photo Operations
    fun getPhotosFlow(taskId: String): Flow<List<PhotoEntity>> {
        val employeeId = SessionManager.getEmployee()?.id ?: ""
        return photoDao.getPhotosForTask(taskId, employeeId)
    }

    suspend fun savePhoto(photo: PhotoEntity) {
        withContext(Dispatchers.IO) {
            val employeeId = SessionManager.getEmployee()?.id ?: ""
            val task = taskDao.getTaskById(photo.taskId)
            if (task == null || task.employeeId != employeeId) {
                throw SecurityException("Unauthorized photo save attempt.")
            }
            val store = storeDao.getStoreById(task.storeId)
            if (store == null || store.assignedEmployeeId != employeeId) {
                throw SecurityException("Store assignment invalid for current employee.")
            }
            val pendingPhoto = photo.copy(syncStatus = "PENDING")
            photoDao.insertPhoto(pendingPhoto)
            SyncManager.enqueueSyncWork(appContext)
        }
    }

    suspend fun deletePhoto(context: Context, photoId: String) {
        withContext(Dispatchers.IO) {
            val employeeId = SessionManager.getEmployee()?.id ?: ""
            val photo = photoDao.getPhotoById(photoId)
            if (photo == null) {
                throw Exception("Photo record not found.")
            }
            val task = taskDao.getTaskById(photo.taskId)
            if (task == null || task.employeeId != employeeId) {
                throw SecurityException("Unauthorized photo deletion attempt.")
            }
            val store = storeDao.getStoreById(task.storeId)
            if (store == null || store.assignedEmployeeId != employeeId) {
                throw SecurityException("Store assignment invalid for current employee.")
            }

            // Keep the marker until the server confirms deletion, even for never-uploaded photos.
            val affected = photoDao.markPhotoDeleted(photoId, employeeId)
            if (affected <= 0) {
                throw Exception("Failed to mark photo deleted.")
            }
            SyncManager.enqueueSyncWork(appContext)
        }
    }
}
