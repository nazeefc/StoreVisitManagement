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
        WHERE photos.taskId = :taskId AND tasks.employeeId = :employeeId AND photos.isDeleted = 0
        ORDER BY photos.createdAt DESC
    """)
    fun getPhotosForTask(taskId: String, employeeId: String): Flow<List<PhotoEntity>>

    @Query("""
        SELECT photos.* FROM photos
        INNER JOIN tasks ON photos.taskId = tasks.id
        WHERE photos.taskId = :taskId AND tasks.employeeId = :employeeId AND photos.isDeleted = 0
        ORDER BY photos.createdAt DESC
    """)
    suspend fun getPhotosForTaskSync(taskId: String, employeeId: String): List<PhotoEntity>

    @Query("""
        SELECT photos.* FROM photos
        INNER JOIN tasks ON photos.taskId = tasks.id
        WHERE photos.taskId = :taskId AND tasks.employeeId = :employeeId
    """)
    suspend fun getAllPhotosForTaskIncludingDeleted(taskId: String, employeeId: String): List<PhotoEntity>

    @Query("""
        SELECT photos.* FROM photos
        INNER JOIN tasks ON photos.taskId = tasks.id
        WHERE tasks.employeeId = :employeeId
          AND tasks.syncStatus = 'SYNCED'
          AND tasks.isDeleted = 0
          AND (photos.syncStatus = 'PENDING' OR photos.syncStatus = 'FAILED')
          AND photos.isDeleted = 0
        ORDER BY photos.createdAt ASC
    """)
    suspend fun getPendingPhotosForEmployee(employeeId: String): List<PhotoEntity>

    @Query("""
        SELECT photos.* FROM photos
        INNER JOIN tasks ON photos.taskId = tasks.id
        WHERE tasks.employeeId = :employeeId AND photos.isDeleted = 1
    """)
    suspend fun getPendingDeletedPhotosForEmployee(employeeId: String): List<PhotoEntity>

    @Query("""
        UPDATE photos SET syncStatus = :syncStatus,
            lastSyncedAt = CASE WHEN :syncStatus = 'SYNCED' THEN :syncedAt ELSE lastSyncedAt END
        WHERE id = :photoId AND isDeleted = 0
          AND syncStatus IN ('PENDING', 'FAILED')
          AND taskId IN (SELECT id FROM tasks WHERE employeeId = :employeeId)
    """)
    suspend fun updatePhotoSyncStatus(photoId: String, employeeId: String, syncStatus: String, syncedAt: Long): Int

    @Query("UPDATE photos SET isDeleted = 1, syncStatus = 'PENDING' WHERE id = :photoId AND isDeleted = 0 AND taskId IN (SELECT id FROM tasks WHERE employeeId = :employeeId)")
    suspend fun markPhotoDeleted(photoId: String, employeeId: String): Int

    @Query("UPDATE photos SET syncStatus = :syncStatus WHERE id = :photoId AND isDeleted = 1 AND taskId IN (SELECT id FROM tasks WHERE employeeId = :employeeId)")
    suspend fun updateDeletedPhotoSyncStatus(photoId: String, employeeId: String, syncStatus: String): Int

    @Query("DELETE FROM photos WHERE id = :photoId AND isDeleted = 1 AND taskId IN (SELECT id FROM tasks WHERE employeeId = :employeeId)")
    suspend fun deleteDeletedPhoto(photoId: String, employeeId: String): Int

    @Query("SELECT * FROM photos")
    suspend fun getAllPhotosSync(): List<PhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE id = :photoId LIMIT 1")
    suspend fun getPhotoById(photoId: String): PhotoEntity?

    @Query("DELETE FROM photos WHERE id = :photoId")
    suspend fun deletePhotoById(photoId: String): Int
}
