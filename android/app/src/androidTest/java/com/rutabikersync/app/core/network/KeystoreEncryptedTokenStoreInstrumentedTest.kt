package com.rutabikersync.app.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba instrumentada en dispositivo Android real (p. ej. Redmi C2 / Redmi Note 13 Pro)
 * para verificar el cifrado AES-256-GCM respaldado por el proveedor de hardware `AndroidKeyStore`.
 *
 * Estado actual en CHECKLIST.md: NO VERIFICADO EN DISPOSITIVO hasta ejecutar `./gradlew connectedDebugAndroidTest`
 * con los equipos conectados por USB.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreEncryptedTokenStoreInstrumentedTest {

    @Test
    fun hardwareAndroidKeystoreEncryptsAndDecryptsRefreshToken() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreEncryptedTokenStore(context = context)
        assertTrue(store.isUsingHardwareAndroidKeystore)

        val token = "eyJraWQiOiJrMSIsImtpbmQiOiJyZWZyZXNoIn0.hardware_keystore_test"
        store.saveRefreshToken(token)

        val rawCipher = store.rawStoredCiphertextForAudit()
        assertNotNull(rawCipher)
        assertFalse(rawCipher!!.contains("hardware_keystore_test"))
        assertEquals(token, store.loadRefreshToken())

        store.clear()
        assertNull(store.loadRefreshToken())
    }
}
