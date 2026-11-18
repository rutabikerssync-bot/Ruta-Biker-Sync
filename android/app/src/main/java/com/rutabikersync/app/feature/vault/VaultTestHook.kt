package com.rutabikersync.app.feature.vault

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hook exclusivo para pruebas automatizadas e instrumentación en hardware físico.
 * Permite simular eventos de UI cuando el dispositivo restringe eventos de inyección por ADB.
 */
object VaultTestHook {
    val isForceUnlocked = MutableStateFlow(false)
    val showRestoreDialogFlow = MutableStateFlow(false)
    val restoreInputFlow = MutableStateFlow("")
    val restoreTrigger = MutableStateFlow<List<String>?>(null)
    val overrideDocuments = MutableStateFlow<List<BikerDocument>?>(null)
    val showEditMedicalDialogFlow = MutableStateFlow(false)
    val showShareMedicalDialogFlow = MutableStateFlow(false)
    val showViewMemberMedicalDialogFlow = MutableStateFlow(false)
    val testActiveRideId = MutableStateFlow<String?>(null)
    val testActiveGroupId = MutableStateFlow<String?>(null)
    val testTargetUserId = MutableStateFlow<String?>(null)
    val testShareTrigger = MutableStateFlow<Boolean>(false)
    val showSecuritySettingsFlow = MutableStateFlow<Boolean>(false)
    val showRotateDialogFlow = MutableStateFlow<Boolean>(false)
    val triggerRotateFlow = MutableStateFlow<Boolean>(false)
    val triggerRevokeMedicalFlow = MutableStateFlow<String?>(null)
    val triggerReshareMedicalFlow = MutableStateFlow<Boolean>(false)

    fun reset() {
        showRestoreDialogFlow.value = false
        restoreInputFlow.value = ""
        restoreTrigger.value = null
        overrideDocuments.value = null
        showEditMedicalDialogFlow.value = false
        showShareMedicalDialogFlow.value = false
        showViewMemberMedicalDialogFlow.value = false
        testActiveRideId.value = null
        testActiveGroupId.value = null
        testTargetUserId.value = null
        testShareTrigger.value = false
        showSecuritySettingsFlow.value = false
        showRotateDialogFlow.value = false
        triggerRotateFlow.value = false
        triggerRevokeMedicalFlow.value = null
        triggerReshareMedicalFlow.value = false
    }
}

