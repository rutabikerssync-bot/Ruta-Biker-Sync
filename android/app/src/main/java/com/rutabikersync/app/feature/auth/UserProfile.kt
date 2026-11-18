package com.rutabikersync.app.feature.auth

import com.rutabikersync.app.feature.groups.AccountType

/**
 * Rol oficial del usuario en la plataforma.
 * Mapeado directamente a AccountType para compatibilidad total con Phase 1.
 */
typealias UserRole = AccountType

/**
 * Tipos de sangre reconocidos para la ficha médica de emergencia del convoy.
 */
enum class BloodType(val label: String) {
    O_POS("O+"),
    O_NEG("O-"),
    A_POS("A+"),
    A_NEG("A-"),
    B_POS("B+"),
    B_NEG("B-"),
    AB_POS("AB+"),
    AB_NEG("AB-");

    companion object {
        fun fromLabel(label: String?): BloodType = entries.find {
            it.label.equals(label, ignoreCase = true) || it.name.equals(label, ignoreCase = true)
        } ?: O_POS
    }
}

/**
 * Opciones de visibilidad y privacidad del perfil y de la motocicleta (D98).
 */
enum class Visibility(val label: String) {
    PUBLIC("Público"),
    CLUB_ONLY("Solo mi club"),
    PRIVATE("Privado");

    companion object {
        fun fromString(value: String?): Visibility = entries.find {
            it.name.equals(value, ignoreCase = true)
        } ?: PUBLIC
    }
}

/**
 * Perfil oficial de motociclista obligatorio (Bloque A) enriquecido con datos técnicos de la moto,
 * visibilidad y soporte para fotos en Postgres BYTEA (D98).
 */
data class UserProfile(
    val uid: String,
    val email: String,
    val photoUrl: String? = null,
    val photoBase64: String? = null,
    val aliasBiker: String = "",
    val motorcycleModel: String = "",
    val pinColorHex: String = "#E5B869", // Oro Heritage por defecto
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val bloodType: BloodType = BloodType.O_POS,

    // Datos técnicos enriquecidos de la motocicleta
    val motorcycleBrand: String = "",
    val motorcycleDisplacement: Int = 0,
    val motorcycleYear: Int = 0,
    val motorcycleColor: String = "",
    val motorcycleSerial: String = "",
    val motorcyclePhotoUrl: String? = null,
    val motorcyclePhotoBase64: String? = null,

    // Preferencias de visibilidad
    val profileVisibility: Visibility = Visibility.PUBLIC,
    val motorcycleVisibility: Visibility = Visibility.PUBLIC
) {
    /**
     * Valida si el perfil cumple todas las reglas de negocio para operar en convoy.
     */
    val isComplete: Boolean
        get() = uid.isNotBlank() &&
                email.isNotBlank() &&
                aliasBiker.trim().length in 3..25 &&
                motorcycleModel.trim().length in 3..40 &&
                emergencyContactName.trim().length >= 3 &&
                emergencyContactPhone.trim().length >= 7 &&
                ALLOWED_PIN_COLORS.contains(pinColorHex.uppercase())

    companion object {
        val ALLOWED_PIN_COLORS = listOf(
            "#E5B869", // Oro Heritage
            "#2563EB", // Azul Real
            "#DC2626", // Rojo Fuego
            "#16A34A", // Verde Convoy
            "#EA580C", // Naranja Ruta
            "#9333EA", // Púrpura Nocturno
            "#0891B2", // Cian Ruta
            "#B45309"  // Cobre Cuero
        )
    }
}

