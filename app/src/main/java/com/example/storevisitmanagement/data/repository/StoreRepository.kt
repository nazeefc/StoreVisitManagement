package com.example.storevisitmanagement.data.repository

import android.content.Context
import com.example.storevisitmanagement.data.SessionManager
import com.example.storevisitmanagement.data.local.AppDatabase
import com.example.storevisitmanagement.data.local.entity.StoreEntity
import com.example.storevisitmanagement.data.remote.ApiClient
import com.example.storevisitmanagement.data.remote.model.StoreDto
import com.example.storevisitmanagement.data.remote.model.StoreResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StoreRepository(context: Context) {
    private val storeService = ApiClient.storeService
    private val storeDao = AppDatabase.getInstance(context).storeDao()

    suspend fun getAssignedStores(): Result<List<StoreDto>> {
        return withContext(Dispatchers.IO) {
            val employeeId = SessionManager.getEmployee()?.id ?: ""
            try {
                val response = storeService.getStores()
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.status == "success" && body.data != null) {
                        val stores = body.data
                        // Cache/upsert stores locally in Room safely without deleting child tasks
                        val entities = stores.map {
                            StoreEntity(
                                id = it.id,
                                name = it.name,
                                code = it.code,
                                address = it.address,
                                latitude = it.latitude,
                                longitude = it.longitude,
                                assignedEmployeeId = it.assigned_employee_id
                            )
                        }
                        storeDao.upsertStores(entities)
                        Result.success(stores)
                    } else {
                        Result.failure(Exception(body?.message ?: "Failed to load assigned stores."))
                    }
                } else {
                    if (response.code() == 401) {
                        SessionManager.clearSession()
                        Result.failure(Exception("UNAUTHORIZED_401"))
                    } else {
                        // Try fallback to local cache
                        val cached = storeDao.getStoresSync(employeeId)
                        if (cached.isNotEmpty()) {
                            Result.success(cached.map {
                                StoreDto(it.id, it.name, it.code, it.address, it.latitude, it.longitude, it.assignedEmployeeId)
                            })
                        } else {
                            Result.failure(Exception("Server error (${response.code()}) and no offline cache available."))
                        }
                    }
                }
            } catch (e: Exception) {
                // Network failure: try fallback to local Room cache
                try {
                    val cached = storeDao.getStoresSync(employeeId)
                    if (cached.isNotEmpty()) {
                        Result.success(cached.map {
                            StoreDto(it.id, it.name, it.code, it.address, it.latitude, it.longitude, it.assignedEmployeeId)
                        })
                    } else {
                        Result.failure(Exception("Network error. Please check your connection."))
                    }
                } catch (dbEx: Exception) {
                    Result.failure(Exception("Network error and local cache failure."))
                }
            }
        }
    }
}
