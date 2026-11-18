package com.rutabikersync.app.feature.vault

/**
 * Validador clínico de la Ficha Médica de Emergencia (RULES R10.2).
 * Garantiza integridad clínica antes de permitir el guardado local o compartición en rodada.
 */
object MedicalSheetValidator {

    val VALID_BLOOD_TYPES: List<String> = listOf("O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-")

    private val PHONE_REGEX = Regex("""^\+?[0-9\s\-\(\)]{7,25}$""")
    private val POLICY_REGEX = Regex("""^[A-Za-z0-9\-_]{6,}$""")

    const val DEFAULT_NONE_ALLERGIES = "Ninguna conocida"
    const val DEFAULT_NONE_CONDITIONS = "Ninguna"
    const val DEFAULT_NONE_MEDICATIONS = "Ninguna"

    data class ValidationResult(
        val isValid: Boolean,
        val errors: Map<String, String> = emptyMap()
    ) {
        val bloodTypeError: String? get() = errors["bloodType"]
        val allergiesError: String? get() = errors["allergies"]
        val chronicConditionsError: String? get() = errors["chronicConditions"]
        val emergencyContactNameError: String? get() = errors["emergencyContactName"]
        val emergencyContactPhoneError: String? get() = errors["emergencyContactPhone"]
        val insurancePolicyNumberError: String? get() = errors["insurancePolicyNumber"]
    }

    fun validateBloodType(bloodType: String?): String? {
        val trimmed = bloodType?.trim()?.uppercase()
        if (trimmed == null || !VALID_BLOOD_TYPES.contains(trimmed)) {
            return "Selecciona un grupo sanguíneo válido (O+, O-, A+, A-, B+, B-, AB+, AB-)."
        }
        return null
    }

    fun validateAllergies(allergies: String?, isNoneChecked: Boolean): String? {
        if (isNoneChecked) return null
        val trimmed = allergies?.trim().orEmpty()
        if (trimmed.equals(DEFAULT_NONE_ALLERGIES, ignoreCase = true)) return null
        if (trimmed.length < 10) {
            return "Describe tus alergias con al menos 10 caracteres o marca 'Ninguna conocida'."
        }
        return null
    }

    fun validateChronicConditions(conditions: String?, isNoneChecked: Boolean): String? {
        if (isNoneChecked) return null
        val trimmed = conditions?.trim().orEmpty()
        if (trimmed.equals(DEFAULT_NONE_CONDITIONS, ignoreCase = true)) return null
        if (trimmed.length < 10) {
            return "Especifica tus condiciones médicas con al menos 10 caracteres o marca 'Ninguna'."
        }
        return null
    }

    fun validateEmergencyContactName(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.length < 3) {
            return "El nombre del contacto ICE debe contener al menos 3 caracteres."
        }
        return null
    }

    fun validateEmergencyContactPhone(phone: String?): String? {
        val trimmed = phone?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return "El teléfono de emergencia es obligatorio."
        }
        val digitCount = trimmed.count { it.isDigit() }
        if (!PHONE_REGEX.matches(trimmed) || digitCount < 7) {
            return "Ingresa un teléfono válido con al menos 7 dígitos (ej. +58 412 1234567 o 0414-1234567)."
        }
        return null
    }

    fun validateInsurancePolicyNumber(policyNumber: String?): String? {
        val trimmed = policyNumber?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            // Póliza es opcional
            return null
        }
        if (!POLICY_REGEX.matches(trimmed)) {
            return "El folio de póliza debe ser alfanumérico de al menos 6 caracteres."
        }
        return null
    }

    /**
     * Valida integralmente la ficha médica de emergencia.
     */
    fun validate(
        sheet: EmergencyMedicalSheet,
        isNoneAllergiesChecked: Boolean = sheet.allergies.trim().equals(DEFAULT_NONE_ALLERGIES, ignoreCase = true),
        isNoneConditionsChecked: Boolean = sheet.chronicConditions.trim().equals(DEFAULT_NONE_CONDITIONS, ignoreCase = true)
    ): ValidationResult {
        val errors = mutableMapOf<String, String>()

        validateBloodType(sheet.bloodType)?.let { errors["bloodType"] = it }
        validateAllergies(sheet.allergies, isNoneAllergiesChecked)?.let { errors["allergies"] = it }
        validateChronicConditions(sheet.chronicConditions, isNoneConditionsChecked)?.let { errors["chronicConditions"] = it }
        validateEmergencyContactName(sheet.emergencyContactName)?.let { errors["emergencyContactName"] = it }
        validateEmergencyContactPhone(sheet.emergencyContactPhone)?.let { errors["emergencyContactPhone"] = it }
        validateInsurancePolicyNumber(sheet.insurancePolicyNumber)?.let { errors["insurancePolicyNumber"] = it }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    /**
     * Determina si la ficha está completamente llena con datos válidos para activar el badge de seguridad.
     */
    fun isComplete(sheet: EmergencyMedicalSheet): Boolean {
        val res = validate(sheet)
        if (!res.isValid) return false
        return sheet.emergencyContactName.isNotBlank() &&
                sheet.emergencyContactPhone.isNotBlank() &&
                VALID_BLOOD_TYPES.contains(sheet.bloodType.trim().uppercase())
    }
}

