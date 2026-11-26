package com.example.storevisitmanagement.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @Test
    fun migrationFromVersionOnePreservesTaskAndPhotoRowsAndAppliesStringDefaults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-v1-v2-" + UUID.randomUUID().toString() + ".db"
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()

        val legacy = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            legacy.execSQL("PRAGMA foreign_keys = ON")
            legacy.execSQL("""
                CREATE TABLE stores (
                    id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    code TEXT NOT NULL,
                    address TEXT NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    assignedEmployeeId TEXT NOT NULL,
                    PRIMARY KEY(id)
                )
            """.trimIndent())
            legacy.execSQL("""
                CREATE TABLE tasks (
                    id TEXT NOT NULL,
                    storeId TEXT NOT NULL,
                    employeeId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    category TEXT NOT NULL,
                    isCompleted INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(storeId) REFERENCES stores(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            legacy.execSQL("CREATE INDEX index_tasks_storeId ON tasks(storeId)")
            legacy.execSQL("CREATE INDEX index_tasks_employeeId ON tasks(employeeId)")
            legacy.execSQL("""
                CREATE TABLE photos (
                    id TEXT NOT NULL,
                    taskId TEXT NOT NULL,
                    localFilePath TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            legacy.execSQL("CREATE INDEX index_photos_taskId ON photos(taskId)")
            legacy.execSQL("INSERT INTO stores VALUES ('store-1', 'Store', 'S1', 'Address', 1.0, 2.0, 'employee-1')")
            legacy.execSQL("INSERT INTO tasks VALUES ('task-1', 'store-1', 'employee-1', 'Existing task', '', 'INVENTORY', 0, 1000, 1000)")
            legacy.execSQL("INSERT INTO photos VALUES ('photo-1', 'task-1', '/data/photos/photo-1.jpg', 1000)")
            legacy.execSQL("PRAGMA user_version = 1")
        } finally {
            legacy.close()
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        try {
            val task = migrated.taskDao().getTaskById("task-1")
            assertEquals("Existing task", task?.title)
            assertEquals("PENDING", task?.syncStatus)
            assertFalse(task?.isDeleted ?: true)

            val photos = migrated.photoDao().getAllPhotosSync()
            assertEquals(1, photos.size)
            assertEquals("photo-1", photos.single().id)
            assertEquals("PENDING", photos.single().syncStatus)
            assertFalse(photos.single().isDeleted)

            assertEquals("'PENDING'", columnDefault(migrated, "tasks", "syncStatus"))
            assertEquals("'PENDING'", columnDefault(migrated, "photos", "syncStatus"))
            assertEquals("0", columnDefault(migrated, "tasks", "isDeleted"))
            assertEquals("0", columnDefault(migrated, "photos", "isDeleted"))
            assertEquals("NULL", columnDefault(migrated, "tasks", "lastSyncedAt"))
            assertEquals("NULL", columnDefault(migrated, "photos", "lastSyncedAt"))
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun columnDefault(database: AppDatabase, table: String, column: String): String? {
        val cursor = database.openHelper.readableDatabase.query("PRAGMA table_info($table)")
        cursor.use {
            val nameIndex = it.getColumnIndexOrThrow("name")
            val defaultIndex = it.getColumnIndexOrThrow("dflt_value")
            while (it.moveToNext()) {
                if (it.getString(nameIndex) == column) {
                    return if (it.isNull(defaultIndex)) null else it.getString(defaultIndex)
                }
            }
        }
        return null
    }
}
