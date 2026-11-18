package com.rutabikersync.app.feature.vault

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Tipos oficiales de documentos personales para motociclistas (SCREENS F4 & RULES R10.1).
 */
enum class BikerDocumentType(
    val title: String,
    val defaultIssuer: String,
    val isEmergencyPackageEligible: Boolean
) {
    LICENSE("Licencia de Conducir", "Secretaría de Movilidad", true),
    INSURANCE("Póliza de Seguro", "Aseguradora Biker", true),
    CIRCULATION("Tarjeta de Circulación", "Registro Vehicular", false),
    MECHANICAL_INSPECTION("Revisión Mecánica / ITV", "Taller Certificado", false),
    OTHER("Documento Adicional", "Particular", false)
}

/**
 * Semáforo de vencimiento según reglas oficiales (30, 7 y 1 día).
 */
enum class ExpirationSeverity(
    val label: String,
    val textColor: Color,
    val badgeBg: Color,
    val badgeBorder: Color,
    val iconEmoji: String
) {
    VALID("Vigente", Color(0xFF10B981), Color(0xFF062319), Color(0xFF059669), "🟢"),
    EXPIRING_SOON("Vence en 30 días", Color(0xFFF59E0B), Color(0xFF261D09), Color(0xFFD97706), "🟡"),
    CRITICAL("¡Vence pronto (< 7d)!", Color(0xFFEF4444), Color(0xFF2D1010), Color(0xFFDC2626), "🟠"),
    EXPIRED("¡VENCIDO!", Color(0xFFF87171), Color(0xFF381010), Color(0xFFEF4444), "🔴"),
    NO_EXPIRATION("Permanente", Color(0xFF9CA3AF), Color(0xFF1F2430), Color(0xFF4B5563), "⚪")
}

data class ExpirationStatusInfo(
    val severity: ExpirationSeverity,
    val daysRemaining: Long,
    val formattedDate: String
)

/**
 * Documento personal guardado en la Bóveda del motociclista.
 */
data class BikerDocument(
    val id: String,
    val type: BikerDocumentType,
    val title: String,
    val folio: String,
    val issuerOrInsurer: String,
    val expirationDateMillis: Long,
    val emergencyPhone: String = "",
    val notes: String = "",
    val photoUri: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun computeExpirationInfo(nowMillis: Long = System.currentTimeMillis()): ExpirationStatusInfo {
        if (expirationDateMillis <= 0L) {
            return ExpirationStatusInfo(
                severity = ExpirationSeverity.NO_EXPIRATION,
                daysRemaining = 0,
                formattedDate = "Sin vencimiento"
            )
        }

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val dateStr = sdf.format(Date(expirationDateMillis))

        val diffMillis = expirationDateMillis - nowMillis
        val daysRemaining = TimeUnit.MILLISECONDS.toDays(diffMillis)

        val severity = when {
            daysRemaining < 0 -> ExpirationSeverity.EXPIRED
            daysRemaining <= 7 -> ExpirationSeverity.CRITICAL
            daysRemaining <= 30 -> ExpirationSeverity.EXPIRING_SOON
            else -> ExpirationSeverity.VALID
        }

        return ExpirationStatusInfo(
            severity = severity,
            daysRemaining = daysRemaining,
            formattedDate = dateStr
        )
    }
}

/**
 * Ficha Médica de Emergencia personal sellada localmente con Shamir (RULES R10.2 - R10.4).
 * El backend NUNCA almacena estos datos; solo líderes autorizados en rodada activa
 * con SOS pueden recibir temporalmente el paquete de emergencia.
 */
data class EmergencyMedicalSheet(
    val bloodType: String = "O+",
    val allergies: String = "Ninguna conocida",
    val chronicConditions: String = "Ninguna",
    val habitualMedications: String = "Ninguna",
    val isOrganDonor: Boolean = true,
    val emergencyContactName: String = "Hermano Biker / Familiar",
    val emergencyContactRelationship: String = "Contacto Primario",
    val emergencyContactPhone: String = "+52 55 1234 5678",
    val medicalInsuranceName: String = "Seguro Privado / Cobertura Moto",
    val insurancePolicyNumber: String = "POL-EMERG-2026",
    val showOnLockScreen: Boolean = true,
    val isSealedWithShamir: Boolean = true,
    val lastUpdatedMillis: Long = System.currentTimeMillis()
)
