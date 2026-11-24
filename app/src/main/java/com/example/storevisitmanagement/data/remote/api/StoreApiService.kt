package com.example.storevisitmanagement.data.remote.api

import com.example.storevisitmanagement.data.remote.model.StoreResponse
import retrofit2.Response
import retrofit2.http.GET

interface StoreApiService {
    @GET("stores")
    suspend fun getStores(): Response<StoreResponse>
}
