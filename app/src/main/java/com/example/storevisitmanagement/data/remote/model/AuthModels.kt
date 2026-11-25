package com.example.storevisitmanagement.data.remote.model

data class EmployeeDto(
    val id: String,
    val name: String,
    val email: String,
    val role: String
)

data class LoginRequest(
    val employee_id: String,
    val password: String
)

data class LoginData(
    val token: String,
    val expires_at: Long,
    val employee: EmployeeDto
)

data class LoginResponse(
    val status: String,
    val data: LoginData?,
    val message: String?
)

data class LogoutResponse(
    val status: String,
    val message: String?
)
