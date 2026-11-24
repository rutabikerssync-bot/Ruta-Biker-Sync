package com.rutabikersync.app.core.mesh.sos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.Base64

/**
 * Regresion del bug "Caller-provided IV not permitted" al publicar la identidad SOS.
 *
 * [Ed25519SosCrypto.persistKeyPair] debe dejar que AndroidKeyStore genere el IV
 * del cifrado AES-256-GCM de la KEK; el IV resultante se persiste y se reutiliza
 * solo en el descifrado. Este test ejercita el camino real de dispositivo, no el
 * modo JVM (`context == null`), que no pasa por Keystore.
 */
@RunWith(AndroidJUnit4::class)
class Ed25519SosCryptoKeystoreInstrumentedTest {

    @Before
    fun limpiarEstadoPrevio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("rbs_sos_crypto_prefs", 0).edit().clear().commit()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("rbs_sos_kek_v1")
    }

    @Test
    fun keystoreGeneraIvYPersisteParQuePuedeRecargarseYFirmar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val crypto = Ed25519SosCrypto(context = context)

        val pair = crypto.getOrCreateLocalKeyPair()
        val prefs = context.getSharedPreferences("rbs_sos_crypto_prefs", 0)
        val ivB64 = prefs.getString("sos_key_iv_v1", null)
        assertTrue("El IV generado por Keystore debe persistirse", ivB64 != null)
        assertEquals(12, Base64.getDecoder().decode(ivB64).size)

        val payload = "sos-keystore-iv-regression".toByteArray()
        val firma = crypto.sign(payload, pair.private)
        assertTrue(crypto.verify(payload, firma, pair.public))

        // Una instancia nueva no tiene volatileKeyPair: debe cargar el par cifrado
        // desde SharedPreferences + AndroidKeyStore y seguir produciendo la misma firma.
        val reloaded = Ed25519SosCrypto(context = context).getOrCreateLocalKeyPair()
        assertArrayEquals(
            Ed25519SosCrypto.exportRawPublicKey(pair.public),
            Ed25519SosCrypto.exportRawPublicKey(reloaded.public)
        )
        assertTrue(
            Ed25519SosCrypto(context = context).verify(payload, firma, reloaded.public)
        )
    }
}
