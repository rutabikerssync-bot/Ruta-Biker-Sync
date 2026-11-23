package com.rutabikersync.app.core.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1/T3: el ToneGenerator se libera al cerrar el hub. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConvoySignalAudioReleaseTest {

    class TrackingEmitter : ToneEmitter {
        var toneOnCount = 0
        var isReleased = false
        override fun toneOn(durationMs: Long): Boolean = toneOn(0, durationMs)
        override fun toneOn(toneType: Int, durationMs: Long): Boolean {
            toneOnCount++
            return true
        }
        override fun toneOff() = Unit
        override fun release() {
            isReleased = true
        }
    }

    class TrackingFocus : AudioFocusDelegate {
        private var held = false
        override val isFocusHeld: Boolean get() = held
        override fun requestFocus(): Boolean {
            held = true
            return true
        }
        override fun releaseFocus() {
            held = false
        }
    }

    @After
    fun tearDown() {
        ConvoySignalAudioHub.setCustomPlayerForTesting(null)
    }

    @Test
    fun `release detiene la reproduccion libera el emisor y el foco`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val emitter = TrackingEmitter()
        val focus = TrackingFocus()
        val player = DefaultConvoySignalPlayer(
            toneEmitter = emitter,
            focusDelegate = focus,
            coroutineScope = scope,
            dispatcher = dispatcher
        )

        player.playSignal(ConvoySignalKind.OBSTACLE)
        runCurrent()
        assertTrue(player.isPlaying)
        assertTrue(focus.isFocusHeld)

        player.release()

        assertFalse(player.isPlaying)
        assertTrue(emitter.isReleased)
        assertFalse(focus.isFocusHeld)

        val emittedBefore = emitter.toneOnCount
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(emittedBefore, emitter.toneOnCount)
    }

    @Test
    fun `el hub libera el reproductor y deja de estar inicializado`() {
        var released = false
        val custom = object : ConvoySignalAudioPlayer {
            override val isPlaying: Boolean get() = false
            override fun playSignal(kind: ConvoySignalKind) = Unit
            override fun playTone(toneType: Int, durationMs: Long) = Unit
            override fun playPattern(toneType: Int, pulseCount: Int, pulseDurationMs: Long, gapMs: Long) = Unit
            override fun stop() = Unit
            override fun release() {
                released = true
            }
        }

        ConvoySignalAudioHub.setCustomPlayerForTesting(custom)
        assertTrue(ConvoySignalAudioHub.isInitialized)

        ConvoySignalAudioHub.release()

        assertTrue(released)
        assertFalse(ConvoySignalAudioHub.isInitialized)

        // No hay contexto aún en el test: no se reproduce nada tras liberar.
        ConvoySignalAudioHub.playSignal(ConvoySignalKind.GAS_STOP)
        assertFalse(ConvoySignalAudioHub.isInitialized)
    }
}
