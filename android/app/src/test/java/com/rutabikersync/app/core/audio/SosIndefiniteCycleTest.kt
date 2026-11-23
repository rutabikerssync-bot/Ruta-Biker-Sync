package com.rutabikersync.app.core.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El tono SOS no tiene duración de mensaje: se repite mientras esté activo. */
@OptIn(ExperimentalCoroutinesApi::class)
class SosIndefiniteCycleTest {

    class CountingEmitter : ToneEmitter {
        var toneOnCount = 0
        override fun toneOn(durationMs: Long): Boolean = toneOn(0, durationMs)
        override fun toneOn(toneType: Int, durationMs: Long): Boolean {
            toneOnCount++
            return true
        }
        override fun toneOff() = Unit
        override fun release() = Unit
    }

    class CountingFocus : AudioFocusDelegate {
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

    @Test
    fun `el SOS sigue emitiendo ciclos y solo para al detenerlo`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val emitter = CountingEmitter()
        val focus = CountingFocus()
        val player = DefaultSosTonePlayer(
            toneEmitter = emitter,
            focusDelegate = focus,
            pttStateProvider = PttStateProvider { false },
            coroutineScope = scope,
            dispatcher = dispatcher
        )

        player.start()
        runCurrent()
        assertTrue(player.isPlaying)

        // Un ciclo Morse dura 4850 ms: tras 10 s ya debería ir por el segundo ciclo.
        scope.advanceTimeBy(10_000)
        runCurrent()
        assertTrue("El SOS debe seguir activo pasados varios ciclos", player.isPlaying)
        assertTrue("Debe haber emitido más de un ciclo (9 pulsos)", emitter.toneOnCount > 9)

        player.stop()
        runCurrent()
        assertFalse(player.isPlaying)
        assertFalse(focus.isFocusHeld)
    }
}
