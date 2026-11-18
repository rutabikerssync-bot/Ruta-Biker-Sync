package com.rutabikersync.app.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Suite de Tests Unitarios Exhaustivos para [VaultCrypto] y [Bip39] (Fase 5 · Hito 1).
 *
 * Cubre todos los vectores criptográficos requeridos:
 * 1. Cifrado y descifrado round-trip con AES-256-GCM.
 * 2. Detección de tag de autenticación corrupto (AEADBadTagException).
 * 3. Fallo de descifrado cuando cambia el salt KDF o se simula manipulación de datos.
 * 4. Fallo de recuperación cuando la passphrase mnemónica es incorrecta.
 * 5. Éxito de restauración de Master Key en un nuevo dispositivo simulado usando passphrase BIP-0039 correcta.
 * 6. Validación determinista de entropía (128 bits) y checksum de 4 bits en BIP-0039.
 */
class VaultCryptoTest {

    private val crypto = VaultCrypto(context = null)

    @Test
    fun `1_1 encrypt produce blob no vacio`() {
        val blob = crypto.encrypt("datos sensibles")
        assertTrue("El blob cifrado no debe estar vacío", blob.isNotBlank())
    }

    @Test
    fun `1_2 encrypt no devuelve texto plano`() {
        val plaintext = "tipo_sangre:O+"
        val blob = crypto.encrypt(plaintext)
        assertNotEquals("El blob no debe ser igual al texto plano", plaintext, blob)
    }

    @Test
    fun `1_3 round-trip encrypt-decrypt es identidad`() {
        val original = """{"bloodType":"A+","allergies":"Penicilina","isOrganDonor":true}"""
        val blob = crypto.encrypt(original)
        val recovered = crypto.decrypt(blob)
        assertEquals("El texto recuperado debe ser igual al original", original, recovered)
    }

    @Test
    fun `1_4 round-trip con payload desglosado para backend (ciphertext, iv, authTag)`() {
        val original = "Documento de Licencia Biker A2 - Semovi"
        val payload = crypto.encryptToPayload(original.toByteArray(Charsets.UTF_8))

        assertNotNull(payload.ciphertext)
        assertNotNull(payload.iv)
        assertNotNull(payload.authTag)
        assertEquals(12, Base64.getDecoder().decode(payload.iv).size)
        assertEquals(16, Base64.getDecoder().decode(payload.authTag).size)

        val decryptedBytes = crypto.decryptPayload(payload)
        val recovered = String(decryptedBytes, Charsets.UTF_8)
        assertEquals(original, recovered)
    }

    @Test
    fun `2_1 fallo con AEADBadTagException si el tag de autenticacion esta corrupto`() {
        val original = "Ficha Médica de Emergencia: Alergia al látex"
        val payload = crypto.encryptToPayload(original.toByteArray(Charsets.UTF_8))

        // Corromper el último byte del authTag
        val tagBytes = Base64.getDecoder().decode(payload.authTag)
        tagBytes[tagBytes.size - 1] = (tagBytes[tagBytes.size - 1].toInt() xor 0xFF).toByte()
        val corruptedTag = Base64.getEncoder().encodeToString(tagBytes)

        val corruptedPayload = payload.copy(authTag = corruptedTag)

        assertThrows(AEADBadTagException::class.java) {
            crypto.decryptPayload(corruptedPayload)
        }
    }

    @Test
    fun `2_2 fallo con AEADBadTagException si el ciphertext esta alterado (tamper resistance)`() {
        val original = "Póliza de Seguro Moto Vigente"
        val payload = crypto.encryptToPayload(original.toByteArray(Charsets.UTF_8))

        // Alterar un byte del ciphertext
        val cipherBytes = Base64.getDecoder().decode(payload.ciphertext)
        cipherBytes[0] = (cipherBytes[0].toInt() xor 0x01).toByte()
        val tamperedCiphertext = Base64.getEncoder().encodeToString(cipherBytes)

        val tamperedPayload = payload.copy(ciphertext = tamperedCiphertext)

        assertThrows(AEADBadTagException::class.java) {
            crypto.decryptPayload(tamperedPayload)
        }
    }

    @Test
    fun `3_1 fallo si el salt KDF cambia entre cifrado y descifrado (simula ataque de diccionario o manipulacion)`() {
        val mnemonic = Bip39.generateMnemonic()
        val salt1 = ByteArray(16) { 0x01 }
        val salt2 = ByteArray(16) { 0x02 } // Salt diferente

        val key1 = Bip39.deriveRecoveryKey(mnemonic, salt1)
        val key2 = Bip39.deriveRecoveryKey(mnemonic, salt2)

        val payload = crypto.encryptToPayload("secreto de estado".toByteArray(Charsets.UTF_8), key1)

        // Intentar descifrar con la clave derivada con el salt incorrecto debe fallar
        assertThrows(AEADBadTagException::class.java) {
            crypto.decryptPayload(payload, key2)
        }
    }

