package com.rutabikersync.app.feature.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VaultViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: VaultRepository = VaultRepository.getInstance(application.applicationContext)
) : AndroidViewModel(application) {

    val documents: StateFlow<List<BikerDocument>> = repository.documentsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val medicalSheet: StateFlow<EmergencyMedicalSheet> = repository.medicalSheetFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EmergencyMedicalSheet()
        )

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _recoveryError = MutableStateFlow<String?>(null)
    val recoveryError: StateFlow<String?> = _recoveryError.asStateFlow()

    private val _recoverySuccessMessage = MutableStateFlow<String?>(null)
    val recoverySuccessMessage: StateFlow<String?> = _recoverySuccessMessage.asStateFlow()

    private val _isRotatingMnemonic = MutableStateFlow(false)
    val isRotatingMnemonic: StateFlow<Boolean> = _isRotatingMnemonic.asStateFlow()

    private val _rotatedMnemonic = MutableStateFlow<List<String>?>(null)
    val rotatedMnemonic: StateFlow<List<String>?> = _rotatedMnemonic.asStateFlow()

    private val _rotateError = MutableStateFlow<String?>(null)
    val rotateError: StateFlow<String?> = _rotateError.asStateFlow()

    fun clearRecoveryStatus() {
        _recoveryError.value = null
        _recoverySuccessMessage.value = null
    }

    fun clearRotateStatus() {
        _rotateError.value = null
        _rotatedMnemonic.value = null
    }

    fun rotateMnemonic(onSuccess: (List<String>) -> Unit = {}) {
        viewModelScope.launch {
            _isRotatingMnemonic.value = true
            _rotateError.value = null
            val res = repository.rotateMnemonic()
            _isRotatingMnemonic.value = false
            if (res.isSuccess) {
                val words = res.getOrThrow()
                _rotatedMnemonic.value = words
                onSuccess(words)
            } else {
                _rotateError.value = res.exceptionOrNull()?.message ?: "Error al rotar la mnemónica"
            }
        }
    }

    fun confirmAndWipeRotatedMnemonic() {
        val current = _rotatedMnemonic.value
        if (current != null) {
            val mutableList = current.toMutableList()
            repository.wipeMnemonicFromMemory(mutableList)
            _rotatedMnemonic.value = null
        }
        _rotateError.value = null
    }

    fun getOrGenerateMnemonic(): List<String> {
        return repository.getOrGenerateMnemonic()
    }

    val hasMnemonicBackedUp: Boolean
        get() = repository.hasMnemonicBackedUp()

    val hasMasterKey: Boolean
        get() = repository.hasMasterKey()

    fun backupMasterKey(words: List<String>, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            _isBackingUp.value = true
            _recoveryError.value = null
            val res = repository.backupMasterKeyWithMnemonic(words)
            _isBackingUp.value = false
            if (res.isSuccess) {
                _recoverySuccessMessage.value = "Respaldo E2EE registrado en la bóveda segura"
            } else {
                _recoveryError.value = res.exceptionOrNull()?.message ?: "Error al registrar respaldo"
            }
            onResult(res)
        }
    }

    fun restoreVault(words: List<String>, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            _isRestoring.value = true
            _recoveryError.value = null
            val res = repository.restoreFromRecoveryMnemonic(words)
            _isRestoring.value = false
            if (res.isSuccess) {
                _recoverySuccessMessage.value = "Bóveda recuperada exitosamente"
            } else {
                _recoveryError.value = res.exceptionOrNull()?.message ?: "Passphrase incorrecta o no coincide con el respaldo registrado"
            }
            onResult(res)
        }
    }

    fun saveDocument(document: BikerDocument) {
        viewModelScope.launch {
            repository.saveDocument(document)
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            repository.deleteDocument(id)
        }
    }

    fun saveMedicalSheet(sheet: EmergencyMedicalSheet) {
        viewModelScope.launch {
            repository.saveMedicalSheet(sheet)
        }
    }

    fun toggleLockScreen(enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleLockScreen(enabled)
        }
    }

    val isMedicalSheetComplete: Boolean
        get() = MedicalSheetValidator.isComplete(medicalSheet.value)

    private val _isSharingMedical = MutableStateFlow(false)
    val isSharingMedical: StateFlow<Boolean> = _isSharingMedical.asStateFlow()

    private val _shareMedicalSuccess = MutableStateFlow<String?>(null)
    val shareMedicalSuccess: StateFlow<String?> = _shareMedicalSuccess.asStateFlow()

    private val _shareMedicalError = MutableStateFlow<String?>(null)
    val shareMedicalError: StateFlow<String?> = _shareMedicalError.asStateFlow()

    val lastSharedAtMillis: Long?
        get() = repository.getLastSharedTimeMillis()

    val lastSharedRideId: String?
        get() = repository.getLastSharedRideId()

    val lastSharedGroupId: String?
        get() = repository.getLastSharedGroupId()

    val isSharedMedicalOutdated: Boolean
        get() {
            val sharedAt = repository.getLastSharedTimeMillis() ?: return false
            val editedAt = repository.getLastEditedTimeMillis() ?: return false
            return editedAt > sharedAt
        }

    private val _isRevokingMedical = MutableStateFlow(false)
    val isRevokingMedical: StateFlow<Boolean> = _isRevokingMedical.asStateFlow()

    private val _revokeMedicalSuccess = MutableStateFlow<String?>(null)
    val revokeMedicalSuccess: StateFlow<String?> = _revokeMedicalSuccess.asStateFlow()

    private val _revokeMedicalError = MutableStateFlow<String?>(null)
    val revokeMedicalError: StateFlow<String?> = _revokeMedicalError.asStateFlow()

    fun clearRevokeMedicalStatus() {
        _revokeMedicalSuccess.value = null
        _revokeMedicalError.value = null
    }

    fun revokeMedicalSheet(rideId: String, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            _isRevokingMedical.value = true
            _revokeMedicalError.value = null
            _revokeMedicalSuccess.value = null

            val res = repository.revokeMedicalSheet(rideId)
            _isRevokingMedical.value = false
            if (res.isSuccess) {
                _revokeMedicalSuccess.value = "Acceso a tu ficha médica revocado exitosamente"
            } else {
                _revokeMedicalError.value = res.exceptionOrNull()?.message ?: "Error al revocar acceso a la ficha"
            }
            onResult(res)
        }
    }

    fun clearShareMedicalStatus() {
        _shareMedicalSuccess.value = null
        _shareMedicalError.value = null
    }

    fun setShareMedicalError(message: String) {
        _shareMedicalError.value = message
    }

    fun shareMedicalSheet(rideId: String, groupId: String, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            _isSharingMedical.value = true
            _shareMedicalError.value = null
            _shareMedicalSuccess.value = null

            val res = repository.shareMedicalSheet(rideId, groupId)
            _isSharingMedical.value = false
            if (res.isSuccess) {
                _shareMedicalSuccess.value = "Ficha compartida con tu club durante 24h"
            } else {
                val msg = res.exceptionOrNull()?.message ?: "Error al compartir ficha"
                if (msg.contains("RIDE_NOT_ACTIVE", ignoreCase = true) || 
                    msg.contains("Solo se puede compartir", ignoreCase = true) || 
                    msg.contains("RULES R6.5", ignoreCase = true) ||
                    msg.contains("No hay una rodada activa", ignoreCase = true)) {
                    _shareMedicalError.value = "No hay rodada activa. La compartición no se realizó (RULES R6.5)"
                } else {
                    _shareMedicalError.value = msg
                }
            }
            onResult(res)
        }
    }

    private val _viewedMemberMedicalSheet = MutableStateFlow<EmergencyMedicalSheet?>(null)
    val viewedMemberMedicalSheet: StateFlow<EmergencyMedicalSheet?> = _viewedMemberMedicalSheet.asStateFlow()

    private val _fetchMedicalError = MutableStateFlow<String?>(null)
    val fetchMedicalError: StateFlow<String?> = _fetchMedicalError.asStateFlow()

    fun fetchMemberMedicalSheet(targetUserId: String, rideId: String) {
        viewModelScope.launch {
            _fetchMedicalError.value = null
            val res = repository.fetchSharedMedicalSheet(targetUserId, rideId)
            if (res.isSuccess) {
                _viewedMemberMedicalSheet.value = res.getOrNull()
            } else {
                _fetchMedicalError.value = res.exceptionOrNull()?.message ?: "Error al consultar ficha médica"
            }
        }
    }

    fun clearViewedMemberMedicalSheet() {
        _viewedMemberMedicalSheet.value = null
        _fetchMedicalError.value = null
    }
}
