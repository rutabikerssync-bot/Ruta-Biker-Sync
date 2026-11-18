package com.rutabikersync.app.feature.sos

import android.util.Log
import com.rutabikersync.app.feature.vault.VaultCrypto
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado efimero AES-256-GCM para el paquete de emergencia medica (R10.1, R10.2).
 *
 * La clave se genera en SecureRandom al inicio de cada rodada y NO persiste en disco.
 * Solo se puede descifrar mientras SosStatus sea ACTIVE_EMERGENCY; cualquier intento
 * fuera de ese contexto se registra en el log de auditoria (R10.3) y retorna null.
 *
 * Uso:
 *   1. Llamar [initForRide] al inicio de cada rodada.
 *   2. Llamar [encryptPacket] para cifrar el paquete y guardarlo cifrado en memoria.
 *   3. Llamar [decryptPacket] solo cuando hay SOS activo -- verifica [SosRepository].
 *   4. Llamar [clearKey] al finalizar la rodada (en [SosRepository.clearAllOnRideFinished]).
 */
class EmergencyPackageCrypto {

    companion object {
        private const val TAG = "EmergencyPackageCrypto"
        private const val AES_KEY_SIZE = 256       // bits
        private const val GCM_IV_SIZE  = 12        // bytes
        private const val GCM_TAG_BITS = 128       // bits
        private const val ALGORITHM    = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Separador entre IV y ciphertext en el blob almacenado.
         * Layout: [12 bytes IV][N bytes ciphertext+tag]
         */
        private const val IV_LENGTH_BYTES = GCM_IV_SIZE
    }

    /** Clave efimera AES-256; null si la rodada no ha iniciado o ya finalizo. */
    @Volatile
    private var sessionKey: SecretKey? = null

    // -------------------------------------------------------------------------
    // API publica
    // -------------------------------------------------------------------------

    /**
     * Genera una clave AES-256 en SecureRandom para esta sesion de rodada.
     * Debe llamarse cada vez que inicia una rodada nueva.
     */
    fun initForRide() {
        val kg = KeyGenerator.getInstance(ALGORITHM)
        kg.init(AES_KEY_SIZE, SecureRandom())
        sessionKey = kg.generateKey()
        Log.i(TAG, "Clave efimera de sesion generada para rodada activa.")
    }

    /**
     * Cifra [packet] con AES-256-GCM usando la clave de sesion.
     * Retorna un blob [IV || ciphertext] como ByteArray.
     * Lanza [IllegalStateException] si la rodada no inicio (clave nula).
     */
    fun encryptPacket(packet: EmergencyRescuePacket): ByteArray {
        val key = sessionKey
            ?: error("EmergencyPackageCrypto: initForRide() debe llamarse antes de cifrar.")

        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

        val plaintext = serializePacket(packet)
        val ciphertext = cipher.doFinal(plaintext)

        // Blob: 12 bytes IV + N bytes ciphertext+GCM-tag
        val blob = ByteArray(IV_LENGTH_BYTES + ciphertext.size)
        System.arraycopy(iv,         0, blob, 0,                iv.size)
        System.arraycopy(ciphertext, 0, blob, IV_LENGTH_BYTES,  ciphertext.size)

        Log.i(TAG, "Paquete cifrado: ${blob.size} bytes (IV=${IV_LENGTH_BYTES}B, cipher=${ciphertext.size}B).")
        return blob
    }

