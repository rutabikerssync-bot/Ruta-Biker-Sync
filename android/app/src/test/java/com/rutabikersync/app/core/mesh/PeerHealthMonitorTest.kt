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

    private fun heartbeat(sequenceNumber: Int, nowEpochMs: Long): MeshPacket =
        MeshPacket.createSigned(
            shortId = 2u,
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
        val receiver = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey)
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
        val receiver = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey)
        val monitor = PeerHealthMonitor(engine = receiver)
        val t0 = 2_000_000L

        receiver.onRawPacketReceived(
            heartbeat(sequenceNumber = 1, nowEpochMs = t0).toByteArray(),
            nowEpochMs = t0,
            rssi = -105 // RSSI pésimo: no influye, solo cuenta el timeout.
        )

        val justoAntes = monitor.evaluateHealth(t0 + MeshRelayEngine.LOST_TIMEOUT_MS - 1_000L)
        assertEquals(0, justoAntes.lostPeersCount)

        val perdido = monitor.evaluateHealth(t0 + 45_000L)
        assertEquals(1, perdido.lostPeersCount)
        assertEquals(0, perdido.onlinePeersCount)
        assertTrue(receiver.peers.value.getValue(2u).isLost)
    }
}
