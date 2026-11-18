package com.rutabikersync.app.core.mesh.text

import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.audio.VoiceCrypto
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Motor de Cifrado Autenticado para Mensajería de Texto Off-Grid (Fase 6 · Hito 5 · Etapa 1).
 *
 * Utiliza ChaCha20-Poly1305 (RFC 8439):
 * - Clave simétrica de rodada ([key]): 256 bits (32 bytes), compartida en el manifiesto firmado.
 * - Nonce/IV: 96 bits (12 bytes) aleatorio y único generado por [SecureRandom].
 * - Tag de integridad Poly1305: 128 bits (16 bytes).
 * - Cumplimiento R12: Zeroing explícito de buffers en memoria volátil.
 * - Sin persistencia de la clave en disco.
 */
object TextMessageCrypto {

    const val KEY_SIZE_BYTES = 32
    const val IV_SIZE_BYTES = 12
    const val TAG_SIZE_BYTES = 16

    private val secureRandom = SecureRandom()

    private fun initCipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        val candidates = listOf(
            "ChaCha20/Poly1305/NoPadding" to "ChaCha20",
            "ChaCha20-Poly1305/None/NoPadding" to "ChaCha20-Poly1305",
            "ChaCha20-Poly1305" to "ChaCha20-Poly1305",
            "ChaCha20/Poly1305/NoPadding" to "ChaCha20-Poly1305"
        )
        var lastException: Exception? = null
        for ((trans, algo) in candidates) {
            try {
                val cipher = Cipher.getInstance(trans)
                val keySpec = SecretKeySpec(key, algo)
                val ivSpec = IvParameterSpec(iv)
                cipher.init(mode, keySpec, ivSpec)
                return cipher
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: java.security.NoSuchAlgorithmException("No provider found for ChaCha20-Poly1305")
    }

    /**
     * Genera un vector de inicialización (nonce) de 12 bytes criptográficamente seguro.
     */
    fun generateIv(): ByteArray {
        val iv = ByteArray(IV_SIZE_BYTES)
        secureRandom.nextBytes(iv)
        return iv
    }

    /**
     * Cifra un mensaje de texto plano UTF-8 y construye un [TextMessagePacket] listo para emitir a la malla.
     *
     * @param key Clave simétrica de rodada de 32 bytes (256 bits)
     * @param plaintext Texto plano a cifrar (máximo 120 bytes UTF-8)
     * @param header Cabecera [MeshHeader] (debe tener [MeshHeader.TYPE_TEXT_MESSAGE])
     * @param targetShortId Destinatario (0xFFFF para broadcast a todo el convoy o shortId específico)
     * @param customIv Nonce opcional (por defecto genera uno seguro de 12 bytes)
     * @param hopCount Contador inicial de saltos (por defecto 0)
     */
    fun encrypt(
        key: ByteArray,
        plaintext: String,
        header: MeshHeader,
        targetShortId: UShort = TextMessagePacket.BROADCAST_TARGET,
        customIv: ByteArray? = null,
        hopCount: Byte = 0
    ): TextMessagePacket {
        require(key.size == KEY_SIZE_BYTES) { "La clave debe ser de $KEY_SIZE_BYTES bytes (256 bits)" }
        require(plaintext.isNotEmpty()) { "El mensaje de texto no puede estar vacío" }

        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        require(plaintextBytes.size <= TextMessagePacket.MAX_PLAINTEXT_BYTES) {
            "El mensaje supera el límite máximo de ${TextMessagePacket.MAX_PLAINTEXT_BYTES} bytes UTF-8 (tamaño: ${plaintextBytes.size})"
        }

        val iv = customIv ?: generateIv()
        require(iv.size == IV_SIZE_BYTES) { "iv debe tener $IV_SIZE_BYTES bytes" }

        val cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv)
        val encryptedWithTag = cipher.doFinal(plaintextBytes)

        // Limpiar buffer temporal de texto plano
        Arrays.fill(plaintextBytes, 0.toByte())

        // ChaCha20-Poly1305 genera ciphertext (N bytes) + tag (16 bytes)
        val ciphertextSize = encryptedWithTag.size - TAG_SIZE_BYTES
        val ciphertext = ByteArray(ciphertextSize)
        val authTag = ByteArray(TAG_SIZE_BYTES)

        System.arraycopy(encryptedWithTag, 0, ciphertext, 0, ciphertextSize)
        System.arraycopy(encryptedWithTag, ciphertextSize, authTag, 0, TAG_SIZE_BYTES)

        // Limpiar buffer concatenado
        Arrays.fill(encryptedWithTag, 0.toByte())

        return TextMessagePacket(
            header = header,
            targetShortId = targetShortId,
            messageLength = ciphertextSize.toUByte(),
            iv = iv,
            authTag = authTag,
            ciphertext = ciphertext,
            hopCount = hopCount
        )
    }

    /**
     * Descifra el contenido de un [TextMessagePacket] utilizando ChaCha20-Poly1305.
     *
     * @param key Clave simétrica de rodada de 32 bytes (256 bits)
     * @param packet Paquete recibido por la malla
     * @return Texto plano descifrado en UTF-8
     * @throws AEADBadTagException Si el ciphertext, authTag, iv o clave son incorrectos o fueron manipulados
     */
    @Throws(AEADBadTagException::class)
    fun decrypt(key: ByteArray, packet: TextMessagePacket): String {
        require(key.size == KEY_SIZE_BYTES) { "La clave debe ser de $KEY_SIZE_BYTES bytes (256 bits)" }

        // Reconstruir buffer concatenado para el Cipher: ciphertext + authTag
        val encryptedWithTag = ByteArray(packet.ciphertext.size + packet.authTag.size)
        System.arraycopy(packet.ciphertext, 0, encryptedWithTag, 0, packet.ciphertext.size)
        System.arraycopy(packet.authTag, 0, encryptedWithTag, packet.ciphertext.size, packet.authTag.size)

        val cipher = initCipher(Cipher.DECRYPT_MODE, key, packet.iv)
        val decryptedBytes = cipher.doFinal(encryptedWithTag)

        // Limpiar buffer intermedio cifrado
        Arrays.fill(encryptedWithTag, 0.toByte())

        val resultText = String(decryptedBytes, Charsets.UTF_8)

        // Limpiar buffer de texto plano en memoria volátil
        Arrays.fill(decryptedBytes, 0.toByte())

        return resultText
    }

    /**
     * Limpieza forense de memoria (Regla R12).
     */
    fun zeroBuffer(buffer: ByteArray) {
        Arrays.fill(buffer, 0.toByte())
    }
}
