package com.rutabikersync.app.feature.groups

import androidx.lifecycle.ViewModel
import com.rutabikersync.app.ui.components.skeleton.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GroupsScreenData(
    val groups: List<ClubGroupModel>,
    val pendingInvitations: List<ClubInvitationModel>,
    val currentUser: UserProfileModel
)

class Phase1ViewModel(
    val repository: ClubRepository = ClubRepository()
) : ViewModel() {

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _selectedInvitationId = MutableStateFlow<String?>(null)
    val selectedInvitationId: StateFlow<String?> = _selectedInvitationId.asStateFlow()

    private val _selectedMemberUserId = MutableStateFlow<String?>(null)
    val selectedMemberUserId: StateFlow<String?> = _selectedMemberUserId.asStateFlow()

    private val _forceSkeletonPreview = MutableStateFlow(false)
    val forceSkeletonPreview: StateFlow<Boolean> = _forceSkeletonPreview.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    fun setForceSkeletonPreview(enabled: Boolean) {
        _forceSkeletonPreview.value = enabled
    }

    fun selectGroup(groupId: String?) {
        _selectedGroupId.value = groupId
        _selectedMemberUserId.value = null
    }

    fun selectInvitation(invitationId: String?) {
        _selectedInvitationId.value = invitationId
    }

    fun selectMember(userId: String?) {
        _selectedMemberUserId.value = userId
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun buildGroupsUiState(
        groups: List<ClubGroupModel>,
        invitations: List<ClubInvitationModel>,
        user: UserProfileModel,
        forceLoading: Boolean
    ): UiState<GroupsScreenData> {
        if (forceLoading) return UiState.Loading
        val data = GroupsScreenData(groups, invitations, user)
        return repository.resolveUiState(data) { it.groups.isEmpty() && it.pendingInvitations.isEmpty() }
    }

    fun createNewGroup(countryCode: String, name: String, logoPhashHex: String) {
        val user = repository.currentUser.value
        val res = repository.createGroup(user.id, countryCode, name, logoPhashHex)
        res.fold(
            onSuccess = { grp ->
                _statusBanner.value = "Grupo '${grp.name}' creado en ${grp.countryCode} con marca Fundador 🔥"
                _selectedGroupId.value = grp.id
            },
            onFailure = { err ->
                _statusBanner.value = "Rechazado (${err.message}): verifica unicidad de nombre/leet o logo pHash en $countryCode"
            }
        )
    }

    fun sendInvite(groupId: String, invitedUserId: String, statusKind: MemberStatusKind) {
        val user = repository.currentUser.value
        val res = repository.sendInvitation(groupId, user.id, invitedUserId, statusKind)
        res.fold(
            onSuccess = { inv ->
                _statusBanner.value = "Invitación enviada a ${inv.invitedUserId} (${inv.statusKind.apiValue}, pendiente de aceptación)"
            },
            onFailure = { err ->
                _statusBanner.value = "No se pudo invitar: ${err.message}"
            }
        )
    }

    fun acceptInvite(
        invitationId: String,
        consentMembership: Boolean,
        consentLocation: Boolean,
        consentEmergency: Boolean,
        consentNotifications: Boolean
    ) {
        val res = repository.acceptInvitation(
            invitationId = invitationId,
            consentMembership = consentMembership,
            consentLocation = consentLocation,
            consentEmergencyPackage = consentEmergency,
            consentNotifications = consentNotifications
        )
        res.fold(
            onSuccess = { grp ->
                _statusBanner.value = "Invitación aceptada · Unido a '${grp.name}' ($2.50 USD/mes)"
                _selectedInvitationId.value = null
            },
            onFailure = { err ->
                _statusBanner.value = "Debes otorgar los consentimientos obligatorios (${err.message})"
            }
        )
    }

    fun rejectInvite(invitationId: String) {
        repository.rejectInvitation(invitationId)
        _selectedInvitationId.value = null
        _statusBanner.value = "Invitación rechazada"
    }

    fun updateMemberRoleOrStatus(
        groupId: String,
        memberUserId: String,
        statusKind: MemberStatusKind,
        clubRole: ClubRole?
    ) {
        val actor = repository.currentUser.value
        val res = repository.assignMemberRoleOrStatus(groupId, actor.id, memberUserId, statusKind, clubRole)
        res.fold(
            onSuccess = {
                _statusBanner.value = "Estatus/cargo actualizado para $memberUserId"
            },
            onFailure = { err ->
                _statusBanner.value = "Regla R2.4/R3.3: ${err.message}"
            }
        )
    }

    fun voteOnSensitiveAction(groupId: String, voteId: String, approve: Boolean) {
        val actor = repository.currentUser.value
        val res = repository.castLeaderVote(groupId, voteId, actor.id, approve)
        res.fold(
            onSuccess = { v ->
                _statusBanner.value = "Voto registrado (${v.approvals.size}/${v.requiredVotes}) · Estado: ${v.state}"
            },
            onFailure = { err ->
                _statusBanner.value = "Error en votación: ${err.message}"
            }
        )
    }

    fun startGroupTransfer(groupId: String, toLeaderUserId: String) {
        val actor = repository.currentUser.value
        val res = repository.initiateGroupTransfer(groupId, actor.id, toLeaderUserId)
        res.fold(
            onSuccess = {
                _statusBanner.value = "Transferencia iniciada hacia $toLeaderUserId · Espera de seguridad de 7 días (R5.3)"
            },
            onFailure = { err ->
                _statusBanner.value = "No se pudo iniciar transferencia: ${err.message}"
            }
        )
    }

    fun saveUserProfile(displayName: String, bikeModel: String, mapColorHex: String) {
        repository.updateProfile(displayName, bikeModel, mapColorHex)
        _statusBanner.value = "Perfil actualizado localmente y sincronizado"
    }

    fun loginOrRegisterUser(
        email: String,
        displayName: String,
        countryCode: String,
        acceptedTerms: Boolean,
        activateLeader: Boolean
    ) {
        val res = repository.loginOrRegister(email, displayName, countryCode, acceptedTerms, activateLeader)
        res.fold(
            onSuccess = { u ->
                _statusBanner.value = "Sesión iniciada como ${u.displayName} (${u.accountType.name})"
            },
            onFailure = { err ->
                _statusBanner.value = "Debes aceptar términos y reglas de convivencia (${err.message})"
            }
        )
    }
}
