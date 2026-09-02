package com.vizuzik.app.ui.deezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.data.remote.deezer.DeezerAuthRepository
import com.vizuzik.app.data.remote.deezer.DeezerAuthResult
import com.vizuzik.app.data.remote.deezer.DeezerOAuthConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeezerAuthUiState(
    val isExchanging: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DeezerAuthViewModel @Inject constructor(
    private val authRepository: DeezerAuthRepository,
) : ViewModel() {

    val isConfigured: Boolean = DeezerOAuthConfig.isConfigured
    val authorizationUrl: String = DeezerOAuthConfig.authorizationUrl()
    val redirectUri: String = DeezerOAuthConfig.redirectUri

    val isAuthenticated: StateFlow<Boolean> = authRepository.isAuthenticated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(DeezerAuthUiState())
    val state: StateFlow<DeezerAuthUiState> = _state.asStateFlow()

    fun onCodeReceived(code: String) {
        if (_state.value.isExchanging) return
        _state.update { it.copy(isExchanging = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.exchangeCode(code)) {
                is DeezerAuthResult.Success -> _state.update { it.copy(isExchanging = false, error = null) }
                is DeezerAuthResult.Error -> _state.update { it.copy(isExchanging = false, error = result.message) }
            }
        }
    }

    fun onWebViewError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
