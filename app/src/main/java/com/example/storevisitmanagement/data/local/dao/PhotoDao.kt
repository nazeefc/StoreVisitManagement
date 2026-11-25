package com.example.storevisitmanagement.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.storevisitmanagement.data.local.entity.PhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("""
        SELECT photos.* FROM photos
        INNER JOIN tasks ON photos.taskId = tasks.id
        WHERE photos.taskId = :taskId AND tasks.employeeId = :employeeId
        ORDER BY photos.createdAt DESC
    """)
    fun getPhotosForTask(taskId: String, employeeId: String): Flow<List<PhotoEntity>>

    @Query("""
        SELECT photos.* FROM photos
        INNER JOIN tasks ON photos.taskId = tasks.id
        WHERE photos.taskId = :taskId AND tasks.employeeId = :employeeId
        ORDER BY photos.createdAt DESC
    """)
    suspend fun getPhotosForTaskSync(taskId: String, employeeId: String): List<PhotoEntity>

    @Query("SELECT * FROM photos")
    suspend fun getAllPhotosSync(): List<PhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE id = :photoId LIMIT 1")
    suspend fun getPhotoById(photoId: String): PhotoEntity?

    @Query("DELETE FROM photos WHERE id = :photoId")
    suspend fun deletePhotoById(photoId: String): Int
}
