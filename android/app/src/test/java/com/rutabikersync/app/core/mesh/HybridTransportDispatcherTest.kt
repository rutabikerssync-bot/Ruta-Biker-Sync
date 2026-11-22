package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HybridTransportDispatcherTest {

    private val now = 1_800_000_000_000L
    private val userId = "e54bb490-754a-4aa6-9b4c-8b7b043e9dff"

    private fun sample(
        id: String = userId,
        lat: Double = 10.3538,
        lng: Double = -67.0425,
        observedAt: Long = now - 2_000L
    ) = ClubApiClient.RemoteTelemetrySample(
        userId = id,
        latitude = lat,
        longitude = lng,
        heading = 90.0,
        speedKmh = 42.0,
        observedAtMillis = observedAt
    )

    @Test
    fun ` BLE gana sobre HTTP cuando el usuario ya esta en la malla`() {
        val dispatcher = HybridTransportDispatcher(
            http = { Result.success(emptyList()) },
            clock = { now },
            resolveShortId = { id -> if (id == userId) 123.toUShort() else null }
        )

        val fused = dispatcher.fuse(
            blePeers = listOf(
                BlePeerSnapshot(
                    shortId = 123u,
                    latitude = 10.4000,
                    longitude = -67.0000,
                    observedAtMillis = now
                )
            ),
            httpSamples = listOf(sample())
        )

        assertEquals(1, fused.size)
        assertEquals(TelemetrySource.BLE, fused.single().source)
        assertEquals(10.4, fused.single().latitude, 0.000001)
    }

    @Test
    fun ` HTTP sin equivalente BLE añade pin 4G con perfil redactado`() {
        val dispatcher = HybridTransportDispatcher(http = { Result.success(emptyList()) }, clock = { now })

        val fused = dispatcher.fuse(blePeers = emptyList(), httpSamples = listOf(sample()))

        assertEquals(1, fused.size)
        val pin = fused.single()
        assertEquals(TelemetrySource.HTTP_4G, pin.source)
        assertTrue(pin.profileRedacted)
        assertTrue(pin.shortId.toInt() >= 60_000)
        assertEquals(userId, pin.userId)
    }

    @Test
    fun ` descarta muestras HTTP vencidas y posiciones centinela`() {
        val dispatcher = HybridTransportDispatcher(http = { Result.success(emptyList()) }, clock = { now })

        val fused = dispatcher.fuse(
            blePeers = emptyList(),
            httpSamples = listOf(
                sample(id = "expirado", observedAt = now - 31_000L),
                sample(id = "centinela", lat = 0.0, lng = 0.0)
            )
        )

        assertTrue(fused.isEmpty())
    }

    @Test
    fun ` un fallo HTTP conserva la ultima muestra valida`() = runTest {
        var shouldFail = false
        val dispatcher = HybridTransportDispatcher(
            http = {
                if (shouldFail) Result.failure(IllegalStateException("sin red"))
                else Result.success(listOf(sample()))
            },
            clock = { now }
        )

        dispatcher.pollOnce("ride")
        shouldFail = true
        dispatcher.pollOnce("ride")

        assertEquals(1, dispatcher.httpSamples.value.size)
        val fused = dispatcher.fuse(blePeers = emptyList())
        assertEquals(TelemetrySource.HTTP_4G, fused.single().source)
        assertTrue(fused.single().profileRedacted)
    }
}