    @Test
    fun `4_1 fallo si la passphrase de recuperacion es incorrecta`() {
        val correctMnemonic = Bip39.generateMnemonic()
        val wrongMnemonic = Bip39.generateMnemonic() // Otra mnemónica válida pero diferente

        val blob = crypto.exportRecoveryBlob(correctMnemonic)

        // Intentar restaurar en un nuevo dispositivo con la mnemónica incorrecta
        val newDeviceCrypto = VaultCrypto(context = null)
        val restored = newDeviceCrypto.restoreMasterKeyFromRecovery(wrongMnemonic, blob)

        assertFalse("La restauración debe fallar si la mnemónica no es la correcta", restored)
    }

    @Test
    fun `5_1 exito en nuevo dispositivo con passphrase correcta (recuperacion de Master Key byte-identica)`() {
        // Dispositivo 1 (Tecno)
        val device1Crypto = VaultCrypto(context = null)
        val secretDoc = """{"title":"Tarjeta de Circulación","placa":"ABC-123"}"""
        val encryptedDocPayload = device1Crypto.encryptToPayload(secretDoc.toByteArray(Charsets.UTF_8))

        // Generar mnemónica BIP-0039 de respaldo y exportar blob de recuperación
        val mnemonic = Bip39.generateMnemonic()
        val recoveryBlob = device1Crypto.exportRecoveryBlob(mnemonic)

        // Dispositivo 2 (Redmi - simula nuevo hardware con almacenamiento vacío)
        val device2Crypto = VaultCrypto(context = null)

        // Antes de restaurar, device2 tiene su propia clave y no puede descifrar el documento de device1
        assertThrows(AEADBadTagException::class.java) {
            device2Crypto.decryptPayload(encryptedDocPayload)
        }

        // Restaurar la Master Key usando la frase mnemónica y el blob
        val restoreSuccess = device2Crypto.restoreMasterKeyFromRecovery(mnemonic, recoveryBlob)
        assertTrue("La restauración de la Master Key debe ser exitosa", restoreSuccess)

        // Ahora device2 tiene la misma Master Key que device1 y debe descifrar el documento
        val decryptedDocBytes = device2Crypto.decryptPayload(encryptedDocPayload)
        val decryptedDoc = String(decryptedDocBytes, Charsets.UTF_8)
        assertEquals(secretDoc, decryptedDoc)
    }

    @Test
    fun `6_1 generacion y validacion determinista BIP-0039`() {
        val mnemonic = Bip39.generateMnemonic()
        assertEquals("BIP-0039 debe generar exactamente 12 palabras", 12, mnemonic.size)
        assertTrue("La mnemónica generada debe ser válida según BIP-0039", Bip39.validateMnemonic(mnemonic))

        // Todas las palabras deben pertenecer a la lista oficial de 2048 palabras
        for (word in mnemonic) {
            assertTrue("La palabra '$word' debe estar en Bip39WordList", Bip39WordList.indexOf(word) >= 0)
        }
    }

    @Test
    fun `6_2 deteccion de checksum BIP-0039 corrupto o palabra alterada`() {
        val validMnemonic = Bip39.generateMnemonic().toMutableList()

        // Cambiar una palabra al azar por otra de la lista
        val originalWord = validMnemonic[0]
        validMnemonic[0] = if (originalWord == "abandon") "ability" else "abandon"

        // En más del 93.75% de los casos (15/16) el checksum de 4 bits no coincidirá
        // Si justo coincide el checksum, cambiamos otra palabra para garantizar fallo
        var isInvalid = !Bip39.validateMnemonic(validMnemonic)
        if (!isInvalid) {
            validMnemonic[1] = "zoo"
            isInvalid = !Bip39.validateMnemonic(validMnemonic)
        }
        assertTrue("Una mnemónica con palabras permutadas debe fallar la validación de checksum", isInvalid)

        // Una palabra inventada debe fallar inmediatamente
        val invalidWordMnemonic = validMnemonic.toMutableList()
        invalidWordMnemonic[0] = "palabrainventadaquenoexistebip39"
        assertFalse("Palabra que no existe en el diccionario debe ser rechazada", Bip39.validateMnemonic(invalidWordMnemonic))
    }

    @Test
    fun `7_1 dos encripciones del mismo texto producen blobs distintos por IV aleatorio`() {
        val plaintext = "ficha-medica-rider"
        val blob1 = crypto.encrypt(plaintext)
        val blob2 = crypto.encrypt(plaintext)
        assertNotEquals("Dos cifrados deben producir blobs distintos por IV aleatorio", blob1, blob2)
        assertEquals(plaintext, crypto.decrypt(blob1))
        assertEquals(plaintext, crypto.decrypt(blob2))
    }
}
