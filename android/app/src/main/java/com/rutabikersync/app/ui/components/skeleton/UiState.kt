package com.rutabikersync.app.ui.components.skeleton

/**
 * Contrato único de estado de pantalla para pantallas con datos de backend (SKELETONS.md §5).
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Content<T>(val data: T, val stale: Boolean = false) : UiState<T>
    data object Empty : UiState<Nothing>
    data class Error(val kind: ErrorKind, val detail: String? = null) : UiState<Nothing>
    data object Offline : UiState<Nothing>
}

enum class ErrorKind {
    NETWORK,
    SERVER,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    SUBSCRIPTION_EXPIRED,
    UNKNOWN
}
