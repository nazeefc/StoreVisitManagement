package com.example.storevisitmanagement.ui.stores

import androidx.lifecycle.ViewModel
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

class StoreListViewModel(
    private val storeRepository: StoreRepository = StoreRepository(),
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

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
            // Call authRepository.logout() which executes API request with token before clearing session
            authRepository.logout()
            _uiState.value = StoreListUiState.LoggedOut
        }
    }
}
