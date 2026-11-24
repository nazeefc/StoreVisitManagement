package com.example.storevisitmanagement.data

import com.example.storevisitmanagement.data.remote.model.EmployeeDto

object SessionManager {
    private var authToken: String? = null
    private var currentEmployee: EmployeeDto? = null

    fun setSession(token: String, employee: EmployeeDto) {
        authToken = token
        currentEmployee = employee
    }

    fun clearSession() {
        authToken = null
        currentEmployee = null
    }

    fun getToken(): String? = authToken

    fun getEmployee(): EmployeeDto? = currentEmployee

    fun isLoggedIn(): Boolean = !authToken.isNullOrBlank()
}
