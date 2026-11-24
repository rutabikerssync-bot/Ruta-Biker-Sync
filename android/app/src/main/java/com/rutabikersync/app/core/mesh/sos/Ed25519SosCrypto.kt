package com.rutabikersync.app.core.mesh.sos

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Gestor criptográfico asimétrico Ed25519 para tramas de emergencia SOS y ACK (Fase 6 · Hito 4).
 *
 * Modelo de Seguridad:
 * 1. Firma Asimétrica Ed25519 (RFC 8032):
 *    - Firma de 64 bytes generada sobre el payload canónico inmutable (14 bytes en SOS, 10 bytes en ACK).
 *    - Inmune a manipulación de coordenadas, tipo de incidente o grupo sanguíneo.
 * 2. Protección de Clave Privada (AndroidKeyStore Envelope):
 *    - En dispositivo Android real (context != null):
 *      Intenta usar par de claves nativo en AndroidKeyStore si el hardware lo soporta (API 33+).
 *      Fallback de resiliencia: genera par Ed25519 puro y cifra la clave privada PKCS#8 con una
 *      Key-Encrypting-Key (KEK) AES-256-GCM alojada en AndroidKeyStore (`rbs_sos_kek_v1`).
 *      La clave privada NUNCA se persiste en texto plano en disco ni en logs.
 *    - En entorno JVM / Tests unitarios (context == null):
 *      Genera par de claves Ed25519 en memoria volátil sin dependencias de Android.
 */
