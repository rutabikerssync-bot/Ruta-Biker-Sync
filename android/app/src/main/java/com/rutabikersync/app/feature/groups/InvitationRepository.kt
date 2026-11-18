package com.rutabikersync.app.feature.groups

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repositorio canónico para la emisión, consulta, aceptación y auditoría de invitaciones (RULES R2.2 / R2.3).
 * Soporta inyección de reloj de tiempo (clock) para pruebas deterministas de expiración sin sleeps.
 */
class InvitationRepository(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val _invitations = MutableStateFlow<List<Invitation>>(emptyList())
    val invitations: StateFlow<List<Invitation>> = _invitations.asStateFlow()

    /**
     * Emite una nueva invitación oficial en estado PENDING con expiración configurable (por defecto 24 horas).
     */
    fun createInvitation(
        type: InvitationType,
        clubId: String,
        clubName: String,
        inviterUid: String,
        inviterName: String,
        validDurationMs: Long = 24 * 60 * 60 * 1000L,
        customId: String? = null
    ): Invitation {
        val now = clock()
        val inv = Invitation(
            id = customId ?: UUID.randomUUID().toString(),
            type = type,
            clubId = clubId,
            clubName = clubName,
            inviterUid = inviterUid,
            inviterName = inviterName,
            status = InvitationStatus.PENDING,
            createdAt = now,
            expiresAt = now + validDurationMs
        )
        _invitations.value = _invitations.value.filterNot { it.id == inv.id } + inv
        return inv
    }

    /**
     * Obtiene una invitación por su identificador único. Si no existe, retorna null sin lanzar excepción.
     */
    fun getInvitationById(id: String): Invitation? {
        val now = clock()
        val found = _invitations.value.find { it.id == id } ?: return null
        // Si ha expirado y sigue PENDING, actualizar su estado a EXPIRED reactivamente
        if (found.status == InvitationStatus.PENDING && now > found.expiresAt) {
            val expired = found.copy(status = InvitationStatus.EXPIRED)
            _invitations.value = _invitations.value.map { if (it.id == id) expired else it }
            return expired
        }
        return found
    }

    /**
     * Procesa la aceptación de una invitación evaluando previamente las reglas estatutarias:
     * - Si la invitación no existe -> Result.failure(NoSuchElementException)
     * - Si ha expirado -> Retorna estado EXPIRED y rechaza la unión
     * - Si type == HERMANDAD y el piloto ya tiene club (currentClubId != null) ->
     *     REJECTED conforme a la Regla R2.2 (Estatuto Biker: doble membresía prohibida).
     *     El club del piloto NO se altera.
     * - Si type == HERMANDAD y el piloto no tiene club -> ACCEPTED y se asigna el nuevo clubId.
     * - Si type == RODADA -> ACCEPTED conforme a la Regla R2.3. El clubId del piloto NO se altera.
     */
    fun acceptInvitation(
        invitationId: String,
        inviteeUid: String,
        inviteeName: String,
        currentClubId: String?
    ): Result<InvitationAcceptanceResult> {
        val inv = getInvitationById(invitationId)
            ?: return Result.failure(NoSuchElementException("INVITATION_NOT_FOUND"))

        val now = clock()

        // 1. Verificación de expiración
        if (inv.status == InvitationStatus.EXPIRED || now > inv.expiresAt) {
            val expiredInv = inv.copy(
                inviteeUid = inviteeUid,
                inviteeName = inviteeName,
                status = InvitationStatus.EXPIRED,
                rejectionReason = "EXPIRED"
            )
            updateInvitationInList(expiredInv)
            return Result.success(
                InvitationAcceptanceResult(
                    invitation = expiredInv,
                    appliedClubId = currentClubId,
                    isSuccess = false,
                    message = "La invitación ha expirado."
                )
            )
        }

        // 2. Procesamiento según tipo de invitación
        return when (inv.type) {
            InvitationType.HERMANDAD -> {
                // REGLA R2.2: Prohibición estricta de doble membresía
                if (!currentClubId.isNullOrBlank() && currentClubId != inv.clubId) {
                    val rejectedInv = inv.copy(
                        inviteeUid = inviteeUid,
                        inviteeName = inviteeName,
                        status = InvitationStatus.REJECTED,
                        rejectionReason = "Estatuto Biker R2.2: Ya perteneces a un club. Prohibida la doble membresía."
                    )
                    updateInvitationInList(rejectedInv)
                    Result.success(
                        InvitationAcceptanceResult(
                            invitation = rejectedInv,
                            appliedClubId = currentClubId,
                            isSuccess = false,
                            message = "🛡️ Estatuto Biker: Ya perteneces a otro club. Por estatutos no puedes ser miembro de dos clubes. Solicita una Invitación a Rodar."
                        )
                    )
                } else {
                    // Piloto libre de club: unión formal autorizada
                    val acceptedInv = inv.copy(
                        inviteeUid = inviteeUid,
                        inviteeName = inviteeName,
                        status = InvitationStatus.ACCEPTED,
                        rejectionReason = null
                    )
                    updateInvitationInList(acceptedInv)
                    Result.success(
                        InvitationAcceptanceResult(
                            invitation = acceptedInv,
                            appliedClubId = inv.clubId,
                            isSuccess = true,
                            message = "✓ Bienvenido a '${inv.clubName}' · Te has unido al club"
                        )
                    )
                }
            }

            InvitationType.RODADA -> {
                // REGLA R2.3: Convocatoria abierta a rodar, no altera la membresía del piloto
                val acceptedInv = inv.copy(
                    inviteeUid = inviteeUid,
                    inviteeName = inviteeName,
                    status = InvitationStatus.ACCEPTED,
                    rejectionReason = null
                )
                updateInvitationInList(acceptedInv)
                Result.success(
                    InvitationAcceptanceResult(
                        invitation = acceptedInv,
                        appliedClubId = currentClubId, // Mantiene su club actual o null sin alteración
                        isSuccess = true,
                        message = "🏍️ ¡Invitación a rodar con '${inv.clubName}' confirmada! Libre y gratuita."
                    )
                )
            }
        }
    }

    /**
     * Rechaza voluntariamente una invitación pendiente.
     */
    fun rejectInvitation(
        invitationId: String,
        inviteeUid: String?,
        reason: String? = null
    ): Invitation? {
        val inv = _invitations.value.find { it.id == invitationId } ?: return null
        val rejected = inv.copy(
            inviteeUid = inviteeUid ?: inv.inviteeUid,
            status = InvitationStatus.REJECTED,
            rejectionReason = reason ?: "Rechazada por el piloto"
        )
        updateInvitationInList(rejected)
        return rejected
    }

    fun updateInvitationStatus(invitationId: String, status: InvitationStatus, inviteeName: String? = null) {
        val inv = _invitations.value.find { it.id == invitationId } ?: return
        val updated = inv.copy(
            status = status,
            inviteeName = inviteeName ?: inv.inviteeName
        )
        updateInvitationInList(updated)
    }

    private fun updateInvitationInList(updated: Invitation) {
        _invitations.value = _invitations.value.map { if (it.id == updated.id) updated else it }
    }
}