    /**
     * Descifra [encrypted] solo si [sosRepo] tiene un SOS activo.
     *
     * - Si NO hay SOS activo: registra en auditoria y retorna null (R10.3).
     * - Si la clave es nula (rodada no iniciada): retorna null.
     * - Si la operacion de descifrado falla: retorna null y loga el error.
     */
    fun decryptPacket(
        encrypted: ByteArray,
        sosRepo: SosRepository
    ): EmergencyRescuePacket? {
        // Verificacion de acceso controlado (Regla R10.2)
        val hasActiveSos = sosRepo.activeAlerts.value.any { !it.isResolved }
        if (!hasActiveSos) {
            Log.w(TAG, "Intento de descifrado sin SOS activo -- acceso denegado. Registrando auditoria.")
            sosRepo.logAccessToEmergencyPacket(
                sosId          = "none",
                viewerUserId   = "local-device",
                viewerUserName = "Sistema (acceso denegado)",
                targetRiderName = "paquete local"
            )
            return null
        }

        val key = sessionKey
        if (key == null) {
            Log.e(TAG, "Clave de sesion nula -- rodada no iniciada.")
            return null
        }

        if (encrypted.size <= IV_LENGTH_BYTES) {
            Log.e(TAG, "Blob cifrado demasiado corto: ${encrypted.size} bytes.")
            return null
        }

        return try {
            val iv         = encrypted.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = encrypted.copyOfRange(IV_LENGTH_BYTES, encrypted.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)

            deserializePacket(plaintext).also {
                Log.i(TAG, "Paquete descifrado correctamente para SOS activo.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error descifrado paquete: ${e.message}", e)
            null
        }
    }

    /**
     * Destruye la clave de sesion de la memoria.
     * Debe llamarse en [SosRepository.clearAllOnRideFinished].
     */
    fun clearKey() {
        sessionKey = null
        Log.i(TAG, "Clave efimera de sesion destruida -- fin de rodada.")
    }

    // -------------------------------------------------------------------------
    // Serializacion simple (texto plano delimitado por '\n')
    // Sin dependencias de JSON externas para mantener esto puro.
    // Formato: campo=valor, un campo por linea.
    // -------------------------------------------------------------------------

    private fun serializePacket(p: EmergencyRescuePacket): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("riderId=${p.riderId}")
        sb.appendLine("fullName=${p.fullName}")
        sb.appendLine("riderAlias=${p.riderAlias}")
        sb.appendLine("bloodType=${p.bloodType}")
        sb.appendLine("allergies=${p.allergies.joinToString("|")}")
        sb.appendLine("medicalConditions=${p.medicalConditions.joinToString("|")}")
        sb.appendLine("regularMedications=${p.regularMedications.joinToString("|")}")
        sb.appendLine("organDonor=${p.organDonor}")
        sb.appendLine("emergencyContacts=${p.emergencyContacts.joinToString(";") { c ->
            "${c.name}~${c.relationship}~${c.phoneNumber}~${c.isPrimary}"
        }}")
        sb.appendLine("insuranceCompany=${p.insuranceCompany ?: ""}")
        sb.appendLine("insurancePolicyNumber=${p.insurancePolicyNumber ?: ""}")
        sb.appendLine("insurancePhone=${p.insurancePhone ?: ""}")
        sb.appendLine("bikeBrand=${p.bikeBrand ?: ""}")
        sb.appendLine("bikeModel=${p.bikeModel ?: ""}")
        sb.appendLine("bikePlate=${p.bikePlate ?: ""}")
        sb.appendLine("bikeColor=${p.bikeColor ?: ""}")
        sb.appendLine("createdAtTimestamp=${p.createdAtTimestamp}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun deserializePacket(bytes: ByteArray): EmergencyRescuePacket {
        val map = bytes.toString(Charsets.UTF_8)
            .lines()
            .filter { it.contains('=') }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx) to line.substring(idx + 1)
            }

        fun field(key: String) = map[key] ?: ""
        fun list(key: String)  = field(key).split("|").filter { it.isNotEmpty() }

        val contacts = field("emergencyContacts")
            .split(";")
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val parts = entry.split("~")
                if (parts.size == 4) {
                    EmergencyContact(
                        name         = parts[0],
                        relationship = parts[1],
                        phoneNumber  = parts[2],
                        isPrimary    = parts[3].toBooleanStrictOrNull() ?: false
                    )
                } else null
            }

        return EmergencyRescuePacket(
            riderId              = field("riderId"),
            fullName             = field("fullName"),
            riderAlias           = field("riderAlias"),
            bloodType            = field("bloodType"),
            allergies            = list("allergies"),
            medicalConditions    = list("medicalConditions"),
            regularMedications   = list("regularMedications"),
            organDonor           = field("organDonor").toBooleanStrictOrNull() ?: false,
            emergencyContacts    = contacts,
            insuranceCompany     = field("insuranceCompany").ifEmpty { null },
            insurancePolicyNumber = field("insurancePolicyNumber").ifEmpty { null },
            insurancePhone       = field("insurancePhone").ifEmpty { null },
            bikeBrand            = field("bikeBrand").ifEmpty { null },
            bikeModel            = field("bikeModel").ifEmpty { null },
            bikePlate            = field("bikePlate").ifEmpty { null },
            bikeColor            = field("bikeColor").ifEmpty { null },
            createdAtTimestamp   = field("createdAtTimestamp").toLongOrNull() ?: System.currentTimeMillis()
        )
    }
}