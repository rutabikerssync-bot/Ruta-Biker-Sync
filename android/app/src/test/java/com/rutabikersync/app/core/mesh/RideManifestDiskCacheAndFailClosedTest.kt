package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.crypto.RideManifestDiskCache
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideManifestDiskCacheAndFailClosedTest {

    private class CapturingTransport : Transport {
        val sent = mutableListOf<ByteArray>()
        override val name: String = "TEST_CAPTURING"
        override val incoming: Flow<ByteArray> = emptyFlow()
        override suspend fun start() {}
        override suspend fun stop() {}
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
            sent.add(payload)
        }
    }

    @Test
    fun `mesh real con requireAuthorization true rechaza paquetes si rideAuthorization es null (fail-closed)`() = runTest(UnconfinedTestDispatcher()) {
        val secretKey = "rbs-p0-failclosed-secret-key-32b".toByteArray(Charsets.UTF_8)
        val senderTransport = CapturingTransport()
        val sender = MeshRelayEngine(
            localShortId = 2u,
            transport = senderTransport,
            rideSecretKey = secretKey,
            relayScope = backgroundScope
        ,
        requireAuthorization = false
        )
        sender.emitRideLifecycleEvent(MeshRelayEngine.RideLifecycleEvent.START_OUTBOUND)
        assertTrue(senderTransport.sent.isNotEmpty())

        val receiver = MeshRelayEngine(
            localShortId = 1u,
            transport = CapturingTransport(),
            rideSecretKey = secretKey,
            relayScope = backgroundScope,
            requireAuthorization = true
        )
        val receivedEvents = mutableListOf<MeshRelayEngine.IncomingRideLifecycleEvent>()
        receiver.onRideLifecycleEventReceived = { receivedEvents.add(it) }

        // rideAuthorization permanece null (caso sin manifiesto verificado)
        receiver.onRawPacketReceived(senderTransport.sent.first(), nowEpochMs = 1_000L)

        assertTrue(receivedEvents.isEmpty())
        assertTrue(receiver.peers.value.isEmpty())
    }

    @Test
    fun `RideManifestDiskCache cifra en JVM, valida rideId y expira tras 24h`() {
        val cache = RideManifestDiskCache(context = null)
        val now = 1_700_000_000L
        val entry = RideManifestDiskCache.Entry(
            rideId = "ride-disk-01",
            manifestToken = "signed.token.payload",
            publicKeys = mapOf("k1" to "pubkey-base64"),
            participantUid = "uid-1",
            savedAtEpochSec = now
        )

        cache.save(entry)
        val loaded = cache.load("ride-disk-01", nowEpochSec = now + 3600L)
        assertNotNull(loaded)
        assertEquals("signed.token.payload", loaded?.manifestToken)

        // Expirado (> 24h) se descarta y limpia
        val expired = cache.load("ride-disk-01", nowEpochSec = now + RideManifestDiskCache.MAX_CACHE_AGE_SEC + 1L)
        assertNull(expired)
        assertNull(cache.load("ride-disk-01", nowEpochSec = now + 10L))
    }
}
