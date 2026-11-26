package com.example.storevisitmanagement.data.remote.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class TaskSyncItem(
    val id: String,
    val store_id: String,
    val title: String,
    val description: String?,
    val category: String,
    val is_completed: Int,
    val is_deleted: Int,
    val created_at: Long,
    val updated_at: Long
)

data class TaskSyncRequest(
    val tasks: List<TaskSyncItem>
)

data class TaskSyncResponseData(
    val synced_task_ids: List<String>
)

data class TaskSyncResponse(
    val status: String,
    val message: String?,
    val data: TaskSyncResponseData?
)

data class PhotoUploadRequest(
    val photo_id: String,
    val task_id: String,
    val image_base64: String,
    val local_file_path: String,
    val created_at: Long
)

data class PhotoUploadResponseData(
    val photo_id: String,
    val server_filename: String
)

data class PhotoUploadResponse(
    val status: String,
    val message: String?,
    val data: PhotoUploadResponseData?
)

data class TaskDeleteRequest(
    val task_id: String
)

data class PhotoDeleteRequest(
    val photo_id: String
)

data class GenericSyncResponse(
    val status: String,
    val message: String?
)

interface SyncApiService {
    @POST("tasks/sync")
    suspend fun syncTasks(@Body request: TaskSyncRequest): Response<TaskSyncResponse>

    @POST("photos/upload")
    suspend fun uploadPhoto(@Body request: PhotoUploadRequest): Response<PhotoUploadResponse>

    @POST("tasks/delete")
    suspend fun deleteTaskOnServer(@Body request: TaskDeleteRequest): Response<GenericSyncResponse>

    @POST("photos/delete")
    suspend fun deletePhotoOnServer(@Body request: PhotoDeleteRequest): Response<GenericSyncResponse>
}
