package com.example.storevisitmanagement.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.storevisitmanagement.data.local.dao.PhotoDao
import com.example.storevisitmanagement.data.local.dao.StoreDao
import com.example.storevisitmanagement.data.local.dao.TaskDao
import com.example.storevisitmanagement.data.local.entity.PhotoEntity
import com.example.storevisitmanagement.data.local.entity.StoreEntity
import com.example.storevisitmanagement.data.local.entity.TaskEntity

@Database(
    entities = [StoreEntity::class, TaskEntity::class, PhotoEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
    abstract fun taskDao(): TaskDao
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "store_visit_database"
                )
                    // NOTE: Removed fallbackToDestructiveMigration() to prevent accidental data loss.
                    // Future schema changes must use explicit Migration scripts (e.g. MIGRATION_1_2).
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
