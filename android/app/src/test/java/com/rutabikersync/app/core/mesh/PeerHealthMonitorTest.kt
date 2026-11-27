package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.protocol.MeshPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regla de diseño de FIX B fase 1: el timeout de pérdida debe derivarse del intervalo
 * REAL de llegada medido en campo, no del tick ideal del scheduler de 3 s.
 *
 * Evidencia: `docs/evidence/fix_gps_retencion_20261121/heartbeat_gaps.txt` (27,27,27,27 s).
 * No se toca `FRAGMENT_REPEATS` ni ningún parámetro de radio en esta fase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerHealthMonitorTest {

    private val secretKey = "peer-health-timeout-test-key-32bytes!!".encodeToByteArray()

    private fun heartbeat(
        sequenceNumber: Int,
        nowEpochMs: Long,
        shortId: UShort = 2u
    ): MeshPacket = MeshPacket.createSigned(
            shortId = shortId,
            latitude = 19.4300,
            longitude = -99.1332,
            speedKmh = 45,
            bearingDegrees = 90f,
            batteryPercent = 80,
            sequenceNumber = sequenceNumber,
            ttl = 3,
            rideSecretKey = secretKey
        )

    @Test
    fun `timeout derivado cumple factor 1_5 sobre intervalo de llegada medido`() {
        val intervalo = MeshRelayEngine.OBSERVED_HEARTBEAT_ARRIVAL_INTERVAL_MS
        val numerador = MeshRelayEngine.LOST_TIMEOUT_TOLERANCE_NUMERATOR
        val denominador = MeshRelayEngine.LOST_TIMEOUT_TOLERANCE_DENOMINATOR

        assertEquals(27_000L, intervalo)
        assertEquals(40_500L, MeshRelayEngine.LOST_TIMEOUT_MS)
        assertTrue(MeshRelayEngine.LOST_TIMEOUT_MS >= intervalo * numerador / denominador)
        assertEquals(
            (MeshRelayEngine.LOST_TIMEOUT_MS / MeshRelayEngine.HEARTBEAT_INTERVAL_MS).toInt(),
            MeshRelayEngine.LOST_THRESHOLD_BEATS
        )
    }

    @Test
    fun `heartbeat cada 27s no marca perdido con timeout derivado`() = runTest(UnconfinedTestDispatcher()) {
        val receiver = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey, requireAuthorization = false)
        val monitor = PeerHealthMonitor(engine = receiver)
        val t0 = 2_000_000L
        val llegada = MeshRelayEngine.OBSERVED_HEARTBEAT_ARRIVAL_INTERVAL_MS

        repeat(4) { index ->
            val ahora = t0 + llegada * index
            receiver.onRawPacketReceived(
                heartbeat(sequenceNumber = index + 1, nowEpochMs = ahora).toByteArray(),
                nowEpochMs = ahora,
                rssi = -60
            )

            // Un segundo antes de la siguiente llegada: 26 s < 40,5 s -> el peer sigue en línea.
            val snapshot = monitor.evaluateHealth(ahora + llegada - 1_000L)
            assertEquals(1, snapshot.onlinePeersCount)
            assertEquals(0, snapshot.lostPeersCount)
            assertFalse(receiver.peers.value.getValue(2u).isLost)
        }
    }

    @Test
    fun `silencio de 45s supera timeout derivado y marca perdido`() = runTest(UnconfinedTestDispatcher()) {
        val receiver = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey, requireAuthorization = false)
        val monitor = PeerHealthMonitor(engine = receiver)
        val t0 = 2_000_000L

        receiver.onRawPacketReceived(
            heartbeat(sequenceNumber = 1, nowEpochMs = t0).toByteArray(),
            nowEpochMs = t0,
            rssi = -105 // RSSI pésimo: no influye, solo cuenta el timeout.
        )

        val justoAntes = monitor.evaluateHealth(t0 + MeshRelayEngine.LOST_TIMEOUT_MS - 1_000L)
        assertEquals(0, justoAntes.lostPeersCount)
        assertEquals(0, justoAntes.suspectedLostPeersCount)

        // Al vencer el timeout hay pérdida SOSPECHADA (cruda), pero no confirmada todavía.
        val perdido = monitor.evaluateHealth(t0 + 45_000L)
        assertEquals(1, perdido.suspectedLostPeersCount)
        assertEquals(0, perdido.lostPeersCount)
        // En gracia el peer sigue contando como presente para el monitor (degradado, no caído).
        assertEquals(1, perdido.onlinePeersCount)
        assertTrue(receiver.peers.value.getValue(2u).isLost)

        // Agotada la gracia derivada del timeout, sí hay pérdida confirmada.
        // `lostSinceEpochMs` se fija en el instante en que se evaluó el timeout (45 s).
        val confirmadoEn = t0 + 45_000L +
            PeerGracePolicy.graceMsFor(MeshRelayEngine.LOST_TIMEOUT_MS) + 1L
        val confirmado = monitor.evaluateHealth(confirmadoEn)
        assertEquals(1, confirmado.suspectedLostPeersCount)
        assertEquals(1, confirmado.lostPeersCount)
    }

    @Test
    fun `isla confirmada avisa de inmediato y repite solo despues de 60s`() = runTest(UnconfinedTestDispatcher()) {
        val receiver = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey, requireAuthorization = false)
        val avisos = mutableListOf<String>()
        val monitor = PeerHealthMonitor(engine = receiver, onIslandTtsAnnouncement = { avisos.add(it) })
        val t0 = 2_000_000L

        receiver.onRawPacketReceived(heartbeat(1, t0, 2u).toByteArray(), nowEpochMs = t0)
        receiver.onRawPacketReceived(heartbeat(1, t0, 3u).toByteArray(), nowEpochMs = t0)
        receiver.evaluatePeerTimeouts(t0 + MeshRelayEngine.LOST_TIMEOUT_MS)

        val confirmadoEn = t0 + MeshRelayEngine.LOST_TIMEOUT_MS +
            PeerGracePolicy.graceMsFor(MeshRelayEngine.LOST_TIMEOUT_MS) + 1L
        val primera = monitor.evaluateHealth(confirmadoEn)
        assertEquals(2, primera.suspectedLostPeersCount)
        assertEquals(2, primera.lostPeersCount)
        assertTrue(primera.isIslandActive)
        assertEquals(1, avisos.size)
        assertEquals("Mesh parcial: 2 nodos fuera de contacto", avisos.first())

        // Antes de los 60 s no hay segundo aviso; después, uno solo.
        monitor.evaluateHealth(confirmadoEn + PeerHealthMonitor.ISLAND_TTS_THRESHOLD_MS)
        assertEquals(1, avisos.size)
        monitor.evaluateHealth(confirmadoEn + PeerHealthMonitor.ISLAND_TTS_THRESHOLD_MS + 1L)
        assertEquals(2, avisos.size)
        assertEquals("Mesh parcial: 2 nodos siguen fuera de contacto", avisos.last())
        monitor.evaluateHealth(confirmadoEn + 180_000L)
        assertEquals(2, avisos.size)
    }
}
