package com.example.storevisitmanagement.data.repository

import com.example.storevisitmanagement.data.SessionManager
import com.example.storevisitmanagement.data.remote.ApiClient
import com.example.storevisitmanagement.data.remote.model.LoginData
import com.example.storevisitmanagement.data.remote.model.LoginRequest
import com.example.storevisitmanagement.data.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository {
    private val authService = ApiClient.authService

    suspend fun login(employeeId: String, password: String): Result<LoginData> {
        return withContext(Dispatchers.IO) {
            try {
                val response = authService.login(LoginRequest(employee_id = employeeId, password = password))
                if (response.isSuccessful) {
                    val body = response.body()
                    val loginData = body?.data
                    if (body?.status == "success" && loginData != null) {
                        // Stop any prior employee's sync before installing the new session
                        SyncManager.cancelAllSyncWork()
                        SessionManager.setSession(loginData.token, loginData.employee, loginData.expires_at)
                        Result.success(loginData)
                    } else {
                        Result.failure(Exception(body?.message ?: "Login failed. Please verify credentials."))
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    val errorMsg = if (!errorBody.isNullOrBlank()) {
                        try {
                            com.google.gson.Gson().fromJson(errorBody, com.example.storevisitmanagement.data.remote.model.LoginResponse::class.java)?.message
                        } catch (e: Exception) {
                            null
                        } ?: "Invalid employee ID or password."
                    } else {
                        when (response.code()) {
                            401 -> "Invalid employee ID or password."
                            else -> "Server error (${response.code()}). Please try again."
                        }
                    }
                    Result.failure(Exception(errorMsg))
                }
            } catch (e: Exception) {
                Result.failure(Exception("Network connection failed. Please check if the mock server is running."))
            }
        }
    }

    suspend fun logout(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            var remoteSuccess = false
            try {
                val response = authService.logout()
                if (response.isSuccessful) {
                    remoteSuccess = true
                }
            } catch (_: Exception) {
                // Ignore network failure during server logout, but ensure local session is cleared
            } finally {
                // Stop queued/running sync before clearing session (logout / account isolation)
                SyncManager.cancelAllSyncWork()
                SessionManager.clearSession()
            }

            if (remoteSuccess) {
                Result.success(Unit)
            } else {
                Result.success(Unit) // Local logout is always successful for user safety
            }
        }
    }
}
