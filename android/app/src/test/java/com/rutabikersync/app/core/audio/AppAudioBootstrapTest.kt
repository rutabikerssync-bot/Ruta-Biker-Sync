package com.rutabikersync.app.core.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica el seam del arranque de audio (P1/T1). El proyecto no usa Robolectric:
 * `App.onCreate()` delega en [AppAudioBootstrap], y este test comprueba ese contrato
 * sin instanciar un `Application` de Android. La inicialización real se confirma
 * además en hardware con el logcat de arranque.
 */
class AppAudioBootstrapTest {

    private fun dummyPlayer() = object : ConvoySignalAudioPlayer {
        override val isPlaying: Boolean get() = false
        override fun playSignal(kind: ConvoySignalKind) = Unit
        override fun playTone(toneType: Int, durationMs: Long) = Unit
        override fun playPattern(toneType: Int, pulseCount: Int, pulseDurationMs: Long, gapMs: Long) = Unit
        override fun stop() = Unit
    }

    @After
    fun tearDown() {
        ConvoySignalAudioHub.setCustomPlayerForTesting(null)
        ConvoySignalAudioHub.pttStateProvider = PttStateProvider { false }
    }

    @Test
    fun `el arranque instala el hub de SOS y el de convoy exactamente una vez cada uno`() {
        var sosInstalls = 0
        var convoyInstalls = 0

        AppAudioBootstrap.initialize(
            installSosHub = { sosInstalls++ },
            installConvoyHub = { convoyInstalls++ }
        )

        assertEquals(1, sosInstalls)
        assertEquals(1, convoyInstalls)
    }

    @Test
    fun `el hub de convoy informa que esta inicializado y la cabina no lo reinicia`() {
        assertFalse(ConvoySignalAudioHub.isInitialized)

        AppAudioBootstrap.initialize(
            installSosHub = {},
            installConvoyHub = { ConvoySignalAudioHub.setCustomPlayerForTesting(dummyPlayer()) }
        )

        assertTrue(ConvoySignalAudioHub.isInitialized)

        // La cabina solo publica su PTT local; no vuelve a crear el hub.
        ConvoySignalAudioHub.pttStateProvider = PttStateProvider { true }
        assertTrue(ConvoySignalAudioHub.isInitialized)
        assertTrue(ConvoySignalAudioHub.pttStateProvider.isPttActive())
    }
}
