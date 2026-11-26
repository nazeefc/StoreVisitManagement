package com.example.storevisitmanagement.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.storevisitmanagement.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE storeId = :storeId AND employeeId = :employeeId AND isDeleted = 0 ORDER BY createdAt DESC")
    fun getTasksForStore(storeId: String, employeeId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE employeeId = :employeeId AND (syncStatus = 'PENDING' OR syncStatus = 'FAILED') AND isDeleted = 0 ORDER BY createdAt ASC")
    suspend fun getPendingTasksForEmployee(employeeId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE employeeId = :employeeId AND isDeleted = 1")
    suspend fun getPendingDeletedTasksForEmployee(employeeId: String): List<TaskEntity>

    @Query("UPDATE tasks SET syncStatus = :syncStatus, lastSyncedAt = CASE WHEN :syncStatus = 'SYNCED' THEN :syncedAt ELSE lastSyncedAt END WHERE id = :taskId AND employeeId = :employeeId AND updatedAt = :updatedAtTimestamp AND isDeleted = 0")
    suspend fun updateTaskSyncStatus(taskId: String, employeeId: String, syncStatus: String, syncedAt: Long, updatedAtTimestamp: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity)

    @Update
    suspend fun updateTask(task: TaskEntity): Int

    @Delete
    suspend fun deleteTask(task: TaskEntity): Int

    @Query("UPDATE tasks SET isDeleted = 1, syncStatus = 'PENDING', updatedAt = :updatedAt WHERE id = :taskId AND employeeId = :employeeId")
    suspend fun markTaskDeleted(taskId: String, employeeId: String, updatedAt: Long): Int

    @Query("DELETE FROM tasks WHERE id = :taskId AND employeeId = :employeeId AND isDeleted = 1 AND updatedAt = :updatedAt")
    suspend fun deleteTaskIfDeletedAtRevision(taskId: String, employeeId: String, updatedAt: Long): Int

    @Query("UPDATE tasks SET syncStatus = 'FAILED' WHERE id = :taskId AND employeeId = :employeeId AND updatedAt = :updatedAt AND isDeleted = :isDeleted")
    suspend fun markTaskSyncFailed(taskId: String, employeeId: String, updatedAt: Long, isDeleted: Boolean): Int

    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): TaskEntity?
}