class Ed25519SosCrypto(
    private val context: Context? = null
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SOS_KEY_ALIAS = "rbs_sos_ed25519_key_v1"
        private const val KEK_ALIAS = "rbs_sos_kek_v1"
        private const val PREFS_NAME = "rbs_sos_crypto_prefs"
        private const val KEY_WRAPPED_PRIVATE_KEY = "wrapped_sos_priv_key_v1"
        private const val KEY_PUBLIC_KEY_SPKI = "sos_pub_key_spki_v1"
        private const val KEY_IV = "sos_key_iv_v1"

        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        /** Tamaño de la clave pública Ed25519 cruda (sin cabecera SPKI). */
        const val RAW_PUBLIC_KEY_SIZE_BYTES = 32

        /**
         * Cabecera SPKI DER estándar de 12 bytes para clave pública Ed25519 (OID 1.3.101.112).
         * 30 2a 30 05 06 03 2b 65 70 03 21 00 + [32 bytes raw key]
         */
        val ED25519_SPKI_HEADER = byteArrayOf(
            0x30.toByte(), 0x2a.toByte(), 0x30.toByte(), 0x05.toByte(),
            0x06.toByte(), 0x03.toByte(), 0x2b.toByte(), 0x65.toByte(),
            0x70.toByte(), 0x03.toByte(), 0x21.toByte(), 0x00.toByte()
        )

        /**
         * Importa una clave pública Ed25519 a partir de sus 32 bytes crudos.
         */
        fun importRawPublicKey(raw32: ByteArray): PublicKey {
            require(raw32.size == RAW_PUBLIC_KEY_SIZE_BYTES) { "Clave pública cruda Ed25519 debe medir exactamente 32 bytes" }
            val spki = ED25519_SPKI_HEADER + raw32
            return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        }

        /**
         * Extrae los 32 bytes crudos de una clave pública Ed25519 codificada en SPKI.
         */
        fun exportRawPublicKey(publicKey: PublicKey): ByteArray {
            val encoded = publicKey.encoded
            return if (encoded.size == 44 && startsWithSpkiHeader(encoded)) {
                encoded.copyOfRange(12, 44)
            } else {
                encoded
            }
        }

        /**
         * Importa una clave pública Ed25519 codificada en Base64 (formato SPKI DER).
         */
        fun importPublicKeySpkiBase64(b64: String): PublicKey {
            val der = Base64.getDecoder().decode(b64)
            return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
        }

        /**
         * Exporta una clave pública Ed25519 a Base64 (formato SPKI DER).
         */
        fun exportPublicKeySpkiBase64(publicKey: PublicKey): String =
            Base64.getEncoder().encodeToString(publicKey.encoded)

        private fun startsWithSpkiHeader(bytes: ByteArray): Boolean {
            if (bytes.size < ED25519_SPKI_HEADER.size) return false
            for (i in ED25519_SPKI_HEADER.indices) {
                if (bytes[i] != ED25519_SPKI_HEADER[i]) return false
            }
            return true
        }
    }

    @Volatile
    private var volatileKeyPair: KeyPair? = null

    /**
     * Obtiene el par de claves local del nodo o genera uno nuevo si no existe.
     */
    @Synchronized
    fun getOrCreateLocalKeyPair(): KeyPair {
        volatileKeyPair?.let { return it }

        if (context == null) {
            // Entorno JVM / Unit Tests
            val kpg = KeyPairGenerator.getInstance("Ed25519")
            val pair = kpg.generateKeyPair()
            volatileKeyPair = pair
            return pair
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wrappedPrivB64 = prefs.getString(KEY_WRAPPED_PRIVATE_KEY, null)
        val pubB64 = prefs.getString(KEY_PUBLIC_KEY_SPKI, null)
        val ivB64 = prefs.getString(KEY_IV, null)

        if (wrappedPrivB64 != null && pubB64 != null && ivB64 != null) {
            try {
                val pubKey = importPublicKeySpkiBase64(pubB64)
                val privKey = unwrapPrivateKey(wrappedPrivB64, ivB64)
                val pair = KeyPair(pubKey, privKey)
                volatileKeyPair = pair
                return pair
            } catch (_: Exception) {
                // Si la KEK se invalidó o corrompió, regenera un nuevo par
            }
        }

        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val pair = kpg.generateKeyPair()
        persistKeyPair(prefs, pair)
        volatileKeyPair = pair
        return pair
    }

    /**
     * Firma un arreglo arbitrario de bytes con la clave privada local Ed25519.
     * Retorna exactamente 64 bytes de firma.
     */
    fun sign(data: ByteArray, privateKey: PrivateKey = getOrCreateLocalKeyPair().private): ByteArray {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(data)
        val sig = signer.sign()
        require(sig.size == SosPacket.SIGNATURE_SIZE_BYTES) {
            "Firma Ed25519 inválida: se esperaban ${SosPacket.SIGNATURE_SIZE_BYTES} bytes, obtenido: ${sig.size}"
        }
        return sig
    }

    /**
     * Verifica la validez de una firma Ed25519 de 64 bytes contra los datos canónicos.
     */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        if (signature.size != SosPacket.SIGNATURE_SIZE_BYTES) return false
        return try {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(signature)
        } catch (_: SignatureException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Firma un [SosPacket] calculando la firma Ed25519 sobre sus 14 bytes canónicos (Opción B).
     * Retorna una copia inmutable del paquete con la firma de 64 bytes inyectada.
     */
    fun signSos(packet: SosPacket, privateKey: PrivateKey = getOrCreateLocalKeyPair().private): SosPacket {
        val signature = sign(packet.getSignableData(), privateKey)
        return packet.copy(signature = signature)
    }

    /**
     * Verifica la autenticidad e integridad de un [SosPacket] utilizando la clave pública del emisor.
     */
    fun verifySos(packet: SosPacket, publicKey: PublicKey): Boolean {
        return verify(packet.getSignableData(), packet.signature, publicKey)
    }

    /**
     * Firma un [SosAckPacket] calculando la firma Ed25519 sobre sus 10 bytes canónicos.
     * Retorna una copia inmutable del paquete con la firma de 64 bytes inyectada.
     */
    fun signSosAck(ack: SosAckPacket, privateKey: PrivateKey = getOrCreateLocalKeyPair().private): SosAckPacket {
        val signature = sign(ack.getSignableData(), privateKey)
        return ack.copy(signature = signature)
    }

    /**
     * Verifica la autenticidad e integridad de un [SosAckPacket] utilizando la clave pública del emisor del ACK.
     */
    fun verifySosAck(ack: SosAckPacket, publicKey: PublicKey): Boolean {
        return verify(ack.getSignableData(), ack.signature, publicKey)
    }

    // --- Persistencia cifrada en AndroidKeyStore Envelope ---

    private fun getOrCreateKek(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEK_ALIAS)) {
            val entry = keyStore.getEntry(KEK_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun persistKeyPair(prefs: SharedPreferences, pair: KeyPair) {
        val kek = getOrCreateKek()

        // AndroidKeyStore exige generar el IV durante init() cuando la KEK usa
        // cifrado aleatorio (randomizedEncryptionRequired = true, valor por defecto).
        // Pasar un GCMParameterSpec aqui lanza InvalidAlgorithmParameterException
        // ("Caller-provided IV not permitted") y mata la app al publicar la clave SOS.
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES) {
            "IV generado por AndroidKeyStore inesperado: ${iv.size} bytes"
        }
        val wrappedPriv = cipher.doFinal(pair.private.encoded)

        prefs.edit()
            .putString(KEY_WRAPPED_PRIVATE_KEY, Base64.getEncoder().encodeToString(wrappedPriv))
            .putString(KEY_PUBLIC_KEY_SPKI, exportPublicKeySpkiBase64(pair.public))
            .putString(KEY_IV, Base64.getEncoder().encodeToString(iv))
            .commit()
    }

    private fun unwrapPrivateKey(wrappedPrivB64: String, ivB64: String): PrivateKey {
        val kek = getOrCreateKek()
        val iv = Base64.getDecoder().decode(ivB64)
        val wrappedPriv = Base64.getDecoder().decode(wrappedPrivB64)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        val privBytes = cipher.doFinal(wrappedPriv)

        return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privBytes))
    }
}

