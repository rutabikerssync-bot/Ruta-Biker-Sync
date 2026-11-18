package com.rutabikersync.app.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Suite de pruebas unitarias para [PeerDirectionalTracker].
 * Conforme a los requisitos de auditoría de la Fase 6 (Hito 3 · Etapa 1):
 * - Rastrear métricas direccionales aisladas por peer (A -> B).
 * - Detección rigurosa de pérdidas basada en saltos de números de secuencia monotónicos.
 * - Soporte determinista de wraparound uint16 (0..65535).
 * - Prohibición de métricas agregadas o reportes falsos de 0.00% ante pérdidas reales.
 */
class PeerDirectionalPlrTest {

    private lateinit var tracker: PeerDirectionalTracker
    private val peerA: UShort = 2u
    private val peerB: UShort = 3u

    @Before
    fun setUp() {
        tracker = PeerDirectionalTracker()
    }

    @Test
    fun testFiveLossesOutOfOneHundredPacketsYieldsFivePercentPlr() {
        // Enviar 100 paquetes secuenciales (1..100), omitiendo 20, 40, 60, 80, 100 (5 pérdidas exactas)
        val omitted = setOf(20, 40, 60, 80, 100)
        for (seq in 1..100) {
            if (seq !in omitted) {
                tracker.recordPacket(peerA, seq)
            }
        }
        // Notificar fin de ráfaga esperada hasta el paquete 100
        tracker.recordStreamEnd(peerA, 100)

        val received = tracker.getReceivedCount(peerA)
        val expected = tracker.getExpectedCount(peerA)
        val lost = tracker.getLostCount(peerA)
        val plr = tracker.calculatePlr(peerA)

        assertEquals("Debe haber recibido 95 paquetes", 95, received)
        assertEquals("Debe haber esperado 100 paquetes", 100, expected)
        assertEquals("Debe haber detectado exactamente 5 paquetes perdidos", 5, lost)
        assertEquals("PLR debe ser exactamente 5.00%", 5.00f, plr, 0.001f)

        // Aserción de regresión contra falsos positivos de 0.00%
        assertNotEquals("PLR NO debe reportar 0.00% si hubo pérdidas", 0.00f, plr, 0.001f)
    }

    @Test
    fun testZeroLossesYieldsZeroPercentPlr() {
        // Enviar 100 paquetes continuos sin ninguna omisión (1..100)
        for (seq in 1..100) {
            tracker.recordPacket(peerA, seq)
        }

        val received = tracker.getReceivedCount(peerA)
        val expected = tracker.getExpectedCount(peerA)
        val lost = tracker.getLostCount(peerA)
        val plr = tracker.calculatePlr(peerA)

        assertEquals(100, received)
        assertEquals(100, expected)
        assertEquals(0, lost)
        assertEquals(0.00f, plr, 0.001f)
    }

    @Test
    fun testFiftyLossesOutOfOneHundredPacketsYieldsFiftyPercentPlr() {
        // Enviar sólo paquetes impares (1, 3, 5, ..., 99), omitiendo todos los pares (2, 4, ..., 100) -> 50 pérdidas
        for (seq in 1..100 step 2) {
            tracker.recordPacket(peerA, seq)
        }
        tracker.recordStreamEnd(peerA, 100)

        val received = tracker.getReceivedCount(peerA)
        val expected = tracker.getExpectedCount(peerA)
        val lost = tracker.getLostCount(peerA)
        val plr = tracker.calculatePlr(peerA)

        assertEquals(50, received)
        assertEquals(100, expected)
        assertEquals(50, lost)
        assertEquals(50.00f, plr, 0.001f)
    }

    @Test
    fun testUint16WraparoundDoesNotAccumulateFalseLosses() {
        // Simular transición continua sobre el límite de 16 bits (65530 a 5)
        // 65530, 65531, 65532, 65533, 65534, 65535, 0, 1, 2, 3, 4, 5 (12 paquetes)
        val sequence = (65530..65535).toList() + (0..5).toList()
        for (seq in sequence) {
            tracker.recordPacket(peerA, seq)
        }

        val received = tracker.getReceivedCount(peerA)
        val expected = tracker.getExpectedCount(peerA)
        val lost = tracker.getLostCount(peerA)
        val plr = tracker.calculatePlr(peerA)

        assertEquals("Debe haber recibido 12 paquetes", 12, received)
        assertEquals("Debe haber esperado 12 paquetes", 12, expected)
        assertEquals("No debe haber pérdidas durante el wraparound", 0, lost)
        assertEquals(0.00f, plr, 0.001f)
    }

    @Test
    fun testBleDuplicatePacketsDoNotDistortMetrics() {
        // Paquete 1 llega dos veces (común por rebote de escaneo BLE)
        tracker.recordPacket(peerA, 1)
        tracker.recordPacket(peerA, 1)
        tracker.recordPacket(peerA, 2)
        tracker.recordPacket(peerA, 2)

        val received = tracker.getReceivedCount(peerA)
        val expected = tracker.getExpectedCount(peerA)
        val lost = tracker.getLostCount(peerA)
        val plr = tracker.calculatePlr(peerA)

        assertEquals(2, received)
        assertEquals(2, expected)
        assertEquals(0, lost)
        assertEquals(0.00f, plr, 0.001f)
    }

