package com.rutabikersync.app.core.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de TDD para SosTonePlayer (Hito 1 - Audio SOS).
 * Valida la secuencia Morse 3-3-3, la idempotencia de start/stop,
 * el control de AudioFocus y el respeto prioritario a PTT.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosTonePlayerTest {

    sealed class ToneEvent {
        data class ToneOn(val durationMs: Long) : ToneEvent()
        object ToneOff : ToneEvent()
        data class Gap(val durationMs: Long) : ToneEvent()
    }

    class FakeToneEmitter : ToneEmitter {
        val events = mutableListOf<ToneEvent>()
        var isToneActive = false
        var isReleased = false

        var canStartTone = true

        override fun toneOn(durationMs: Long): Boolean {
            events.add(ToneEvent.ToneOn(durationMs))
            if (!canStartTone) return false
            isReleased = false
            isToneActive = true
            return true
        }

        override fun toneOff() {
            isToneActive = false
            events.add(ToneEvent.ToneOff)
        }

        override fun release() {
            isToneActive = false
            isReleased = true
        }
    }

    class FakeAudioFocusDelegate(var canGrantFocus: Boolean = true) : AudioFocusDelegate {
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
    fun testMorsePattern333SequenceEmittedCorrectly() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeEmitter = FakeToneEmitter()
        val fakeFocus = FakeAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultSosTonePlayer(
            toneEmitter = fakeEmitter,
            focusDelegate = fakeFocus,
            pttStateProvider = { false },
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.start()
        assertTrue(player.isPlaying)
        assertTrue(fakeFocus.isFocusHeld)

        // Duración esperada de 1 ciclo SOS:
        // S: 3 x 150ms + 2 x 100ms = 650ms
        // Letra gap: 250ms
        // O: 3 x 450ms + 2 x 100ms = 1550ms
        // Letra gap: 250ms
        // S: 3 x 150ms + 2 x 100ms = 650ms
        // Pausa repetición ciclo: 1500ms
        // Total ciclo = 650 + 250 + 1550 + 250 + 650 + 1500 = 4850ms

        // Avanzamos justo después de la primera 'S' (650ms + 10ms)
        testScope.advanceTimeBy(660L)
        val sPulses = fakeEmitter.events.filterIsInstance<ToneEvent.ToneOn>()
        assertEquals(3, sPulses.size)
        assertTrue(sPulses.all { it.durationMs == SosTonePlayer.DOT_DURATION_MS })

        // Avanzamos hasta después de la 'O' (650 + 250 + 1550 + 10 = 2460ms)
        testScope.advanceTimeBy(1800L)
        val oPulses = fakeEmitter.events.filterIsInstance<ToneEvent.ToneOn>().drop(3)
        assertEquals(3, oPulses.size)
        assertTrue(oPulses.all { it.durationMs == SosTonePlayer.DASH_DURATION_MS })

        // Avanzamos hasta completar el segundo 'S' (2460 + 250 + 650 + 10 = 3370ms)
        testScope.advanceTimeBy(910L)
        val allPulses = fakeEmitter.events.filterIsInstance<ToneEvent.ToneOn>()
        assertEquals(9, allPulses.size)
        // 3 puntos, 3 rayas, 3 puntos
        val durations = allPulses.map { it.durationMs }
        assertEquals(
            listOf(150L, 150L, 150L, 450L, 450L, 450L, 150L, 150L, 150L),
            durations
        )

        player.stop()
        assertFalse(player.isPlaying)
        assertFalse(fakeFocus.isFocusHeld)
    }

    @Test
    fun testStartIsIdempotentAndDoesNotDuplicatePlayback() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeEmitter = FakeToneEmitter()
        val fakeFocus = FakeAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultSosTonePlayer(
            toneEmitter = fakeEmitter,
            focusDelegate = fakeFocus,
            pttStateProvider = { false },
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.start()
        player.start() // Segunda llamada redundante
        player.start() // Tercera llamada redundante

        assertEquals(1, fakeFocus.focusGrantedCount)
        assertTrue(player.isPlaying)

        testScope.advanceTimeBy(1000L)
        player.stop()

        assertEquals(1, fakeFocus.focusReleasedCount)
        assertFalse(player.isPlaying)
    }

    @Test
    fun testStopIsIdempotentAndReleasesAllResources() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeEmitter = FakeToneEmitter()
        val fakeFocus = FakeAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultSosTonePlayer(
            toneEmitter = fakeEmitter,
            focusDelegate = fakeFocus,
            pttStateProvider = { false },
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        // Stop en frío cuando no estaba reproduciendo
        player.stop()
        assertFalse(player.isPlaying)
        assertEquals(0, fakeFocus.focusReleasedCount)

        // Start y stop
        player.start()
        assertTrue(player.isPlaying)
        player.stop()
        assertFalse(player.isPlaying)
        assertEquals(1, fakeFocus.focusReleasedCount)

        // Stop redundante
        player.stop()
        player.stop()
        assertEquals(1, fakeFocus.focusReleasedCount)
    }

    @Test
    fun testPttActiveBlocksToneStart() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeEmitter = FakeToneEmitter()
        val fakeFocus = FakeAudioFocusDelegate(canGrantFocus = true)

        var isPttTalking = true
        val player = DefaultSosTonePlayer(
            toneEmitter = fakeEmitter,
            focusDelegate = fakeFocus,
            pttStateProvider = { isPttTalking },
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.start()

        // Si PTT está activo, no debe iniciar para no contaminar el micrófono ni disputar audio
        assertFalse(player.isPlaying)
        assertFalse(fakeFocus.isFocusHeld)
        assertTrue(fakeEmitter.events.isEmpty())

        // Cuando PTT se libera, start sí procede
        isPttTalking = false
        player.start()
        assertTrue(player.isPlaying)
        assertTrue(fakeFocus.isFocusHeld)

        player.stop()
    }

    @Test
    fun testFocusDeniedPreventsPlayback() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeEmitter = FakeToneEmitter()
        val fakeFocus = FakeAudioFocusDelegate(canGrantFocus = false)

        val player = DefaultSosTonePlayer(
            toneEmitter = fakeEmitter,
            focusDelegate = fakeFocus,
            pttStateProvider = { false },
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.start()

        assertFalse(player.isPlaying)
        assertFalse(fakeFocus.isFocusHeld)
        assertTrue(fakeEmitter.events.isEmpty())
    }

    @Test
    fun testStopMidCycleAbortsImmediately() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeEmitter = FakeToneEmitter()
        val fakeFocus = FakeAudioFocusDelegate(canGrantFocus = true)

        val player = DefaultSosTonePlayer(
            toneEmitter = fakeEmitter,
            focusDelegate = fakeFocus,
            pttStateProvider = { false },
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        player.start()
        testScope.advanceTimeBy(300L) // En mitad del segundo punto
        player.stop()

        assertFalse(player.isPlaying)
        assertFalse(fakeFocus.isFocusHeld)
        assertFalse(fakeEmitter.isToneActive)
        assertTrue(fakeEmitter.isReleased)

        val eventCountAtStop = fakeEmitter.events.size

        // Si avanzamos más el tiempo, no deben registrarse nuevos eventos
        testScope.advanceTimeBy(5000L)
        assertEquals(eventCountAtStop, fakeEmitter.events.size)
    }

    @Test
    fun testSosToneHubCoordinatesLifecycleAndSinglePlayer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeEmitter = FakeToneEmitter()
        val fakeFocus = FakeAudioFocusDelegate(canGrantFocus = true)

        var pttActive = false
        val player = DefaultSosTonePlayer(
            toneEmitter = fakeEmitter,
            focusDelegate = fakeFocus,
            pttStateProvider = { pttActive },
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )

        SosToneHub.setCustomPlayerForTesting(player)
        SosToneHub.pttStateProvider = PttStateProvider { pttActive }

        // 1. Iniciar vía Hub
        SosToneHub.start()
        assertTrue(SosToneHub.isPlaying)
        assertTrue(player.isPlaying)
        assertTrue(fakeFocus.isFocusHeld)

        // 2. Parar vía Hub (simulando ACK recibido o stopRide)
        SosToneHub.stop()
        assertFalse(SosToneHub.isPlaying)
        assertFalse(player.isPlaying)
        assertFalse(fakeFocus.isFocusHeld)

        // 3. Si PTT está hablando, SosToneHub.start() no inicia
        pttActive = true
        SosToneHub.start()
        assertFalse(SosToneHub.isPlaying)
        assertFalse(fakeFocus.isFocusHeld)

        // Limpiar mock estático
        SosToneHub.setCustomPlayerForTesting(null)
    }

    @Test
    fun testToneGeneratorFailureStopsAndReleasesFocus() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val emitter = FakeToneEmitter().apply { canStartTone = false }
        val focus = FakeAudioFocusDelegate()
        val player = DefaultSosTonePlayer(emitter, focus, { false }, scope, dispatcher)

        player.start()
        scope.runCurrent()

        assertFalse(player.isPlaying)
        assertFalse(focus.isFocusHeld)
        assertEquals(1, focus.focusReleasedCount)
    }

    @Test
    fun testOldJobCannotReleaseFocusOfRestartedPlayback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val emitter = FakeToneEmitter()
        val focus = FakeAudioFocusDelegate()
        val player = DefaultSosTonePlayer(emitter, focus, { false }, scope, dispatcher)

        player.start()
        scope.advanceTimeBy(100L)
        player.stop()
        player.start() // Reiniciar antes de que ejecute el finally cancelado del job viejo.
        scope.runCurrent()

        assertTrue(player.isPlaying)
        assertTrue(focus.isFocusHeld)
        assertEquals(2, focus.focusGrantedCount)
        assertEquals(1, focus.focusReleasedCount)
        player.stop()
    }

    @Test
    fun testCanceledScopeDoesNotLeaveFocusOrFalsePlayingState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val canceledScope = TestScope(dispatcher)
        canceledScope.cancel()
        val emitter = FakeToneEmitter()
        val focus = FakeAudioFocusDelegate()
        val player = DefaultSosTonePlayer(emitter, focus, { false }, canceledScope, dispatcher)

        player.start()

        assertFalse(player.isPlaying)
        assertFalse(focus.isFocusHeld)
        assertFalse(emitter.isToneActive)
    }
}
