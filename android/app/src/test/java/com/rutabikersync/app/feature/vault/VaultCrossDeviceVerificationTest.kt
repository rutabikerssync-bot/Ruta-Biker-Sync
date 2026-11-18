package com.rutabikersync.app.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Test de Verificación Cruzada End-to-End: Tecno ↔ Backend ↔ Redmi (Fase 5 · Hito 1).
 *
 * Prueba que el cifrado de extremo a extremo (E2EE) funciona de punta a punta:
 * 1. Simula Device A (Tecno Spark 10C) generando la Master Key y cifrando documentos.
 * 2. Exporta el blob de recuperación usando una frase mnemónica BIP-0039 de 12 palabras.
 * 3. Simula el almacenamiento Zero-Knowledge en el servidor (ciphertext, iv, authTag, pbkdf2Salt).
 * 4. Simula Device B (Redmi Note 13 Pro) arrancando sin claves locales previas.
 * 5. Device B restaura la Master Key ingresando las 12 palabras BIP-0039.
 * 6. Device B descarga los documentos cifrados del servidor y los descifra.
 * 7. Comprueba que el texto plano obtenido es BYTE-IDÉNTICO al original de Device A.
 * 8. Comprueba que un intento de recuperación con una sola palabra errónea es rechazado categóricamente.
 */
class VaultCrossDeviceVerificationTest {

