package com.example.storevisitmanagement.data.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.storevisitmanagement.data.SessionManager
import com.example.storevisitmanagement.data.local.AppDatabase
import com.example.storevisitmanagement.data.remote.ApiClient
import com.example.storevisitmanagement.data.remote.api.PhotoDeleteRequest
import com.example.storevisitmanagement.data.remote.api.PhotoUploadRequest
import com.example.storevisitmanagement.data.remote.api.TaskSyncItem
import com.example.storevisitmanagement.data.remote.api.TaskSyncRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val database = AppDatabase.getInstance(appContext)
    private val taskDao = database.taskDao()
    private val photoDao = database.photoDao()

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                SessionManager.init(appContext)
                SyncManager.init(appContext)

                val employeeId = inputData.getString(SyncManager.KEY_EMPLOYEE_ID)
                if (employeeId.isNullOrBlank()) {
                    return@withContext Result.success()
                }

                // Read one consistent employee/token pair from encrypted session storage.
                val session = SessionManager.getSessionSnapshotForEmployee(employeeId)
                    ?: return@withContext Result.success()
                val capturedEmployeeId = session.employeeId
                val capturedToken = session.token

                if (!isCapturedSessionActive(capturedEmployeeId, capturedToken)) {
                    return@withContext Result.success()
                }

                // Use captured token only — never AuthInterceptor / live SessionManager override
                val syncService = ApiClient.createSyncService(capturedToken)
                val photosDir = File(appContext.filesDir, "photos").canonicalFile

                // 1. Process Pending Deleted Photos FIRST (before parent task hard-delete)
                val pendingDeletedPhotos = photoDao.getPendingDeletedPhotosForEmployee(capturedEmployeeId)
                for (photo in pendingDeletedPhotos) {
                    if (!isCapturedSessionActive(capturedEmployeeId, capturedToken)) {
                        return@withContext Result.success()
                    }
                    try {
                        val response = syncService.deletePhotoOnServer(PhotoDeleteRequest(photo.id))
                        if (response.code() == 404 ||
                            (response.isSuccessful && response.body()?.status == "success")) {
                            val removed = photoDao.deleteDeletedPhoto(photo.id, capturedEmployeeId)
                            if (removed > 0) deleteLocalPhotoFile(photosDir, photo.localFilePath)
                        } else if (response.isSuccessful) {
                            return@withContext unexpectedFailureResult(
                                "Server returned an invalid photo deletion acknowledgement for ${photo.id}",
                                IllegalStateException("Invalid photo deletion acknowledgement")
                            )
                        } else if (response.code() == 401) {
                            clearSessionIfMatchesCaptured(capturedEmployeeId, capturedToken)
                            return@withContext Result.success()
                        } else if (SyncRetryPolicy.isRetryableHttpStatus(response.code())) {
                            return@withContext Result.retry()
                        } else {
                            photoDao.updateDeletedPhotoSyncStatus(photo.id, capturedEmployeeId, "FAILED")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        return@withContext Result.retry()
                    } catch (e: Exception) {
                        photoDao.updateDeletedPhotoSyncStatus(photo.id, capturedEmployeeId, "FAILED")
                        return@withContext unexpectedFailureResult("Failed to sync deleted photo ${photo.id}", e)
                    }
                }

                // 2. Send task tombstones through the revision-guarded upsert endpoint.
                val pendingDeletedTasks = taskDao.getPendingDeletedTasksForEmployee(capturedEmployeeId)
                if (pendingDeletedTasks.isNotEmpty()) {
                    val tombstones = pendingDeletedTasks.map { task ->
                        TaskSyncItem(
                            id = task.id,
                            store_id = task.storeId,
                            title = task.title,
                            description = task.description,
                            category = task.category,
                            is_completed = if (task.isCompleted) 1 else 0,
                            is_deleted = 1,
                            created_at = task.createdAt,
                            updated_at = task.updatedAt
                        )
                    }
                    try {
                        if (!isCapturedSessionActive(capturedEmployeeId, capturedToken)) {
                            return@withContext Result.success()
                        }
                        val response = syncService.syncTasks(TaskSyncRequest(tombstones))
                        if (response.isSuccessful && response.body()?.status == "success") {
                            val acknowledgedIds = response.body()?.data?.synced_task_ids.orEmpty().toSet()
                            var missingAcknowledgement = false
                            for (task in pendingDeletedTasks) {
                                if (task.id in acknowledgedIds) {
                                    val attachedPhotos = photoDao.getAllPhotosForTaskIncludingDeleted(task.id, capturedEmployeeId)
                                    val removed = taskDao.deleteTaskIfDeletedAtRevision(task.id, capturedEmployeeId, task.updatedAt)
                                    if (removed > 0) {
                                        attachedPhotos.forEach { deleteLocalPhotoFile(photosDir, it.localFilePath) }
                                    }
                                } else {
                                    taskDao.markTaskSyncFailed(task.id, capturedEmployeeId, task.updatedAt, true)
                                    missingAcknowledgement = true
                                }
                            }
                            if (missingAcknowledgement) {
                                return@withContext unexpectedFailureResult(
                                    "Server omitted one or more task deletion acknowledgements",
                                    IllegalStateException("Incomplete task deletion acknowledgement")
                                )
                            }
                        } else if (response.code() == 401) {
                            clearSessionIfMatchesCaptured(capturedEmployeeId, capturedToken)
                            return@withContext Result.success()
                        } else if (SyncRetryPolicy.isRetryableHttpStatus(response.code())) {
                            return@withContext Result.retry()
                        } else if (response.isSuccessful) {
                            return@withContext unexpectedFailureResult(
                                "Server returned an invalid task deletion acknowledgement",
                                IllegalStateException("Invalid task deletion acknowledgement")
                            )
                        } else {
                            pendingDeletedTasks.forEach {
                                taskDao.markTaskSyncFailed(it.id, capturedEmployeeId, it.updatedAt, true)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        return@withContext Result.retry()
                    } catch (e: Exception) {
                        pendingDeletedTasks.forEach {
                            taskDao.markTaskSyncFailed(it.id, capturedEmployeeId, it.updatedAt, true)
                        }
                        return@withContext unexpectedFailureResult("Failed to sync deleted tasks", e)
                    }
                }

                // 3. Process Pending Tasks (Upload Tasks FIRST)
                if (!isCapturedSessionActive(capturedEmployeeId, capturedToken)) {
                    return@withContext Result.success()
                }
                val pendingTasks = taskDao.getPendingTasksForEmployee(capturedEmployeeId)
                if (pendingTasks.isNotEmpty()) {
                    val taskItems = pendingTasks.map {
                        TaskSyncItem(
                            id = it.id,
                            store_id = it.storeId,
                            title = it.title,
                            description = it.description,
                            category = it.category,
                            is_completed = if (it.isCompleted) 1 else 0,
                            is_deleted = if (it.isDeleted) 1 else 0,
                            created_at = it.createdAt,
                            updated_at = it.updatedAt
                        )
                    }

                    try {
                        if (!isCapturedSessionActive(capturedEmployeeId, capturedToken)) {
                            return@withContext Result.success()
                        }
                        val taskResponse = syncService.syncTasks(TaskSyncRequest(taskItems))
                        if (taskResponse.isSuccessful) {
                            val body = taskResponse.body()
                            if (body?.status != "success") {
                                pendingTasks.forEach {
                                    taskDao.markTaskSyncFailed(it.id, capturedEmployeeId, it.updatedAt, false)
                                }
                                return@withContext unexpectedFailureResult(
                                    "Server returned an invalid task acknowledgement",
                                    IllegalStateException("Invalid task acknowledgement")
                                )
                            } else {
                                val syncedIds = body.data?.synced_task_ids.orEmpty().toSet()
                                val now = System.currentTimeMillis()
                                var missingAcknowledgement = false
                                for (task in pendingTasks) {
                                    if (task.id in syncedIds) {
                                        taskDao.updateTaskSyncStatus(
                                            taskId = task.id,
                                            employeeId = capturedEmployeeId,
                                            syncStatus = "SYNCED",
                                            syncedAt = now,
                                            updatedAtTimestamp = task.updatedAt
                                        )
                                    } else {
                                        taskDao.markTaskSyncFailed(task.id, capturedEmployeeId, task.updatedAt, false)
                                        missingAcknowledgement = true
                                    }
                                }
                                if (missingAcknowledgement) {
                                    return@withContext unexpectedFailureResult(
                                        "Server omitted one or more task acknowledgements",
                                        IllegalStateException("Incomplete task acknowledgement")
                                    )
                                }
                            }
                        } else if (taskResponse.code() == 401) {
                            clearSessionIfMatchesCaptured(capturedEmployeeId, capturedToken)
                            return@withContext Result.success()
                        } else if (SyncRetryPolicy.isRetryableHttpStatus(taskResponse.code())) {
                            return@withContext Result.retry()
                        } else {
                            pendingTasks.forEach {
                                taskDao.markTaskSyncFailed(it.id, capturedEmployeeId, it.updatedAt, false)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        return@withContext Result.retry()
                    } catch (e: Exception) {
                        pendingTasks.forEach {
                            taskDao.markTaskSyncFailed(it.id, capturedEmployeeId, it.updatedAt, false)
                        }
                        return@withContext unexpectedFailureResult("Failed to sync tasks", e)
                    }
                }

                // 4. Process Pending Photos (Upload Photos SECOND - only for tasks whose syncStatus == 'SYNCED')
                val pendingPhotos = photoDao.getPendingPhotosForEmployee(capturedEmployeeId)

                for (photo in pendingPhotos) {
                    if (!isCapturedSessionActive(capturedEmployeeId, capturedToken)) {
                        return@withContext Result.success()
                    }
                    try {
                        val targetFile = File(photo.localFilePath).canonicalFile
                        val relative = photosDir.toPath().relativize(targetFile.toPath())
                        val isInside = !relative.startsWith("..") && !relative.isAbsolute
                        if (!isInside || !targetFile.exists()) {
                            photoDao.updatePhotoSyncStatus(photo.id, capturedEmployeeId, "FAILED", 0L)
                            continue
                        }

                        val base64Data = encodeFileToBase64(targetFile) ?: run {
                            photoDao.updatePhotoSyncStatus(photo.id, capturedEmployeeId, "FAILED", 0L)
                            continue
                        }
                        if (!isCapturedSessionActive(capturedEmployeeId, capturedToken)) {
                            return@withContext Result.success()
                        }
                        // Do not transmit unnecessary local filesystem paths
                        val photoRequest = PhotoUploadRequest(
                            photo_id = photo.id,
                            task_id = photo.taskId,
                            image_base64 = base64Data,
                            local_file_path = "",
                            created_at = photo.createdAt
                        )

                        val photoResponse = syncService.uploadPhoto(photoRequest)
                        if (photoResponse.isSuccessful) {
                            val body = photoResponse.body()
                            if (body?.status == "success" && body.data?.photo_id == photo.id) {
                                photoDao.updatePhotoSyncStatus(
                                    photo.id,
                                    capturedEmployeeId,
                                    "SYNCED",
                                    System.currentTimeMillis()
                                )
                            } else {
                                photoDao.updatePhotoSyncStatus(photo.id, capturedEmployeeId, "FAILED", 0L)
                                return@withContext unexpectedFailureResult(
                                    "Server returned an invalid photo acknowledgement for ${photo.id}",
                                    IllegalStateException("Invalid photo acknowledgement")
                                )
                            }
                        } else if (photoResponse.code() == 401) {
                            clearSessionIfMatchesCaptured(capturedEmployeeId, capturedToken)
                            return@withContext Result.success()
                        } else if (SyncRetryPolicy.isRetryableHttpStatus(photoResponse.code())) {
                            return@withContext Result.retry()
                        } else {
                            photoDao.updatePhotoSyncStatus(photo.id, capturedEmployeeId, "FAILED", 0L)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        return@withContext Result.retry()
                    } catch (e: Exception) {
                        photoDao.updatePhotoSyncStatus(photo.id, capturedEmployeeId, "FAILED", 0L)
                        return@withContext unexpectedFailureResult("Failed to sync photo ${photo.id}", e)
                    }
                }

                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unexpectedFailureResult("Unexpected sync worker failure", e)
            }
        }
    }

    /**
     * Active session must still belong to this worker's captured employee + token.
     * Returns false after logout or account switch so the worker stops safely.
     */
    private fun isCapturedSessionActive(capturedEmployeeId: String, capturedToken: String): Boolean {
        return SessionManager.matchesSession(capturedEmployeeId, capturedToken)
    }

    /** Clear session on 401 only when the active session is still this worker's captured session. */
    private fun clearSessionIfMatchesCaptured(capturedEmployeeId: String, capturedToken: String) {
        SessionManager.clearSessionIfMatches(capturedEmployeeId, capturedToken)
    }

    private fun unexpectedFailureResult(message: String, error: Exception): Result {
        return if (SyncRetryPolicy.shouldRetryUnexpectedly(runAttemptCount)) {
            Log.w("SyncWorker", "$message; scheduling a bounded retry.", error)
            Result.retry()
        } else {
            Log.e("SyncWorker", "$message; retry limit reached.", error)
            Result.failure()
        }
    }

    private fun encodeFileToBase64(file: File): String? {
        return try {
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun deleteLocalPhotoFile(photosDir: File, localFilePath: String) {
        try {
            val targetFile = File(localFilePath).canonicalFile
            val relative = photosDir.toPath().relativize(targetFile.toPath())
            val isInside = !relative.startsWith("..") && !relative.isAbsolute
            if (isInside && targetFile.exists()) {
                targetFile.delete()
            }
        } catch (_: Exception) {
            // Best-effort cleanup; Room record removal remains authoritative
        }
    }
}
