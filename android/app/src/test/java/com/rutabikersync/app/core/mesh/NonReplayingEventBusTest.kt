package com.rutabikersync.app.core.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1/T4: recrear la cabina no re-ejecuta señales ni eventos de ciclo antiguos. */
@OptIn(ExperimentalCoroutinesApi::class)
class NonReplayingEventBusTest {

    @Test
    fun `un evento no se re-ejecuta al recrear la pantalla`() = runTest {
        val bus = NonReplayingEventBus<String>()

        // Primera "cabina": recibe el evento.
        val firstCabina = mutableListOf<String>()
        val firstCollector = launch { bus.events.take(1).toList(firstCabina) }
        advanceUntilIdle()
        assertTrue(bus.publish("start-outbound-#41"))
        advanceUntilIdle()
        assertEquals(listOf("start-outbound-#41"), firstCabina)
        firstCollector.cancel()

        // Segunda "cabina": no debe replay del evento anterior.
        val recreatedCabina = mutableListOf<String>()
        val secondCollector = launch { bus.events.take(1).toList(recreatedCabina) }
        advanceUntilIdle()
        assertTrue("El evento viejo no debe re-ejecutarse", recreatedCabina.isEmpty())

        // Sí debe recibir el evento siguiente.
        assertTrue(bus.publish("safe-home-#42"))
        advanceUntilIdle()
        assertEquals(listOf("safe-home-#42"), recreatedCabina)
        secondCollector.cancel()
    }

    @Test
    fun `la suscripcion tardia no recibe el ultimo evento emitido`() = runTest {
        val bus = NonReplayingEventBus<Int>()
        assertTrue(bus.publish(1))
        assertTrue(bus.publish(2))

        val received = mutableListOf<Int>()
        val collector = launch { bus.events.take(1).toList(received) }
        advanceUntilIdle()

        assertTrue("replay=0: ningún evento antiguo debe entregarse", received.isEmpty())
        collector.cancel()
    }
}
