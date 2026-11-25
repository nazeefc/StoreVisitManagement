package com.example.storevisitmanagement.ui.stores

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.storevisitmanagement.data.SessionManager
import com.example.storevisitmanagement.data.remote.model.StoreDto
import com.example.storevisitmanagement.data.repository.AuthRepository
import com.example.storevisitmanagement.data.repository.StoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class StoreListUiState {
    object Loading : StoreListUiState()
    data class Success(val stores: List<StoreDto>, val totalCount: Int, val employeeName: String) : StoreListUiState()
    object Empty : StoreListUiState()
    data class Error(val message: String) : StoreListUiState()
    object LoggedOut : StoreListUiState()
}

class StoreListViewModel(application: Application) : AndroidViewModel(application) {
    private val storeRepository = StoreRepository(application)
    private val authRepository = AuthRepository()

    private val _uiState = MutableStateFlow<StoreListUiState>(StoreListUiState.Loading)
    val uiState: StateFlow<StoreListUiState> = _uiState.asStateFlow()

    private var allStores: List<StoreDto> = emptyList()

    init {
        fetchStores()
    }

    fun fetchStores(query: String = "") {
        viewModelScope.launch {
            if (allStores.isEmpty()) {
                _uiState.value = StoreListUiState.Loading
                val result = storeRepository.getAssignedStores()
                if (result.isSuccess) {
                    allStores = result.getOrNull()?.sortedBy { it.name } ?: emptyList()
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "Failed to load stores."
                    if (errMsg == "UNAUTHORIZED_401") {
                        _uiState.value = StoreListUiState.LoggedOut
                    } else {
                        _uiState.value = StoreListUiState.Error(errMsg)
                    }
                    return@launch
                }
            }

            val employeeName = SessionManager.getEmployee()?.name ?: "Representative"
            val totalCount = allStores.size
            val filtered = if (query.isBlank()) {
                allStores
            } else {
                allStores.filter {
                    it.name.contains(query, ignoreCase = true) ||
                            it.code.contains(query, ignoreCase = true) ||
                            it.address.contains(query, ignoreCase = true)
                }
            }

            if (filtered.isEmpty()) {
                _uiState.value = StoreListUiState.Empty
            } else {
                _uiState.value = StoreListUiState.Success(filtered, totalCount, employeeName)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.value = StoreListUiState.LoggedOut
        }
    }
}
