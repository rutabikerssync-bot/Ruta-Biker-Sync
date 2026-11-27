package com.rutabikersync.app.core.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica el seam del arranque de audio (P0-A1, FASE 2). El proyecto no usa Robolectric:
 * `App.onCreate()` delega en [AppAudioBootstrap] y este test comprueba ese contrato sin
 * instanciar un `Application` de Android.
 */
class AppAudioBootstrapTest {

    private fun dummyPlayer() = object : ConvoySignalAudioPlayer {
        var playSignalCalls = 0
        override val isPlaying: Boolean get() = false
        override fun playSignal(kind: ConvoySignalKind) {
            playSignalCalls++
        }
        override fun playTone(toneType: Int, durationMs: Long) = Unit
        override fun playPattern(toneType: Int, pulseCount: Int, pulseDurationMs: Long, gapMs: Long) = Unit
        override fun stop() = Unit
    }

    @After
    fun tearDown() {
        ConvoySignalAudioHub.release()
        ConvoySignalAudioHub.setCustomPlayerForTesting(null)
        ConvoySignalAudioHub.setAppContextForTesting(null)
        ConvoySignalAudioHub.pttStateProvider = PttStateProvider { false }
    }

    @Test
    fun `el arranque instala SOS y convoy exactamente una vez cada uno`() {
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
    fun `idempotencia`() {
        var sosInstalls = 0
        var convoyInstalls = 0

        fun installSosOnce() {
            if (sosInstalls == 0) sosInstalls++
        }
        fun installConvoyOnce() {
            if (ConvoySignalAudioHub.isInitialized) return
            convoyInstalls++
            ConvoySignalAudioHub.setCustomPlayerForTesting(dummyPlayer())
        }

        repeat(3) {
            AppAudioBootstrap.initialize(
                installSosHub = ::installSosOnce,
                installConvoyHub = ::installConvoyOnce
            )
        }

        // Los instaladores reales (ensureInitialized) son idempotentes: no hay hubs duplicados.
        assertEquals(1, sosInstalls)
        assertEquals(1, convoyInstalls)
        assertTrue(ConvoySignalAudioHub.isInitialized)
    }

    @Test
    fun `el hub informa isInitialized y la cabina no lo reinicia`() {
        assertFalse(ConvoySignalAudioHub.isInitialized)

        val dummy = dummyPlayer()
        AppAudioBootstrap.initialize(
            installSosHub = {},
            installConvoyHub = { ConvoySignalAudioHub.setCustomPlayerForTesting(dummy) }
        )
        assertTrue(ConvoySignalAudioHub.isInitialized)

        // La cabina solo publica su PTT local; no vuelve a crear el hub.
        ConvoySignalAudioHub.pttStateProvider = PttStateProvider { true }
        assertTrue(ConvoySignalAudioHub.isInitialized)
        assertTrue(ConvoySignalAudioHub.pttStateProvider.isPttActive())

        ConvoySignalAudioHub.playSignal(ConvoySignalKind.REGROUP)
        assertEquals(1, dummy.playSignalCalls)
        assertTrue(ConvoySignalAudioHub.isInitialized)
    }
}
