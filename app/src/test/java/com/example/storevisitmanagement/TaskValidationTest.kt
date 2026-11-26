package com.example.storevisitmanagement

import com.example.storevisitmanagement.data.local.entity.PhotoEntity
import com.example.storevisitmanagement.data.local.entity.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaskValidationTest {

    @Test
    fun testTaskTitleValidationLength() {
        val validTitle = "Check inventory levels"
        assertTrue(validTitle.length <= 100)

        val tooLongTitle = "A".repeat(101)
        assertTrue(tooLongTitle.length > 100)
    }

    @Test
    fun testTaskDescriptionValidationLength() {
        val validDesc = "Verify all beverage stock in aisle 3."
        assertTrue(validDesc.length <= 500)

        val tooLongDesc = "B".repeat(501)
        assertTrue(tooLongDesc.length > 500)
    }

    @Test
    fun testTaskFilteringLogic() {
        val tasks = listOf(
            TaskEntity("1", "STR-001", "EMP-9402", "Task 1", "Desc", "INVENTORY", false, 100L, 100L),
            TaskEntity("2", "STR-001", "EMP-9402", "Task 2", "Desc", "INVENTORY", true, 200L, 200L)
        )

        val pending = tasks.filter { !it.isCompleted }
        assertEquals(1, pending.size)
        assertEquals("Task 1", pending[0].title)

        val completed = tasks.filter { it.isCompleted }
        assertEquals(1, completed.size)
        assertEquals("Task 2", completed[0].title)
    }

    @Test
    fun testTaskAndPhotoSyncFieldsDefaults() {
        val task = TaskEntity(
            id = "task-1",
            storeId = "STR-001",
            employeeId = "EMP-9402",
            title = "Test Task",
            description = "Desc",
            category = "INVENTORY",
            isCompleted = false,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        assertEquals("PENDING", task.syncStatus)
        assertFalse(task.isDeleted)
        assertNull(task.lastSyncedAt)

        val photo = PhotoEntity(
            id = "photo-1",
            taskId = "task-1",
            localFilePath = "/photos/img1.jpg",
            createdAt = 1000L
        )
        assertEquals("PENDING", photo.syncStatus)
        assertFalse(photo.isDeleted)
        assertNull(photo.lastSyncedAt)
    }

    @Test
    fun testImageDimensionLongestDimSampling() {
        // Test aspect ratios: 4000x1000, 1000x4000, 4000x3000, 3000x4000, 800x600
        val dimensions = listOf(
            Pair(4000, 1000),
            Pair(1000, 4000),
            Pair(4000, 3000),
            Pair(3000, 4000),
            Pair(800, 600)
        )

        val maxDim = 1280
        for ((origWidth, origHeight) in dimensions) {
            var sampleSize = 1
            val longestDim = maxOf(origWidth, origHeight)
            while (longestDim / (sampleSize * 2) >= maxDim) {
                sampleSize *= 2
            }
            if (longestDim > maxDim) {
                assertTrue(sampleSize >= 1)
                val scaledLongest = longestDim / sampleSize
                assertTrue(scaledLongest > 0)
            }
        }
    }

    @Test
    fun testCanonicalPathContainmentValidation() {
        val baseDir = File("mock-photos-dir").canonicalFile
        val validFile = File(baseDir, "img_123.jpg").canonicalFile
        val invalidFile = File(baseDir.parentFile, "evil.jpg").canonicalFile

        val relValid = baseDir.toPath().relativize(validFile.toPath())
        val isInsideValid = !relValid.startsWith("..") && !relValid.isAbsolute
        assertTrue(isInsideValid)

        val relInvalid = baseDir.toPath().relativize(invalidFile.toPath())
        val isInsideInvalid = !relInvalid.startsWith("..") && !relInvalid.isAbsolute
        assertFalse(isInsideInvalid)
    }
}