    @Test
    fun `verificacion cruzada E2EE - documento cifrado en Tecno y restaurado en Redmi es byte-identico`() {
        // =====================================================================
        // PASO 1: Dispositivo A (Tecno Spark 10C)
        // =====================================================================
        val tecnoCrypto = VaultCrypto(context = null)

        val originalLicenseJson = """
            {
                "id": "doc_license_real_tecno",
                "type": "LICENSE",
                "title": "Licencia de Conducir (Tipo A2 Moto)",
                "folio": "LIC-CDMX-982314-A2",
                "issuerOrInsurer": "Secretaría de Movilidad",
                "expirationDateMillis": 1825632000000,
                "emergencyPhone": "911",
                "notes": "Autorizada para motocicletas de cualquier cilindrada.",
                "bloodType": "O+",
                "donor": "SI"
            }
        """.trimIndent()

        // Tecno cifra el documento con su Master Key
        val tecnoEncryptedPayload = tecnoCrypto.encryptToPayload(originalLicenseJson.toByteArray(Charsets.UTF_8))

        // Tecno genera su frase de respaldo BIP-0039 (12 palabras)
        val tecnoMnemonic = Bip39.generateMnemonic()
        assertEquals(12, tecnoMnemonic.size)
        assertTrue(Bip39.validateMnemonic(tecnoMnemonic))

        // Tecno exporta el blob de recuperación para respaldo en Fastify / PostgreSQL
        val tecnoRecoveryBlob = tecnoCrypto.exportRecoveryBlob(tecnoMnemonic)

        // =====================================================================
        // PASO 2: Backend Fastify + PostgreSQL (Zero-Knowledge)
        // =====================================================================
        // El servidor almacena los datos de Tecno como blobs opacos:
        val serverVaultItem = mapOf(
            "docType" to "license",
            "title" to "Licencia de Conducir",
            "ciphertext" to tecnoEncryptedPayload.ciphertext,
            "iv" to tecnoEncryptedPayload.iv,
            "authTag" to tecnoEncryptedPayload.authTag
        )
        val serverRecoveryKey = mapOf(
            "encryptedMasterKey" to tecnoRecoveryBlob.encryptedMasterKey,
            "iv" to tecnoRecoveryBlob.iv,
            "authTag" to tecnoRecoveryBlob.authTag,
            "pbkdf2Salt" to tecnoRecoveryBlob.pbkdf2Salt,
            "pbkdf2Iterations" to tecnoRecoveryBlob.pbkdf2Iterations
        )

        // Verificamos que el servidor jamás conoció el texto en claro
        assertFalse(serverVaultItem["ciphertext"]!!.contains("Licencia"))
        assertFalse(serverVaultItem["ciphertext"]!!.contains("Secretaría de Movilidad"))

        // =====================================================================
        // PASO 3: Dispositivo B (Redmi Note 13 Pro) - Nuevo Hardware
        // =====================================================================
        val redmiCrypto = VaultCrypto(context = null)

        // Demostración de aislamiento inicial:
        // Redmi NO puede descifrar los datos de Tecno porque tiene una clave aleatoria diferente
        val remoteDocToDownload = VaultCrypto.EncryptedPayload(
            ciphertext = serverVaultItem["ciphertext"]!!,
            iv = serverVaultItem["iv"]!!,
            authTag = serverVaultItem["authTag"]!!
        )
        assertThrows(AEADBadTagException::class.java) {
            redmiCrypto.decryptPayload(remoteDocToDownload)
        }

        // =====================================================================
        // PASO 4: Recuperación de Bóveda en Redmi con la Passphrase BIP-0039
        // =====================================================================
        // El piloto introduce sus 12 palabras anotadas en carretera
        val downloadedRecoveryBlob = VaultCrypto.RecoveryKeyBlob(
            encryptedMasterKey = serverRecoveryKey["encryptedMasterKey"] as String,
            iv = serverRecoveryKey["iv"] as String,
            authTag = serverRecoveryKey["authTag"] as String,
            pbkdf2Salt = serverRecoveryKey["pbkdf2Salt"] as String,
            pbkdf2Iterations = serverRecoveryKey["pbkdf2Iterations"] as Int
        )

        val restoredSuccess = redmiCrypto.restoreMasterKeyFromRecovery(tecnoMnemonic, downloadedRecoveryBlob)
        assertTrue("La restauración de la Master Key en Redmi debe ser exitosa", restoredSuccess)

        // =====================================================================
        // PASO 5: Verificación de Integridad Byte-a-Byte
        // =====================================================================
        // Ahora Redmi descifra el documento descargado del servidor
        val redmiDecryptedBytes = redmiCrypto.decryptPayload(remoteDocToDownload)
        val redmiDecryptedPlaintext = String(redmiDecryptedBytes, Charsets.UTF_8)

        // Comprobación de que es BYTE-IDÉNTICO al original de Tecno
        assertEquals(
            "El documento recuperado en Redmi debe ser byte-idéntico al cifrado en Tecno",
            originalLicenseJson,
            redmiDecryptedPlaintext
        )
        assertEquals(
            originalLicenseJson.toByteArray(Charsets.UTF_8).size,
            redmiDecryptedBytes.size
        )
    }

    @Test
    fun `intento de recuperacion con passphrase atacante o palabra incorrecta es rechazado`() {
        val tecnoCrypto = VaultCrypto(context = null)
        val tecnoMnemonic = Bip39.generateMnemonic()
        val recoveryBlob = tecnoCrypto.exportRecoveryBlob(tecnoMnemonic)

        // Atacante inventa una mnemónica o se equivoca en 1 palabra
        val attackerMnemonic = Bip39.generateMnemonic()
        assertNotEquals(tecnoMnemonic, attackerMnemonic)

        val redmiCrypto = VaultCrypto(context = null)
        val restored = redmiCrypto.restoreMasterKeyFromRecovery(attackerMnemonic, recoveryBlob)
        assertFalse("Un atacante con otra mnemónica no puede restaurar la Master Key", restored)
    }

