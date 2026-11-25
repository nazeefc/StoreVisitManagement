package com.example.storevisitmanagement.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = StoreEntity::class,
            parentColumns = ["id"],
            childColumns = ["storeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("storeId"),
        Index("employeeId")
    ]
)
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val storeId: String,
    val employeeId: String,
    val title: String,
    val description: String,
    val category: String,
    val isCompleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
