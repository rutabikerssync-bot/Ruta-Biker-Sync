package com.rutabikersync.app.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicalSheetValidatorTest {

    @Test
    fun `test valid default medical sheet succeeds validation`() {
        val validSheet = EmergencyMedicalSheet(
            bloodType = "O+",
            allergies = "Ninguna conocida",
            chronicConditions = "Ninguna",
            habitualMedications = "Ninguna",
            isOrganDonor = true,
            emergencyContactName = "Oscar Martínez",
            emergencyContactRelationship = "Hermano Biker",
            emergencyContactPhone = "+58 412 1234567",
            medicalInsuranceName = "Seguro Privado",
            insurancePolicyNumber = "POL-EMERG-2026"
        )

        val result = MedicalSheetValidator.validate(validSheet)
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
        assertTrue(MedicalSheetValidator.isComplete(validSheet))
    }

    @Test
    fun `test all 8 official blood types are accepted`() {
        for (type in listOf("O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-")) {
            assertNull("Expected $type to be valid", MedicalSheetValidator.validateBloodType(type))
        }
    }

    @Test
    fun `test invalid blood types are rejected`() {
        for (invalid in listOf("X+", "O", "", "  ", "AB++", "Rh+", "UNKNOWN")) {
            assertNotNull("Expected $invalid to be rejected", MedicalSheetValidator.validateBloodType(invalid))
        }
    }

    @Test
    fun `test allergies validation with none checked and custom text`() {
        // Marcado como ninguna conocida
        assertNull(MedicalSheetValidator.validateAllergies("", isNoneChecked = true))
        assertNull(MedicalSheetValidator.validateAllergies("Ninguna conocida", isNoneChecked = false))

        // Texto muy corto sin marcar ninguna
        val shortError = MedicalSheetValidator.validateAllergies("Polvo", isNoneChecked = false)
        assertNotNull(shortError)

        // Texto clínico descriptivo >= 10 caracteres
        assertNull(MedicalSheetValidator.validateAllergies("Alergia severa a la penicilina y AINEs", isNoneChecked = false))
    }

    @Test
    fun `test chronic conditions validation with none checked and custom text`() {
        assertNull(MedicalSheetValidator.validateChronicConditions("", isNoneChecked = true))
        assertNull(MedicalSheetValidator.validateChronicConditions("Ninguna", isNoneChecked = false))

        val shortError = MedicalSheetValidator.validateChronicConditions("Asma", isNoneChecked = false)
        assertNotNull(shortError)

        assertNull(MedicalSheetValidator.validateChronicConditions("Hipertensión arterial controlada", isNoneChecked = false))
    }

    @Test
    fun `test emergency contact name validation`() {
        assertNotNull(MedicalSheetValidator.validateEmergencyContactName(""))
        assertNotNull(MedicalSheetValidator.validateEmergencyContactName("Ed"))
        assertNull(MedicalSheetValidator.validateEmergencyContactName("Ana"))
        assertNull(MedicalSheetValidator.validateEmergencyContactName("Capitán Oscar"))
    }

    @Test
    fun `test emergency contact phone validation`() {
        assertNotNull(MedicalSheetValidator.validateEmergencyContactPhone(""))
        assertNotNull(MedicalSheetValidator.validateEmergencyContactPhone("12345")) // < 7 dígitos
        assertNotNull(MedicalSheetValidator.validateEmergencyContactPhone("not-a-phone"))

        // Formatos válidos de Venezuela, México e internacionales
        assertNull(MedicalSheetValidator.validateEmergencyContactPhone("+58 412 1234567"))
        assertNull(MedicalSheetValidator.validateEmergencyContactPhone("0414-7654321"))
        assertNull(MedicalSheetValidator.validateEmergencyContactPhone("+52 55 1234 5678"))
        assertNull(MedicalSheetValidator.validateEmergencyContactPhone("(0212) 987-6543"))
    }

    @Test
    fun `test insurance policy number optional but formatted if present`() {
        // Vacío es válido (opcional)
        assertNull(MedicalSheetValidator.validateInsurancePolicyNumber(""))
        assertNull(MedicalSheetValidator.validateInsurancePolicyNumber("   "))

        // Demasiado corto < 6 caracteres
        assertNotNull(MedicalSheetValidator.validateInsurancePolicyNumber("POL1"))

        // Caracteres especiales inválidos
        assertNotNull(MedicalSheetValidator.validateInsurancePolicyNumber("POL#123*"))

        // Válido alfanumérico
        assertNull(MedicalSheetValidator.validateInsurancePolicyNumber("POL-2026-X"))
        assertNull(MedicalSheetValidator.validateInsurancePolicyNumber("QUALITAS12345"))
    }

    @Test
    fun `test invalid sheet fails validation with explicit field errors`() {
        val badSheet = EmergencyMedicalSheet(
            bloodType = "INVALID",
            allergies = "Mani", // < 10 chars sin check
            chronicConditions = "Dolor", // < 10 chars sin check
            emergencyContactName = "Al", // < 3 chars
            emergencyContactPhone = "123", // < 7 digits
            insurancePolicyNumber = "12" // < 6 chars
        )

        val result = MedicalSheetValidator.validate(
            sheet = badSheet,
            isNoneAllergiesChecked = false,
            isNoneConditionsChecked = false
        )

        assertFalse(result.isValid)
        assertEquals(6, result.errors.size)
        assertNotNull(result.bloodTypeError)
        assertNotNull(result.allergiesError)
        assertNotNull(result.chronicConditionsError)
        assertNotNull(result.emergencyContactNameError)
        assertNotNull(result.emergencyContactPhoneError)
        assertNotNull(result.insurancePolicyNumberError)
        assertFalse(MedicalSheetValidator.isComplete(badSheet))
    }
}

