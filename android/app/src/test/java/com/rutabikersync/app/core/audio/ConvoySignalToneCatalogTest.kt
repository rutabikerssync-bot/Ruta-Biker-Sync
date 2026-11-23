package com.rutabikersync.app.core.audio

import android.media.ToneGenerator
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** P1/T2: unicidad de la tupla audible completa de las 11 señales. */
class ConvoySignalToneCatalogTest {

    @Test
    fun `las 11 señales tienen tuplas audibles distintas`() {
        val catalog = ConvoySignalToneCatalog.all

        assertEquals(11, catalog.size)
        assertEquals(
            "No puede haber dos señales con la misma tupla (tono, pulsos, duración, gap)",
            11,
            catalog.values.toSet().size
        )
        catalog.forEach { (name, tuple) ->
            assertNotEquals("Tupla inválida en $name", 0, tuple.toneType)
            assertNotEquals("Duración inválida en $name", 0L, tuple.pulseDurationMs)
            assertNotEquals("Gap inválido en $name", -1L, tuple.gapMs)
        }
    }

    @Test
    fun `los pares casi identicos ya no comparten tupla`() {
        val gasStop = ConvoySignalToneCatalog.tupleFor(ConvoySignalKind.GAS_STOP)
        val arrive = ConvoySignalToneCatalog.tupleFor(MeshRelayEngine.RideLifecycleEvent.ARRIVE_DESTINATION)
        assertNotEquals(gasStop, arrive)
        assertEquals(
            SignalToneTuple(ToneGenerator.TONE_PROP_ACK, 1, 6_000L, 0L),
            gasStop
        )
        assertEquals(
            SignalToneTuple(ToneGenerator.TONE_PROP_ACK, 2, 2_800L, 400L),
            arrive
        )

        val regroup = ConvoySignalToneCatalog.tupleFor(ConvoySignalKind.REGROUP)
        val startOutbound = ConvoySignalToneCatalog.tupleFor(MeshRelayEngine.RideLifecycleEvent.START_OUTBOUND)
        assertNotEquals(regroup, startOutbound)
        assertEquals(
            SignalToneTuple(ToneGenerator.TONE_PROP_BEEP, 2, 2_800L, 400L),
            regroup
        )
        assertEquals(
            SignalToneTuple(ToneGenerator.TONE_PROP_BEEP, 3, 1_800L, 300L),
            startOutbound
        )
    }

    @Test
    fun `los 10 tonos de mensaje duran exactamente 6 segundos`() {
        val messageTuples = ConvoySignalToneCatalog.quickSignals.values +
            ConvoySignalToneCatalog.lifecycleEvents.values

        assertEquals(10, messageTuples.size)
        messageTuples.forEach { tuple ->
            assertEquals(
                "Todo mensaje debe durar 6000 ms: $tuple",
                6_000L,
                tuple.totalDurationMs
            )
        }
        // El SOS no es un mensaje de 6 s: se repite mientras esté activo.
        assertEquals(3 * 150L + 2 * 250L, ConvoySignalToneCatalog.sosLocal.totalDurationMs)
    }

    @Test
    fun `el catalogo no altera los codigos de wire`() {
        assertEquals(
            listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte(), 5.toByte()),
            ConvoySignalKind.entries.map { it.code }
        )
        assertEquals(
            listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte(), 5.toByte()),
            MeshRelayEngine.RideLifecycleEvent.entries.map { it.code }
        )
        assertEquals(
            listOf(1, 2, 3, 4, 5).map { it.toByte() },
            ConvoySignalKind.entries.map { ConvoySignalKind.fromCode(it.code)!!.code }
        )
    }
}
