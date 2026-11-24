package com.example.storevisitmanagement.data.repository

import com.example.storevisitmanagement.data.SessionManager
import com.example.storevisitmanagement.data.remote.ApiClient
import com.example.storevisitmanagement.data.remote.model.StoreDto
import com.example.storevisitmanagement.data.remote.model.StoreResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StoreRepository {
    private val storeService = ApiClient.storeService

    suspend fun getAssignedStores(): Result<List<StoreDto>> {
        return withContext(Dispatchers.IO) {
            try {
                val response = storeService.getStores()
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.status == "success" && body.data != null) {
                        Result.success(body.data)
                    } else {
                        Result.failure(Exception(body?.message ?: "Failed to load assigned stores."))
                    }
                } else {
                    if (response.code() == 401) {
                        SessionManager.clearSession()
                        Result.failure(Exception("UNAUTHORIZED_401"))
                    } else {
                        val errorBody = response.errorBody()?.string()
                        val errorMsg = if (!errorBody.isNullOrBlank()) {
                            try {
                                com.google.gson.Gson().fromJson(errorBody, StoreResponse::class.java)?.message
                            } catch (e: Exception) {
                                null
                            } ?: "Failed to load stores."
                        } else {
                            "Server error (${response.code()}). Please try again."
                        }
                        Result.failure(Exception(errorMsg))
                    }
                }
            } catch (e: Exception) {
                Result.failure(Exception("Network error. Please check your connection."))
            }
        }
    }
}
