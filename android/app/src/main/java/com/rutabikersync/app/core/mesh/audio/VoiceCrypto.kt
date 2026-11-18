package com.rutabikersync.app.core.mesh.audio

import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado autenticado por frame de voz utilizando ChaCha20-Poly1305 (RFC 8439).
 * - Clave: 256 bits (32 bytes).
 * - Nonce: 96 bits (12 bytes) compuesto por [clubNetworkId (4B) | shortId (2B) | sequence (2B) | frameIndex (4B)]
 *   o aleatorio criptográfico para evitar reutilización de nonce.
 * - Tag de autenticación: 128 bits (16 bytes) garantizando integridad estricta.
 * - Cumplimiento R12: función de borrado de memoria [zeroBuffer] para sanitizar PCM y claves.
 */
object VoiceCrypto {

    const val KEY_SIZE_BYTES = 32
    const val NONCE_SIZE_BYTES = 12
    const val TAG_SIZE_BYTES = 16

    private val secureRandom = SecureRandom()

    private fun initCipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
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
                val ivSpec = IvParameterSpec(nonce)
                cipher.init(mode, keySpec, ivSpec)
                return cipher
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: java.security.NoSuchAlgorithmException("No provider found for ChaCha20-Poly1305")
    }

    fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE_BYTES)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /**
     * Construye un nonce determinista monotónico de 12 bytes:
     * [clubNetworkId: 4B][senderShortId: 2B][sequenceNum: 2B][frameIndex: 4B]
     * Garantiza unicidad absoluta por convoy, piloto y paquete sin colisión de nonce.
     */
    fun buildDeterministicNonce(
        clubNetworkId: Int,
        senderShortId: Int,
        sequenceNum: Int,
        frameIndex: Int
    ): ByteArray {
        val nonce = ByteArray(NONCE_SIZE_BYTES)
        nonce[0] = (clubNetworkId ushr 24).toByte()
        nonce[1] = (clubNetworkId ushr 16).toByte()
        nonce[2] = (clubNetworkId ushr 8).toByte()
        nonce[3] = clubNetworkId.toByte()

        nonce[4] = (senderShortId ushr 8).toByte()
        nonce[5] = senderShortId.toByte()

        nonce[6] = (sequenceNum ushr 8).toByte()
        nonce[7] = sequenceNum.toByte()

        nonce[8] = (frameIndex ushr 24).toByte()
        nonce[9] = (frameIndex ushr 16).toByte()
        nonce[10] = (frameIndex ushr 8).toByte()
        nonce[11] = frameIndex.toByte()
        return nonce
    }

    /**
     * Cifra un frame Opus plano con ChaCha20-Poly1305.
     * Retorna: ciphertext + auth_tag (16 bytes).
     */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = byteArrayOf()
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "La clave debe ser de 32 bytes (256 bits)" }
        require(nonce.size == NONCE_SIZE_BYTES) { "El nonce debe ser de 12 bytes (96 bits)" }

        val cipher = initCipher(Cipher.ENCRYPT_MODE, key, nonce)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(plaintext)
    }

    /**
     * Descifra un frame cifrado verificando autenticidad con Poly1305.
     * Arroja [AEADBadTagException] si el payload, nonce o tag han sido alterados.
     */
    @Throws(AEADBadTagException::class)
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = byteArrayOf()
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "La clave debe ser de 32 bytes (256 bits)" }
        require(nonce.size == NONCE_SIZE_BYTES) { "El nonce debe ser de 12 bytes (96 bits)" }
        require(ciphertextAndTag.size >= TAG_SIZE_BYTES) {
            "El paquete cifrado debe incluir al menos el auth_tag de 16 bytes"
        }

        val cipher = initCipher(Cipher.DECRYPT_MODE, key, nonce)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertextAndTag)
    }

    /**
     * Limpieza forense de memoria (R12 / Bóveda / PTT).
     */
    fun zeroBuffer(buffer: ByteArray) {
        Arrays.fill(buffer, 0.toByte())
    }

    fun zeroBuffer(buffer: ShortArray) {
        Arrays.fill(buffer, 0.toShort())
    }
}

