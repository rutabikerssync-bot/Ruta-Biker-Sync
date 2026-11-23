package com.rutabikersync.app.core.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConvoySignalAudioPlayerTest {

    sealed class ToneEvent {
        data class ToneOn(val toneType: Int, val durationMs: Long) : ToneEvent()
        object ToneOff : ToneEvent()
    }

    class TestToneEmitter : ToneEmitter {
        val events = mutableListOf<ToneEvent>()
        var isToneActive = false
        var isReleased = false
        var canStartTone = true

        override fun toneOn(durationMs: Long): Boolean = toneOn(0, durationMs)

        override fun toneOn(toneType: Int, durationMs: Long): Boolean {
            events.add(ToneEvent.ToneOn(toneType, durationMs))
            if (!canStartTone) return false
            isToneActive = true
            return true
        }

        override fun toneOff() {
            if (isToneActive) {
                isToneActive = false
                events.add(ToneEvent.ToneOff)
            }
        }

        override fun release() {
            isToneActive = false
            isReleased = true
        }
    }

    class TestAudioFocusDelegate(var canGrantFocus: Boolean = true) : AudioFocusDelegate {
        var focusGrantedCount = 0
        var focusReleasedCount = 0
        private var _isFocusHeld = false
        override val isFocusHeld: Boolean get() = _isFocusHeld

        override fun requestFocus(): Boolean {
            if (canGrantFocus) {
                _isFocusHeld = true
                focusGrantedCount++
                return true
            }
            return false
        }

        override fun releaseFocus() {
            if (_isFocusHeld) {
                _isFocusHeld = false
                focusReleasedCount++
            }
        }
    }

    @Test
    fun testAllSignalsHaveDistinctAndValidPatterns() {
        val allSignals = ConvoySignalKind.entries
        assertEquals(5, allSignals.size)

        val codes = allSignals.map { it.code }.toSet()
        assertEquals("Cada señal debe poseer un código numérico único", 5, codes.size)

        for (signal in allSignals) {
            assertTrue("El título no debe ser vacío", signal.title.isNotBlank())
            assertTrue("El subtítulo no debe ser vacío", signal.subtitle.isNotBlank())
            assertTrue("El ícono no debe ser vacío", signal.icon.isNotBlank())
            assertTrue("Debe tener al menos 1 pulso", signal.pulseCount >= 1)
            assertTrue("La duración debe ser positiva", signal.pulseDurationMs > 0)
            assertTrue("El gap debe ser no negativo", signal.gapMs >= 0)

            val recovered = ConvoySignalKind.fromCode(signal.code)
            assertEquals(signal, recovered)
            assertTrue(signal.fullLabel.contains(signal.title))
            assertTrue(signal.fullLabel.contains(signal.icon))
        }
    }

    @Test
    fun testPlaySignalSinglePulseEmitsAndReleasesFocus() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val emitter = TestToneEmitter()
        val focus = TestAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultConvoySignalPlayer(
            toneEmitter = emitter,
            focusDelegate = focus,
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.playSignal(ConvoySignalKind.OBSTACLE)
        runCurrent()

        assertTrue(player.isPlaying)
        assertTrue(focus.isFocusHeld)
        assertEquals(1, emitter.events.size)
        assertTrue(emitter.events.first() is ToneEvent.ToneOn)

        // Avanzar el tiempo completo del pulso (300 ms)
        advanceTimeBy(ConvoySignalKind.OBSTACLE.pulseDurationMs + 10)
        runCurrent()

        assertFalse(player.isPlaying)
        assertFalse(focus.isFocusHeld)
        assertEquals(1, focus.focusGrantedCount)
        assertEquals(1, focus.focusReleasedCount)
    }

    @Test
    fun testPlaySignalMultiPulseEmitsCorrectCadence() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val emitter = TestToneEmitter()
        val focus = TestAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultConvoySignalPlayer(
            toneEmitter = emitter,
            focusDelegate = focus,
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        // REDUCE_SPEED: 2 pulsos de 120 ms, gap 80 ms
        player.playSignal(ConvoySignalKind.REDUCE_SPEED)
        runCurrent()

        // Pulso 1
        assertEquals(1, emitter.events.filterIsInstance<ToneEvent.ToneOn>().size)
        advanceTimeBy(120)
        runCurrent()
        assertEquals(1, emitter.events.filterIsInstance<ToneEvent.ToneOff>().size)

        // Gap 80 ms
        advanceTimeBy(80)
        runCurrent()

        // Pulso 2
        assertEquals(2, emitter.events.filterIsInstance<ToneEvent.ToneOn>().size)
        advanceTimeBy(120)
        runCurrent()
        assertEquals(2, emitter.events.filterIsInstance<ToneEvent.ToneOff>().size)

        assertFalse(player.isPlaying)
        assertFalse(focus.isFocusHeld)
    }

    @Test
    fun testPlaySignalBlockedWhenSosIsActive() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val emitter = TestToneEmitter()
        val focus = TestAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultConvoySignalPlayer(
            toneEmitter = emitter,
            focusDelegate = focus,
            sosStateProvider = { true }, // SOS activo
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.playSignal(ConvoySignalKind.OBSTACLE)
        runCurrent()

        assertFalse(player.isPlaying)
        assertFalse(focus.isFocusHeld)
        assertEquals(0, emitter.events.size)
        assertEquals(0, focus.focusGrantedCount)
    }

    @Test
    fun testPlaySignalBlockedWhenPttIsActive() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val emitter = TestToneEmitter()
        val focus = TestAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultConvoySignalPlayer(
            toneEmitter = emitter,
            focusDelegate = focus,
            pttStateProvider = { true }, // PTT activo
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.playSignal(ConvoySignalKind.GAS_STOP)
        runCurrent()

        assertFalse(player.isPlaying)
        assertFalse(focus.isFocusHeld)
        assertEquals(0, emitter.events.size)
    }

    @Test
    fun testStopMidPlaybackReleasesFocusAndCancelsImmediately() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val emitter = TestToneEmitter()
        val focus = TestAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultConvoySignalPlayer(
            toneEmitter = emitter,
            focusDelegate = focus,
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.playSignal(ConvoySignalKind.POLICE_CHECKPOINT)
        runCurrent()

        assertTrue(player.isPlaying)
        assertTrue(focus.isFocusHeld)

        // Detener a mitad de camino
        player.stop()
        runCurrent()

        assertFalse(player.isPlaying)
        assertFalse(focus.isFocusHeld)
        assertEquals(1, focus.focusReleasedCount)
    }

    @Test
    fun testNewSignalInterruptsPreviousSignalCleanly() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val emitter = TestToneEmitter()
        val focus = TestAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultConvoySignalPlayer(
            toneEmitter = emitter,
            focusDelegate = focus,
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.playSignal(ConvoySignalKind.GAS_STOP) // 400 ms
        runCurrent()
        assertTrue(player.isPlaying)

        advanceTimeBy(100)
        runCurrent()

        // Disparar nueva señal mientras la primera suena
        player.playSignal(ConvoySignalKind.OBSTACLE)
        runCurrent()

        assertTrue(player.isPlaying)

        // Completar la segunda
        advanceTimeBy(350)
        runCurrent()

        assertFalse(player.isPlaying)
        assertFalse(focus.isFocusHeld)
    }

    @Test
    fun testHubIntegration() {
        val dummyPlayer = object : ConvoySignalAudioPlayer {
            var lastPlayed: ConvoySignalKind? = null
            var stopped = false
            override val isPlaying: Boolean get() = lastPlayed != null && !stopped
            override fun playSignal(kind: ConvoySignalKind) {
                lastPlayed = kind
                stopped = false
            }
            override fun playTone(toneType: Int, durationMs: Long) {}
            override fun playPattern(toneType: Int, pulseCount: Int, pulseDurationMs: Long, gapMs: Long) {}
            override fun stop() {
                stopped = true
            }
            override fun release() {
                stopped = true
            }
        }

        ConvoySignalAudioHub.setCustomPlayerForTesting(dummyPlayer)

        ConvoySignalAudioHub.playSignal(ConvoySignalKind.REGROUP)
        assertEquals(ConvoySignalKind.REGROUP, dummyPlayer.lastPlayed)
        assertTrue(ConvoySignalAudioHub.isPlaying)

        ConvoySignalAudioHub.stop()
        assertTrue(dummyPlayer.stopped)
        assertFalse(ConvoySignalAudioHub.isPlaying)

        ConvoySignalAudioHub.setCustomPlayerForTesting(null)
    }
}
