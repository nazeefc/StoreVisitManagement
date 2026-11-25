package com.example.storevisitmanagement.data.remote.api

import com.example.storevisitmanagement.data.remote.model.LoginRequest
import com.example.storevisitmanagement.data.remote.model.LoginResponse
import com.example.storevisitmanagement.data.remote.model.LogoutResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApiService {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<LogoutResponse>
}
