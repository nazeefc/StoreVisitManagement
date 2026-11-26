package com.example.storevisitmanagement.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.storevisitmanagement.data.local.dao.PhotoDao
import com.example.storevisitmanagement.data.local.dao.StoreDao
import com.example.storevisitmanagement.data.local.dao.TaskDao
import com.example.storevisitmanagement.data.local.entity.PhotoEntity
import com.example.storevisitmanagement.data.local.entity.StoreEntity
import com.example.storevisitmanagement.data.local.entity.TaskEntity

@Database(
    entities = [StoreEntity::class, TaskEntity::class, PhotoEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
    abstract fun taskDao(): TaskDao
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN lastSyncedAt INTEGER DEFAULT NULL")

                db.execSQL("ALTER TABLE photos ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE photos ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE photos ADD COLUMN lastSyncedAt INTEGER DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "store_visit_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
