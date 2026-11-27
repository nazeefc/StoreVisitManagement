package com.example.storevisitmanagement.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.storevisitmanagement.data.remote.model.EmployeeDto
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionSnapshot(
    val employeeId: String,
    val token: String
)

object SessionManager {
    private var authToken: String? = null
    private var currentEmployee: EmployeeDto? = null
    private var prefs: SharedPreferences? = null
    private val gson = Gson()
    private val lock = Any()

    private val _isSessionValid = MutableStateFlow(false)
    val isSessionValid: StateFlow<Boolean> = _isSessionValid.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                val appContext = context.applicationContext
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                prefs = EncryptedSharedPreferences.create(
                    "secure_session_prefs",
                    masterKeyAlias,
                    appContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                restoreSessionLocked()
            }
        }
    }

    private fun restoreSessionLocked() {
        val token = prefs?.getString("auth_token", null)
        val empJson = prefs?.getString("current_employee", null)
        val expiresAt = prefs?.getLong("expires_at", 0L) ?: 0L

        if (!token.isNullOrBlank() && !empJson.isNullOrBlank() && expiresAt > System.currentTimeMillis()) {
            authToken = token
            currentEmployee = try {
                gson.fromJson(empJson, EmployeeDto::class.java)
            } catch (_: Exception) {
                null
            }
            if (currentEmployee == null) {
                clearSessionLocked()
            } else {
                _isSessionValid.value = true
            }
        } else {
            clearSessionLocked()
        }
    }

    fun setSession(token: String, employee: EmployeeDto, expiresAt: Long) {
        synchronized(lock) {
            authToken = token
            currentEmployee = employee
            prefs?.edit()?.apply {
                putString("auth_token", token)
                putString("current_employee", gson.toJson(employee))
                putLong("expires_at", expiresAt)
                apply()
            }
            _isSessionValid.value = true
        }
    }

    fun clearSession() {
        synchronized(lock) {
            clearSessionLocked()
        }
    }

    private fun clearSessionLocked() {
        authToken = null
        currentEmployee = null
        prefs?.edit()?.clear()?.apply()
        _isSessionValid.value = false
    }

    private fun isExpiredLocked(): Boolean {
        val expiresAt = prefs?.getLong("expires_at", 0L) ?: 0L
        return expiresAt <= System.currentTimeMillis()
    }

    /**
     * Atomically reads a consistent employee+token pair from the secure session.
     * Returns null if logged out, expired, or employee does not match [employeeId].
     */
    fun getSessionSnapshotForEmployee(employeeId: String): SessionSnapshot? {
        synchronized(lock) {
            if (employeeId.isBlank() || isExpiredLocked()) {
                if (isExpiredLocked()) clearSessionLocked()
                return null
            }
            val employee = currentEmployee
            val token = authToken
            if (employee == null || token.isNullOrBlank() || employee.id != employeeId) {
                return null
            }
            return SessionSnapshot(employeeId = employee.id, token = token)
        }
    }

    /** Returns true only if the active session still matches the captured employee+token pair. */
    fun matchesSession(employeeId: String, token: String): Boolean {
        synchronized(lock) {
            if (employeeId.isBlank() || token.isBlank() || isExpiredLocked()) {
                if (isExpiredLocked()) clearSessionLocked()
                return false
            }
            return currentEmployee?.id == employeeId && authToken == token
        }
    }

    /**
     * Atomically clears the session only if it still matches [employeeId] + [token].
     * Prevents an old SyncWorker 401 from wiping a newly established login.
     */
    fun clearSessionIfMatches(employeeId: String, token: String): Boolean {
        synchronized(lock) {
            if (employeeId.isBlank() || token.isBlank()) return false
            if (isExpiredLocked()) {
                clearSessionLocked()
                return false
            }
            if (currentEmployee?.id == employeeId && authToken == token) {
                clearSessionLocked()
                return true
            }
            return false
        }
    }

    fun getToken(): String? {
        synchronized(lock) {
            if (isExpiredLocked()) {
                clearSessionLocked()
                return null
            }
            return authToken
        }
    }

    fun getEmployee(): EmployeeDto? {
        synchronized(lock) {
            if (isExpiredLocked()) {
                clearSessionLocked()
                return null
            }
            return currentEmployee
        }
    }

    fun isLoggedIn(): Boolean {
        synchronized(lock) {
            if (isExpiredLocked()) {
                clearSessionLocked()
                return false
            }
            return !authToken.isNullOrBlank()
        }
    }
}
