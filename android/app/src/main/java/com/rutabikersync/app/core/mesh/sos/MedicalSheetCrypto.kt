package com.rutabikersync.app.core.mesh.sos

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Modelo de datos clínicos de rescate para el protocolo de emergencia en malla (Fase 6 · Hito 4 · Etapa 3).
 *
 * Contiene únicamente los datos estrictamente necesarios para soporte vital extrahospitalario (R10.2).
 */
@Serializable
data class MedicalRescueData(
    val bloodType: String = "O+",
    val allergies: String = "Ninguna conocida",
    val medication: String = "Ninguna",
    val conditions: String = "Ninguna",
    val insurancePolicy: String = "",
    val iceName: String = "",
    val icePhone: String = ""
)

/**
 * Criptografía simétrica AES-256-GCM para Fichas Médicas de Emergencia en Malla (Fase 6 · Hito 4 · Etapa 3).
 *
 * Principio Zero-Knowledge de Relays Ciegos (Opción A):
 * - Los nodos intermedios retransmiten el paquete sin tener acceso a la clave de rescate (`medicalRescueKey`).
 * - Solo el Líder Principal y el Sargento de Armas con la clave distribuida en el `manifest.json` firmado
 *   pueden descifrar y auditar los datos clínicos.
 * - Implementa sanitización activa de buffers en memoria (`Arrays.fill(0)`) según la Regla R12.
 */
object MedicalSheetCrypto {

    const val AES_KEY_BYTES = 32 // 256 bits
    const val GCM_IV_BYTES = 12  // 96 bits
    const val GCM_TAG_BYTES = 16 // 128 bits auth tag
    private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    private const val CIPHER_ALGO = "AES/GCM/NoPadding"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val secureRandom = SecureRandom()

    /**
     * Genera una clave aleatoria de 256 bits para el rescate médico de una rodada.
     */
    fun generateRescueKey(): ByteArray {
        val key = ByteArray(AES_KEY_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * Convierte una clave de 256 bits a representación hexadecimal.
     */
    fun keyToHex(key: ByteArray): String {
        val sb = StringBuilder(key.size * 2)
        for (b in key) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /**
     * Convierte una clave hexadecimal de 64 caracteres a ByteArray de 32 bytes.
     */
    fun keyFromHex(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length == AES_KEY_BYTES * 2) {
            "Clave hexadecimal inválida: longitud esperada ${AES_KEY_BYTES * 2}, recibida ${clean.length}"
        }
        val result = ByteArray(AES_KEY_BYTES)
        for (i in 0 until AES_KEY_BYTES) {
            val byteStr = clean.substring(i * 2, i * 2 + 2)
            result[i] = byteStr.toInt(16).toByte()
        }
        return result
    }

    /**
     * Resultado del cifrado simétrico AES-256-GCM.
     */
    data class EncryptedPayload(
        val iv: ByteArray,
        val authTag: ByteArray,
        val ciphertext: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EncryptedPayload
            if (!iv.contentEquals(other.iv)) return false
            if (!authTag.contentEquals(other.authTag)) return false
            if (!ciphertext.contentEquals(other.ciphertext)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = iv.contentHashCode()
            result = 31 * result + authTag.contentHashCode()
            result = 31 * result + ciphertext.contentHashCode()
            return result
        }
    }

    /**
     * Cifra un objeto [MedicalRescueData] con AES-256-GCM.
     *
     * @param data Datos clínicos estructurados
     * @param key Clave simétrica de 256 bits (32 bytes)
     * @param explicitIv Nonce opcional de 12 bytes (genera aleatorio si es nulo)
     * @return [EncryptedPayload] con iv, authTag y ciphertext separados
     */
    fun encrypt(
        data: MedicalRescueData,
        key: ByteArray,
        explicitIv: ByteArray? = null
    ): EncryptedPayload {
        require(key.size == AES_KEY_BYTES) {
            "AES key debe tener 32 bytes (256 bits), recibido: ${key.size}"
        }

        val iv = if (explicitIv != null) {
            require(explicitIv.size == GCM_IV_BYTES) { "IV debe tener 12 bytes" }
            explicitIv.copyOf()
        } else {
            ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        }

        val jsonString = json.encodeToString(data)
        val plainBytes = jsonString.toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance(CIPHER_ALGO)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val fullCipher = cipher.doFinal(plainBytes)
        // Sanitizar el buffer intermedio de texto plano en memoria
        Arrays.fill(plainBytes, 0.toByte())

        // JCA AES/GCM concatena el tag de autenticación (16 bytes) al final del ciphertext
        val cipherLen = fullCipher.size - GCM_TAG_BYTES
        require(cipherLen > 0) { "Ciphertext generado inválido" }

        val ciphertext = ByteArray(cipherLen)
        val authTag = ByteArray(GCM_TAG_BYTES)

        System.arraycopy(fullCipher, 0, ciphertext, 0, cipherLen)
        System.arraycopy(fullCipher, cipherLen, authTag, 0, GCM_TAG_BYTES)

        // Limpiar buffer temporal
        Arrays.fill(fullCipher, 0.toByte())

        return EncryptedPayload(
            iv = iv,
            authTag = authTag,
            ciphertext = ciphertext
        )
    }

    /**
     * Descifra un payload clínico con AES-256-GCM.
     *
     * @throws javax.crypto.AEADBadTagException si la clave es incorrecta o los datos fueron alterados.
     */
    fun decrypt(
        iv: ByteArray,
        authTag: ByteArray,
        ciphertext: ByteArray,
        key: ByteArray
    ): MedicalRescueData {
        require(key.size == AES_KEY_BYTES) {
            "AES key debe tener 32 bytes (256 bits), recibido: ${key.size}"
        }
        require(iv.size == GCM_IV_BYTES) {
            "IV debe tener 12 bytes, recibido: ${iv.size}"
        }
        require(authTag.size == GCM_TAG_BYTES) {
            "AuthTag debe tener 16 bytes, recibido: ${authTag.size}"
        }
        require(ciphertext.isNotEmpty()) {
            "Ciphertext no puede estar vacío"
        }

        // Reensamblar fullCipher = ciphertext + authTag para JCA
        val fullCipher = ByteArray(ciphertext.size + authTag.size)
        System.arraycopy(ciphertext, 0, fullCipher, 0, ciphertext.size)
        System.arraycopy(authTag, 0, fullCipher, ciphertext.size, authTag.size)

        val cipher = Cipher.getInstance(CIPHER_ALGO)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val decryptedBytes = try {
            cipher.doFinal(fullCipher)
        } finally {
            // Limpiar memoria intermedia
            Arrays.fill(fullCipher, 0.toByte())
        }

        val jsonString = String(decryptedBytes, StandardCharsets.UTF_8)
        // Regla R12: Zeroing inmediato del buffer en memoria
        Arrays.fill(decryptedBytes, 0.toByte())

        return json.decodeFromString<MedicalRescueData>(jsonString)
    }

    /**
     * Sobrecarga conveniente para descifrar un [MedicalSheetPacket].
     */
    fun decryptPacket(packet: MedicalSheetPacket, key: ByteArray): MedicalRescueData {
        return decrypt(
            iv = packet.iv,
            authTag = packet.authTag,
            ciphertext = packet.ciphertext,
            key = key
        )
    }
}

