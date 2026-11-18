package com.rutabikersync.app.feature.sos

/**
 * Modelos de dominio para el sistema de SOS, detección de caídas
 * y paquete de rescate médico de emergencia (Fase 5 - R10.2, R10.3, R10.5).
 */

enum class SosTriggerType(val displayName: String) {
    MANUAL("Botón SOS Manual"),
    FALL_DETECTION("Detección Automática de Caída"),
    LEADER_DECLARATION("Declaración de Emergencia por Líder")
}

enum class SosStatus {
    IDLE,
    COUNTDOWN,
    ACTIVE_EMERGENCY,
    RESOLVED,
    CANCELLED
}

data class EmergencyContact(
    val name: String,
    val relationship: String,
    val phoneNumber: String,
    val isPrimary: Boolean = true
)

data class EmergencyRescuePacket(
    val riderId: String,
    val fullName: String,
    val riderAlias: String,
    val bloodType: String, // ej. "O+", "A+", "B+", "AB-"
    val allergies: List<String> = emptyList(),
    val medicalConditions: List<String> = emptyList(),
    val regularMedications: List<String> = emptyList(),
    val organDonor: Boolean = true,
    val emergencyContacts: List<EmergencyContact> = emptyList(),
    val insuranceCompany: String? = null,
    val insurancePolicyNumber: String? = null,
    val insurancePhone: String? = null,
    val bikeBrand: String? = null,
    val bikeModel: String? = null,
    val bikePlate: String? = null,
    val bikeColor: String? = null,
    val createdAtTimestamp: Long = System.currentTimeMillis()
)

data class SosAlertEvent(
    val sosId: String,
    val senderShortId: UShort,
    val senderName: String,
    val senderRole: String,
    val latitude: Double,
    val longitude: Double,
    val triggerType: SosTriggerType,
    val timestampMillis: Long = System.currentTimeMillis(),
    val rescuePacket: EmergencyRescuePacket? = null,
    val isResolved: Boolean = false
)

data class SosAccessAudit(
    val auditId: String,
    val sosId: String,
    val viewerUserId: String,
    val viewerUserName: String,
    val targetRiderName: String,
    val openedAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long = System.currentTimeMillis() + (60 * 60 * 1000L) // Regla R10.3: 60 minutos
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() > expiresAtMillis

    val remainingMinutes: Int
        get() = ((expiresAtMillis - System.currentTimeMillis()) / (60 * 1000L)).toInt().coerceAtLeast(0)
}
