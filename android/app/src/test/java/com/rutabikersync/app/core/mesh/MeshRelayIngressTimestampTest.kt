package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `lastSeenEpochMs` debe ser la hora real de recepción en el motor, aunque el
 * transporte entregue un `IncomingPacket.timestampEpochMs` atrasado (cola del
 * SharedFlow en BLE). Reproduce el desfase de ~15 min medido en TECNO y de
 * 11,5 s medido luego en REDMI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRelayIngressTimestampTest {

    private val secretKey = "rbs-ingress-timestamp-key-32b!!!".encodeToByteArray()

    private class StaleTimestampTransport : Transport {
        override val name: String = "StaleTimestampTransport"

        private val _incoming = MutableSharedFlow<ByteArray>()
        override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

        private val _incomingPackets = MutableSharedFlow<Transport.IncomingPacket>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        override val incomingPackets: Flow<Transport.IncomingPacket> = _incomingPackets.asSharedFlow()

        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) = Unit

        fun emit(bytes: ByteArray, timestampEpochMs: Long) {
            _incomingPackets.tryEmit(
                Transport.IncomingPacket(bytes = bytes, rssi = -55, timestampEpochMs = timestampEpochMs)
            )
        }
    }

    private fun heartbeat() = MeshPacket.createSigned(
        shortId = 2u,
        latitude = 19.4326,
        longitude = -99.1332,
        speedKmh = 45,
        bearingDegrees = 90f,
        batteryPercent = 80,
        sequenceNumber = 1,
        ttl = 1,
        rideSecretKey = secretKey
    )

    @Test
    fun `lastSeenEpochMs usa la hora de recepcion aunque el paquete traiga timestamp atrasado 15 min`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = StaleTimestampTransport()
            val engine = MeshRelayEngine(
                localShortId = 1u,
                transport = transport,
                rideSecretKey = secretKey
            ,
            requireAuthorization = false
            )
            engine.start(backgroundScope)

            val staleTimestamp = System.currentTimeMillis() - 15 * 60_000L
            val beforeReceive = System.currentTimeMillis()
            transport.emit(heartbeat().toByteArray(), staleTimestamp)
            runCurrent()
            val afterReceive = System.currentTimeMillis()

            val track = engine.peers.value[2u]
            assertNotNull("Debe registrar el peer", track)
            assertTrue(
                "lastSeenEpochMs=${track!!.lastSeenEpochMs} debe estar entre $beforeReceive y $afterReceive, " +
                    "no en el timestamp atrasado $staleTimestamp",
                track.lastSeenEpochMs in beforeReceive..afterReceive
            )

            engine.stop()
        }

    @Test
    fun `el ts del transporte no altera la edad ni el log de entrada del heartbeat`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = StaleTimestampTransport()
            val engine = MeshRelayEngine(
                localShortId = 1u,
                transport = transport,
                rideSecretKey = secretKey
            ,
            requireAuthorization = false
            )
            engine.start(backgroundScope)

            val staleTimestamp = System.currentTimeMillis() - 11_500L
            transport.emit(heartbeat().toByteArray(), staleTimestamp)
            runCurrent()

            val entry = engine.getPacketLogs().first { it.direction == "RX" }
            val loggedAt = java.time.Instant.parse(entry.timestampUtc).toEpochMilli()
            assertTrue(
                "timestampUtc=$loggedAt debe ser reciente, no $staleTimestamp",
                loggedAt >= staleTimestamp + 10_000L
            )

            engine.stop()
        }
}
