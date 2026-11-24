package com.example.storevisitmanagement.data.remote.model

data class StoreDto(
    val id: String,
    val name: String,
    val code: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val assigned_employee_id: String
)

data class StoreResponse(
    val status: String,
    val data: List<StoreDto>?,
    val message: String?
)
