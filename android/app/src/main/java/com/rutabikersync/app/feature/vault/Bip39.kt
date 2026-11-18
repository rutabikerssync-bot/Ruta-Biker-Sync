package com.rutabikersync.app.feature.vault

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generador y validador de frases de recuperación mnemónicas de 12 palabras según BIP-0039.
 * Proporciona 128 bits de entropía criptográfica real protegida por suma de comprobación (checksum de 4 bits).
 */
object Bip39 {

    private const val ENTROPY_BYTES = 16 // 128 bits
    private const val WORDS_COUNT = 12
    private const val BITS_PER_WORD = 11
    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_LENGTH_BITS = 256

    /**
     * Genera una nueva mnemónica de 12 palabras con 128 bits de entropía aleatoria segura.
     */
    fun generateMnemonic(random: SecureRandom = SecureRandom()): List<String> {
        val entropy = ByteArray(ENTROPY_BYTES)
        random.nextBytes(entropy)
        return entropyToMnemonic(entropy)
    }

    /**
     * Convierte 16 bytes de entropía en 12 palabras BIP39 con checksum.
     */
    fun entropyToMnemonic(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "La entropía para 12 palabras debe ser de exactamente 16 bytes (128 bits)" }

        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        // Checksum: primeros 4 bits del hash SHA-256
        val checksumByte = hash[0]

        // Construir arreglo de booleanos con 132 bits (128 bits de entropía + 4 bits de checksum)
        val bits = BooleanArray(132)
        for (i in 0 until 128) {
            val byteIndex = i / 8
            val bitIndex = 7 - (i % 8)
            bits[i] = ((entropy[byteIndex].toInt() ushr bitIndex) and 1) == 1
        }
        for (i in 0 until 4) {
            val bitIndex = 7 - i
            bits[128 + i] = ((checksumByte.toInt() ushr bitIndex) and 1) == 1
        }

        // Extraer 12 bloques de 11 bits
        val words = mutableListOf<String>()
        for (i in 0 until WORDS_COUNT) {
            var index = 0
            for (j in 0 until BITS_PER_WORD) {
                index = index shl 1
                if (bits[i * BITS_PER_WORD + j]) {
                    index = index or 1
                }
            }
            words.add(Bip39WordList.WORDS[index])
        }

        return words
    }

    /**
     * Valida que una lista de palabras sea una mnemónica BIP-0039 válida de 12 palabras con checksum correcto.
     */
    fun validateMnemonic(words: List<String>): Boolean {
        if (words.size != WORDS_COUNT) return false

        val bits = BooleanArray(132)
        for (i in 0 until WORDS_COUNT) {
            val word = words[i].lowercase().trim()
            val wordIndex = Bip39WordList.indexOf(word)
            if (wordIndex < 0) return false

            for (j in 0 until BITS_PER_WORD) {
                val bitVal = ((wordIndex ushr (10 - j)) and 1) == 1
                bits[i * BITS_PER_WORD + j] = bitVal
            }
        }

        // Reconstruir los 16 bytes de entropía
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until 128) {
            if (bits[i]) {
                val byteIndex = i / 8
                val bitIndex = 7 - (i % 8)
                entropy[byteIndex] = (entropy[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
        }

        // Calcular checksum esperado
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksumByte = hash[0]

        // Verificar los 4 bits de checksum
        for (i in 0 until 4) {
            val bitIndex = 7 - i
            val expectedBit = ((expectedChecksumByte.toInt() ushr bitIndex) and 1) == 1
            if (bits[128 + i] != expectedBit) {
                return false
            }
        }

        return true
    }

    /**
     * Deriva una clave simétrica AES-256 (K_recov) a partir de la frase mnemónica y un salt de 16 bytes
     * utilizando PBKDF2-HMAC-SHA256 con 100,000 iteraciones (requisito normativo D69).
     */
    fun deriveRecoveryKey(mnemonicWords: List<String>, salt: ByteArray): SecretKey {
        require(validateMnemonic(mnemonicWords)) { "Frase de recuperación BIP-0039 inválida o checksum corrupto" }
        require(salt.size >= 16) { "El salt para PBKDF2 debe ser de al menos 16 bytes" }

        val normalizedPhrase = mnemonicWords.joinToString(" ") { it.lowercase().trim() }
        val spec = PBEKeySpec(
            normalizedPhrase.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            PBKDF2_KEY_LENGTH_BITS
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}

