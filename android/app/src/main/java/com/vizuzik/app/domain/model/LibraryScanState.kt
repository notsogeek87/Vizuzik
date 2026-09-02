package com.vizuzik.app.domain.model

sealed interface LibraryScanState {
    data object Idle : LibraryScanState
    data object Scanning : LibraryScanState
    data class Error(val message: String) : LibraryScanState
}
