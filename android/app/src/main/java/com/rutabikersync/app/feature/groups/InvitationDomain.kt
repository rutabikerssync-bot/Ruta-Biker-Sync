package com.rutabikersync.app.feature.groups

/**
 * Tipo de invitación oficial según las reglas de Ruta Biker Sync (RULES R2.2 / R2.3).
 */
enum class InvitationType(val code: String, val title: String) {
    /**
     * Membresía formal en el club. Sujeta estrictamente a la regla R2.2:
     * un piloto solo puede pertenecer a un club a la vez (prohibida la doble membresía).
     */
    HERMANDAD("hermandad", "Invitación de Hermandad"),

    /**
     * Convocatoria libre o temporal a rodar. Conforme a la regla R2.3:
     * permite a pilotos rodar juntos sin alterar su club de origen.
     */
    RODADA("rodada", "Invitación a Rodada Oficial")
}

/**
 * Ciclo de vida canónico de una invitación.
 */
enum class InvitationStatus {
    PENDING,    // Emitida y en espera de escaneo o aceptación
    ACCEPTED,   // Aceptada formalmente
    REJECTED,   // Rechazada (voluntariamente o por violación a reglas de negocio como R2.2)
    EXPIRED     // Expirada por transcurso del tiempo de validez
}

/**
 * Modelo de datos inmutable de una invitación oficial.
 */
data class Invitation(
    val id: String,
    val type: InvitationType,
    val clubId: String,
    val clubName: String,
    val inviterUid: String,
    val inviterName: String,
    val inviteeUid: String? = null,
    val inviteeName: String? = null,
    val status: InvitationStatus = InvitationStatus.PENDING,
    val rejectionReason: String? = null,
    val createdAt: Long,
    val expiresAt: Long
) {
    val isExpired: Boolean
        get() = status == InvitationStatus.EXPIRED

    val isPending: Boolean
        get() = status == InvitationStatus.PENDING
}

/**
 * Resultado formal de procesar una aceptación de invitación.
 */
data class InvitationAcceptanceResult(
    val invitation: Invitation,
    val appliedClubId: String?,
    val isSuccess: Boolean,
    val message: String
)

