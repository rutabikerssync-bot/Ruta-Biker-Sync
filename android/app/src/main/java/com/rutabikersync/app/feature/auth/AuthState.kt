package com.rutabikersync.app.feature.auth

import com.rutabikersync.app.feature.groups.AccountType

/**
 * Máquina de estados estricta de autenticación y onboarding (Bloque A).
 * El Scaffold principal SOLO se compone cuando el estado es [Ready].
 */
sealed interface AuthState {
    /**
     * Usuario sin sesión de Google en el dispositivo.
     */
    data object NotAuthenticated : AuthState

    /**
     * Sesión iniciada con Google pero perfil motero incompleto.
     * Gate bloqueante: no se puede saltar ni descartar.
     */
    data class ProfileIncomplete(
        val uid: String,
        val email: String,
        val displayName: String? = null,
        val photoUrl: String? = null
    ) : AuthState

    /**
     * Perfil motero completado, pero propósito en plataforma aún no definido (Líder vs Miembro).
     */
    data class RoleNotChosen(
        val profile: UserProfile
    ) : AuthState

    /**
     * Acceso autorizado: perfil verificado, rol definido y estado de club conocido.
     */
    data class Ready(
        val profile: UserProfile,
        val role: AccountType,
        val clubId: String? = null
    ) : AuthState
}

