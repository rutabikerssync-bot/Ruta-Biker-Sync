package com.rutabikersync.app.core.mesh.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

class VoiceCryptoTest {

    private val testKey = ByteArray(32) { it.toByte() }
    private val testNonce = VoiceCrypto.buildDeterministicNonce(
        clubNetworkId = 0x52415301,
        senderShortId = 2,
        sequenceNum = 42,
        frameIndex = 7
    )

    @Test
    fun testChaCha20RoundTrip() {
        val plaintextOpus = "OPUS_VOICE_PAYLOAD_TEST_DATA_FRAME_20MS".toByteArray(Charsets.UTF_8)
        val encryptedWithTag = VoiceCrypto.encrypt(testKey, testNonce, plaintextOpus)

        // El payload cifrado debe medir exactamente plaintext + 16 bytes de tag
        assertEquals(plaintextOpus.size + VoiceCrypto.TAG_SIZE_BYTES, encryptedWithTag.size)

        val decrypted = VoiceCrypto.decrypt(testKey, testNonce, encryptedWithTag)
        assertArrayEquals("El texto descifrado debe ser idéntico al original", plaintextOpus, decrypted)
    }

    @Test
    fun testChaCha20TamperDetection() {
        val plaintext = "SECRET_AUDIO_PACKET".toByteArray(Charsets.UTF_8)
        val encrypted = VoiceCrypto.encrypt(testKey, testNonce, plaintext)

        // Alterar 1 bit en el ciphertext
        val tamperedCiphertext = encrypted.clone()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()

        try {
            VoiceCrypto.decrypt(testKey, testNonce, tamperedCiphertext)
            fail("Debe lanzar AEADBadTagException al alterar el ciphertext")
        } catch (e: AEADBadTagException) {
            // Éxito: integridad verificada
        }

        // Alterar 1 bit en el tag
        val tamperedTag = encrypted.clone()
        tamperedTag[tamperedTag.size - 1] = (tamperedTag[tamperedTag.size - 1].toInt() xor 0x01).toByte()

        try {
            VoiceCrypto.decrypt(testKey, testNonce, tamperedTag)
            fail("Debe lanzar AEADBadTagException al alterar el auth_tag")
        } catch (e: AEADBadTagException) {
            // Éxito: integridad verificada
        }
    }

    @Test
    fun testZeroBufferCompliance() {
        val pcm = ShortArray(100) { 12345 }
        VoiceCrypto.zeroBuffer(pcm)
        assertTrue("El buffer PCM debe estar completamente a 0", pcm.all { it == 0.toShort() })

        val key = ByteArray(32) { 0xFF.toByte() }
        VoiceCrypto.zeroBuffer(key)
        assertTrue("El buffer de clave debe estar completamente a 0", key.all { it == 0.toByte() })
    }
}

