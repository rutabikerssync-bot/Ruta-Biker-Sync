package com.rutabikersync.app.feature.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rutabikersync.app.feature.groups.AccountType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel que gobierna la máquina de estados de autenticación y onboarding de Ruta Biker Sync.
 */
class AuthViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: IProfileRepository = ProfileRepository(application.applicationContext)
) : AndroidViewModel(application) {

    companion object {
        fun provideFactory(
            application: Application,
            repository: IProfileRepository = ProfileRepository(application.applicationContext)
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                    return AuthViewModel(application, repository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        checkSession()
    }

    /**
     * Evalúa el estado de autenticación actual de forma determinista al arrancar la app.
     */
    fun checkSession() {
        if (!repository.isAuthenticated()) {
            _authState.value = AuthState.NotAuthenticated
            return
        }

        val uid = repository.getGoogleUid()
        val email = repository.getGoogleEmail()

        if (uid.isNullOrBlank() || email.isNullOrBlank()) {
            _authState.value = AuthState.NotAuthenticated
            return
        }

        val profile = repository.getUserProfile()
        if (profile == null || !repository.isProfileComplete()) {
            _authState.value = AuthState.ProfileIncomplete(
                uid = uid,
                email = email,
                displayName = profile?.aliasBiker?.ifBlank { null } ?: email.substringBefore("@"),
                photoUrl = repository.getGooglePhotoUrl()
            )
            return
        }

        val role = repository.getUserRole()
        if (role == null) {
            _authState.value = AuthState.RoleNotChosen(profile)
            return
        }

        val clubId = repository.getClubId()
        _authState.value = AuthState.Ready(
            profile = profile,
            role = role,
            clubId = clubId
        )
        com.rutabikersync.app.core.network.RutaBikerMessagingService.syncFcmToken(getApplication())
    }

    /**
     * Recibe los datos autenticados de la cuenta de Google del dispositivo.
     */
    fun onGoogleLoginSuccess(
        uid: String,
        email: String,
        displayName: String?,
        photoUrl: String?
    ) {
        viewModelScope.launch {
            repository.setAuthenticated(uid, email, displayName, photoUrl)

            val existingProfile = repository.getUserProfile()
            if (existingProfile == null || !repository.isProfileComplete()) {
                _authState.value = AuthState.ProfileIncomplete(
                    uid = uid,
                    email = email,
                    displayName = displayName ?: email.substringBefore("@"),
                    photoUrl = photoUrl
                )
            } else {
                val role = repository.getUserRole()
                if (role == null) {
                    _authState.value = AuthState.RoleNotChosen(existingProfile)
                } else {
                    _authState.value = AuthState.Ready(
                        profile = existingProfile,
                        role = role,
                        clubId = repository.getClubId()
                    )
                }
            }
        }
    }

    /**
     * Guarda el perfil motero obligatorio.
     */
    fun saveProfile(
        aliasBiker: String,
        motorcycleModel: String,
        pinColorHex: String,
        emergencyContactName: String,
        emergencyContactPhone: String,
        bloodType: BloodType
    ): Boolean {
        val current = _authState.value
        val (uid, email, photoUrl) = when (current) {
            is AuthState.ProfileIncomplete -> Triple(current.uid, current.email, current.photoUrl)
            is AuthState.RoleNotChosen -> Triple(current.profile.uid, current.profile.email, current.profile.photoUrl)
            is AuthState.Ready -> Triple(current.profile.uid, current.profile.email, current.profile.photoUrl)
            is AuthState.NotAuthenticated -> {
                val u = repository.getGoogleUid() ?: return false
                val e = repository.getGoogleEmail() ?: return false
                Triple(u, e, repository.getGooglePhotoUrl())
            }
        }

        val updatedProfile = UserProfile(
            uid = uid,
            email = email,
            photoUrl = photoUrl,
            aliasBiker = aliasBiker.trim(),
            motorcycleModel = motorcycleModel.trim(),
            pinColorHex = pinColorHex,
            emergencyContactName = emergencyContactName.trim(),
            emergencyContactPhone = emergencyContactPhone.trim(),
            bloodType = bloodType
        )

        if (!updatedProfile.isComplete) {
            return false
        }

        repository.saveUserProfile(updatedProfile)

        val role = repository.getUserRole()
        if (role == null) {
            _authState.value = AuthState.RoleNotChosen(updatedProfile)
        } else {
            _authState.value = AuthState.Ready(
                profile = updatedProfile,
                role = role,
                clubId = repository.getClubId()
            )
        }
        return true
    }

    /**
     * Define el propósito en la plataforma (Líder o Miembro).
     */
    fun chooseRole(role: AccountType) {
        val profile = repository.getUserProfile() ?: return
        repository.saveUserRole(role)
        val clubId = repository.getClubId()

        _authState.value = AuthState.Ready(
            profile = profile,
            role = role,
            clubId = clubId
        )
        com.rutabikersync.app.core.network.RutaBikerMessagingService.syncFcmToken(getApplication())
    }

    /**
     * Asocia o actualiza el club al que pertenece el usuario.
     */
    fun updateClubId(clubId: String?) {
        repository.saveClubId(clubId)
        val current = _authState.value
        if (current is AuthState.Ready) {
            _authState.value = current.copy(clubId = clubId)
        }
    }

    /**
     * Cierra la sesión y regresa la máquina de estados al inicio.
     */
    fun logout() {
        repository.clearAll()
        _authState.value = AuthState.NotAuthenticated
    }
}
