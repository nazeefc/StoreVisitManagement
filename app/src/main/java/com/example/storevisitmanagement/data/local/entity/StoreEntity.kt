package com.example.storevisitmanagement.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stores")
data class StoreEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val code: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val assignedEmployeeId: String
)