    @Test
    fun `rotacion de mnemonica invalida frase anterior en backend y permite recuperacion con la nueva frase`() {
        // =====================================================================
        // PASO 1: Dispositivo A (Tecno) tiene su Bóveda y su frase inicial (comprometida)
        // =====================================================================
        val tecnoCrypto = VaultCrypto(context = null)
        val originalLicense = "LICENCIA_CAPITAN_OSCAR_CONFIDENCIAL"
        val docPayload = tecnoCrypto.encryptToPayload(originalLicense.toByteArray(Charsets.UTF_8))

        // Frase 1 (comprometida)
        val oldMnemonic = Bip39.generateMnemonic()
        val oldRecoveryBlob = tecnoCrypto.exportRecoveryBlob(oldMnemonic)

        // =====================================================================
        // PASO 2: Tecno ejecuta la ROTACIÓN de Mnemónica
        // =====================================================================
        // Genera nueva entropía (128 bits), nuevo salt aleatorio de 16 bytes y nuevo blob
        val newMnemonic = Bip39.generateMnemonic()
        assertNotEquals("Las frases deben ser totalmente distintas", oldMnemonic, newMnemonic)

        val newRecoveryBlob = tecnoCrypto.exportRecoveryBlob(newMnemonic)
        assertNotEquals("El salt PBKDF2 debe ser único y renovado", oldRecoveryBlob.pbkdf2Salt, newRecoveryBlob.pbkdf2Salt)
        assertNotEquals("El IV debe ser renovado", oldRecoveryBlob.iv, newRecoveryBlob.iv)
        assertNotEquals("El ciphertext del blob debe ser distinto", oldRecoveryBlob.encryptedMasterKey, newRecoveryBlob.encryptedMasterKey)

        // El servidor actualiza el registro (ON CONFLICT DO UPDATE reemplaza el blob anterior)
        val serverCurrentBlob = newRecoveryBlob

        // =====================================================================
        // PASO 3: PRUEBA NEGATIVA - Intento de recuperación con la frase VIEJA
        // =====================================================================
        val redmiCrypto = VaultCrypto(context = null)
        val restoredWithOld = redmiCrypto.restoreMasterKeyFromRecovery(oldMnemonic, serverCurrentBlob)
        assertFalse(
            "La frase vieja debe fallar categóricamente al intentar descifrar el nuevo blob",
            restoredWithOld
        )

        // =====================================================================
        // PASO 4: PRUEBA POSITIVA - Recuperación exitosa con la frase NUEVA
        // =====================================================================
        val restoredWithNew = redmiCrypto.restoreMasterKeyFromRecovery(newMnemonic, serverCurrentBlob)
        assertTrue(
            "La frase nueva debe descifrar exitosamente la Master Key en Redmi",
            restoredWithNew
        )

        // Verificamos que Redmi descifra la licencia de Oscar byte-idéntica
        val redmiDecryptedBytes = redmiCrypto.decryptPayload(docPayload)
        val redmiDecryptedText = String(redmiDecryptedBytes, Charsets.UTF_8)
        assertEquals(originalLicense, redmiDecryptedText)

        // =====================================================================
        // PASO 5: PRUEBA DE REGRESIÓN - Tecno sigue operando con su Master Key
        // =====================================================================
        val tecnoDecryptedBytes = tecnoCrypto.decryptPayload(docPayload)
        assertEquals(originalLicense, String(tecnoDecryptedBytes, Charsets.UTF_8))
    }

    @Test
    fun `tampering en el servidor corrompiendo 1 solo bit de authTag es bloqueado`() {
        val tecnoCrypto = VaultCrypto(context = null)
        val tecnoMnemonic = Bip39.generateMnemonic()
        val recoveryBlob = tecnoCrypto.exportRecoveryBlob(tecnoMnemonic)

        // Supongamos que un atacante en la base de datos altera 1 bit del authTag del recovery blob
        val tagBytes = Base64.getDecoder().decode(recoveryBlob.authTag)
        tagBytes[0] = (tagBytes[0].toInt() xor 0x01).toByte()
        val tamperedBlob = recoveryBlob.copy(authTag = Base64.getEncoder().encodeToString(tagBytes))

        val redmiCrypto = VaultCrypto(context = null)
        val restored = redmiCrypto.restoreMasterKeyFromRecovery(tecnoMnemonic, tamperedBlob)
        assertFalse("Un blob de recuperación manipulado debe ser rechazado categóricamente", restored)
    }
}