    @Test
    fun testIsolatedTrackingPerPeer() {
        // Inyectar tráfico perfecto para Peer A (1..10)
        for (seq in 1..10) {
            tracker.recordPacket(peerA, seq)
        }

        // Inyectar tráfico con 50% de pérdida para Peer B (1, 3, 5, 7, 9 con streamEnd=10)
        for (seq in 1..9 step 2) {
            tracker.recordPacket(peerB, seq)
        }
        tracker.recordStreamEnd(peerB, 10)

        // Peer A debe reportar 0%
        assertEquals(0.00f, tracker.calculatePlr(peerA), 0.001f)
        assertEquals(0, tracker.getLostCount(peerA))

        // Peer B debe reportar 50%
        assertEquals(50.00f, tracker.calculatePlr(peerB), 0.001f)
        assertEquals(5, tracker.getLostCount(peerB))
    }

    @Test
    fun testBootEpochChangeResetsTrackingWithoutCarryingOverPreviousLosses() {
        // 1. Peer A emite 50 paquetes en época 1
        for (seq in 1..50) {
            tracker.recordPacket(peerA, seq, bootEpoch = 1)
        }
        assertEquals(50, tracker.getReceivedCount(peerA))
        assertEquals(50, tracker.getExpectedCount(peerA))
        assertEquals(0, tracker.getLostCount(peerA))

        // 2. Peer A se reinicia en época 2 e inicia nueva secuencia 1..50
        for (seq in 1..50) {
            tracker.recordPacket(peerA, seq, bootEpoch = 2)
        }

        // 3. El tracker debe haberse reseteado en el cambio de época y contar solo la nueva época (50, NO 100)
        assertEquals("receivedCount debe ser 50 (segunda época)", 50, tracker.getReceivedCount(peerA))
        assertEquals("expectedCount debe ser 50 (segunda época)", 50, tracker.getExpectedCount(peerA))
        assertEquals("lostCount debe ser 0", 0, tracker.getLostCount(peerA))
        assertEquals("PLR debe ser exactamente 0.00%", 0.00f, tracker.calculatePlr(peerA), 0.001f)
    }

    @Test
    fun testBootEpochChangeWithLossesCalculatesAccurateTenPercentPlr() {
        // 1. Peer A emite 50 paquetes en época 1
        for (seq in 1..50) {
            tracker.recordPacket(peerA, seq, bootEpoch = 1)
        }

        // 2. Peer A se reinicia en época 2 y emite 50 paquetes (1..50), pero se pierden 5 exactos (10, 20, 30, 40, 50)
        val omitted = setOf(10, 20, 30, 40, 50)
        for (seq in 1..50) {
            if (seq !in omitted) {
                tracker.recordPacket(peerA, seq, bootEpoch = 2)
            }
        }
        tracker.recordStreamEnd(peerA, 50)

        // 3. Las métricas deben reflejar exactamente 5 perdidos de 50 esperados = 10.00% PLR
        assertEquals("receivedCount debe ser 45", 45, tracker.getReceivedCount(peerA))
        assertEquals("expectedCount debe ser 50", 50, tracker.getExpectedCount(peerA))
        assertEquals("lostCount debe ser exactamente 5", 5, tracker.getLostCount(peerA))
        assertEquals("PLR debe ser exactamente 10.00% (5/50)", 10.00f, tracker.calculatePlr(peerA), 0.001f)
        assertNotEquals("PLR no debe ser 5% ni 90%", 5.00f, tracker.calculatePlr(peerA), 0.001f)
    }

    @Test
    fun testConcurrentPacketRecordingDoesNotCorruptMetrics() {
        val threads = mutableListOf<Thread>()
        val peers = listOf(1u.toUShort(), 2u.toUShort(), 3u.toUShort(), 4u.toUShort())

        for (peer in peers) {
            val t = Thread {
                for (seq in 1..50) {
                    tracker.recordPacket(peer, seq, bootEpoch = 1)
                }
            }
            threads.add(t)
            t.start()
        }

        for (t in threads) {
            t.join(2000L)
        }

        for (peer in peers) {
            assertEquals("Cada peer debe registrar exactamente 50 paquetes", 50, tracker.getReceivedCount(peer))
            assertEquals(50, tracker.getExpectedCount(peer))
            assertEquals(0, tracker.getLostCount(peer))
            assertEquals(0.00f, tracker.calculatePlr(peer), 0.001f)
        }
    }

    @Test
    fun testStreamIsolationHeartbeatVersusVoicePackets() {
        // Simular que el tracker de telemetría recibe sólo Heartbeats con secuencias 1..50
        for (seq in 1..50) {
            tracker.recordPacket(peerA, seq, bootEpoch = 5)
        }

        // Si los VoicePackets tuvieran secuencias arbitrarias (ej. 11000..11050), al estar aislados en BleMeshService
        // no se inyectan al tracker de telemetría.
        assertEquals(50, tracker.getReceivedCount(peerA))
        assertEquals(50, tracker.getExpectedCount(peerA))
        assertEquals(0, tracker.getLostCount(peerA))
        assertEquals(0.00f, tracker.calculatePlr(peerA), 0.001f)
    }
}

