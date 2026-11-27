package com.rutabikersync.app.core.audio

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ciclo de liberación del hub de señales en JVM pura (sin Robolectric): se inyecta un
 * `Context` falso para comprobar que `release()` libera al dueño del ToneGenerator y que
 * la siguiente señal reconstruye el hub desde el contexto de aplicación recordado.
 */
class ConvoySignalAudioReleaseTest {

    private class FakeAppContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private class FakePlayer : ConvoySignalAudioPlayer {
        var releaseCalls = 0
        var stopCalls = 0
        override val isPlaying: Boolean get() = false
        override fun playSignal(kind: ConvoySignalKind) = Unit
        override fun playTone(toneType: Int, durationMs: Long) = Unit
        override fun playPattern(toneType: Int, pulseCount: Int, pulseDurationMs: Long, gapMs: Long) = Unit
        override fun stop() {
            stopCalls++
        }
        override fun release() {
            releaseCalls++
            stop()
        }
    }

    @After
    fun tearDown() {
        ConvoySignalAudioHub.release()
        ConvoySignalAudioHub.setCustomPlayerForTesting(null)
        ConvoySignalAudioHub.setAppContextForTesting(null)
        ConvoySignalAudioHub.pttStateProvider = PttStateProvider { false }
    }

    @Test
    fun `release libera el player y deja el hub sin inicializar`() {
        val fake = FakePlayer()
        ConvoySignalAudioHub.setCustomPlayerForTesting(fake)
        assertTrue(ConvoySignalAudioHub.isInitialized)

        ConvoySignalAudioHub.release()

        assertEquals(1, fake.releaseCalls)
        assertEquals(1, fake.stopCalls)
        assertFalse(ConvoySignalAudioHub.isInitialized)

        // Sin contexto de aplicación inyectado, reproducir no lanza ni recrea el hub.
        ConvoySignalAudioHub.playSignal(ConvoySignalKind.OBSTACLE)
        assertFalse(ConvoySignalAudioHub.isInitialized)
    }

    @Test
    fun `playSignal reconstruye el hub desde appContext despues de release`() {
        val fake = FakePlayer()
        ConvoySignalAudioHub.setAppContextForTesting(FakeAppContext())
        ConvoySignalAudioHub.setCustomPlayerForTesting(fake)

        ConvoySignalAudioHub.release()
        assertEquals(1, fake.releaseCalls)
        assertFalse(ConvoySignalAudioHub.isInitialized)

        ConvoySignalAudioHub.playSignal(ConvoySignalKind.OBSTACLE)

        assertTrue(ConvoySignalAudioHub.isInitialized)
    }
}
