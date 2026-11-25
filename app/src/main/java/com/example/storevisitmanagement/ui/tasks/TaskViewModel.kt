package com.example.storevisitmanagement.ui.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.storevisitmanagement.data.SessionManager
import com.example.storevisitmanagement.data.local.entity.PhotoEntity
import com.example.storevisitmanagement.data.local.entity.TaskEntity
import com.example.storevisitmanagement.data.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

enum class TaskFilter {
    ALL, PENDING, COMPLETED
}

sealed class TaskListUiState {
    object Loading : TaskListUiState()
    data class Success(val tasks: List<TaskEntity>, val filter: TaskFilter) : TaskListUiState()
    object Empty : TaskListUiState()
    data class Error(val message: String) : TaskListUiState()
}

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val taskRepository = TaskRepository(application)

    private val _uiState = MutableStateFlow<TaskListUiState>(TaskListUiState.Loading)
    val uiState: StateFlow<TaskListUiState> = _uiState.asStateFlow()

    private var currentStoreId: String = ""
    private var currentFilter: TaskFilter = TaskFilter.ALL
    private var allTasks: List<TaskEntity> = emptyList()
    private var tasksJob: kotlinx.coroutines.Job? = null
    private var isSaving: Boolean = false

    fun initStore(storeId: String) {
        if (storeId.isBlank()) {
            _uiState.value = TaskListUiState.Error("Invalid or missing store ID.")
            return
        }
        currentStoreId = storeId
        observeTasks()
    }

    private fun observeTasks() {
        val employeeId = SessionManager.getEmployee()?.id
        if (employeeId.isNullOrBlank()) {
            _uiState.value = TaskListUiState.Error("Authentication session expired. Please sign in again.")
            return
        }

        tasksJob?.cancel()
        tasksJob = viewModelScope.launch {
            taskRepository.getTasksFlow(currentStoreId, employeeId).collectLatest { tasks ->
                allTasks = tasks
                applyFilterAndEmit()
            }
        }
    }

    fun setFilter(filter: TaskFilter) {
        currentFilter = filter
        applyFilterAndEmit()
    }

    private fun applyFilterAndEmit() {
        val filtered = when (currentFilter) {
            TaskFilter.ALL -> allTasks
            TaskFilter.PENDING -> allTasks.filter { !it.isCompleted }
            TaskFilter.COMPLETED -> allTasks.filter { it.isCompleted }
        }

        if (filtered.isEmpty()) {
            _uiState.value = TaskListUiState.Empty
        } else {
            _uiState.value = TaskListUiState.Success(filtered, currentFilter)
        }
    }

    fun saveTask(title: String, description: String, category: String, onResult: (Boolean, String?) -> Unit) {
        if (isSaving) return

        val trimmedTitle = title.trim()
        val trimmedDesc = description.trim()

        if (trimmedTitle.isEmpty()) {
            onResult(false, "Task title is required.")
            return
        }
        if (trimmedTitle.length > 100) {
            onResult(false, "Task title cannot exceed 100 characters.")
            return
        }
        if (trimmedDesc.length > 500) {
            onResult(false, "Task description cannot exceed 500 characters.")
            return
        }

        val employeeId = SessionManager.getEmployee()?.id
        if (employeeId.isNullOrBlank()) {
            onResult(false, "Authentication session expired. Please sign in again.")
            return
        }

        if (currentStoreId.isBlank()) {
            onResult(false, "Invalid store ID.")
            return
        }

        isSaving = true
        val now = System.currentTimeMillis()
        val newTask = TaskEntity(
            id = UUID.randomUUID().toString(),
            storeId = currentStoreId,
            employeeId = employeeId,
            title = trimmedTitle,
            description = trimmedDesc,
            category = category,
            isCompleted = false,
            createdAt = now,
            updatedAt = now
        )

        viewModelScope.launch {
            try {
                taskRepository.saveTask(newTask)
                isSaving = false
                onResult(true, null)
            } catch (e: Exception) {
                isSaving = false
                onResult(false, e.message ?: "Failed to save task.")
            }
        }
    }

    fun updateTask(task: TaskEntity, newTitle: String, newDescription: String, newCategory: String, onResult: (Boolean, String?) -> Unit) {
        if (isSaving) return

        val trimmedTitle = newTitle.trim()
        val trimmedDesc = newDescription.trim()

        if (trimmedTitle.isEmpty()) {
            onResult(false, "Task title is required.")
            return
        }
        if (trimmedTitle.length > 100) {
            onResult(false, "Task title cannot exceed 100 characters.")
            return
        }
        if (trimmedDesc.length > 500) {
            onResult(false, "Task description cannot exceed 500 characters.")
            return
        }

        isSaving = true
        val updated = task.copy(
            title = trimmedTitle,
            description = trimmedDesc,
            category = newCategory,
            updatedAt = System.currentTimeMillis()
        )

        viewModelScope.launch {
            try {
                taskRepository.updateTask(updated)
                isSaving = false
                onResult(true, null)
            } catch (e: Exception) {
                isSaving = false
                onResult(false, e.message ?: "Failed to update task.")
            }
        }
    }

    fun toggleTaskCompletion(task: TaskEntity, isCompleted: Boolean) {
        val updated = task.copy(
            isCompleted = isCompleted,
            updatedAt = System.currentTimeMillis()
        )

        viewModelScope.launch {
            try {
                taskRepository.updateTask(updated)
            } catch (e: Exception) {
                _uiState.value = TaskListUiState.Error(e.message ?: "Failed to update task status.")
            }
        }
    }

    fun deleteTask(task: TaskEntity, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                taskRepository.deleteTask(getApplication(), task)
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Failed to delete task.")
            }
        }
    }

    // Photo Operations
    fun getPhotosFlow(taskId: String): Flow<List<PhotoEntity>> {
        return taskRepository.getPhotosFlow(taskId)
    }

    suspend fun saveCapturedPhoto(taskId: String, localFilePath: String) {
        val photo = PhotoEntity(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            localFilePath = localFilePath,
            createdAt = System.currentTimeMillis()
        )
        taskRepository.savePhoto(photo)
    }

    suspend fun deletePhoto(photoId: String) {
        taskRepository.deletePhoto(getApplication(), photoId)
    }
}
