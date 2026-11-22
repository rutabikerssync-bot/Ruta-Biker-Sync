package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.audio.ConvoySignalKind
import com.rutabikersync.app.core.mesh.text.TacticalChatMessage
import com.rutabikersync.app.core.mesh.text.TacticalChatStore
import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HybridChatDispatcherTest {

    private val now = 1_800_000_000_000L
    private val rideId = "2de260aa-3e24-493b-b9b8-0846366ba1c5"

    @Before
    fun limpiarStore() {
        TacticalChatStore.purgeR12()
    }

    private fun commsItem(
        id: String = "http-1",
        kind: String = "chat",
        sender: UShort = 129u,
        text: String? = "llamada",
        signalKind: Int? = null,
        sequence: Int? = null,
        createdAt: Long = now - 1_000L
    ) = ClubApiClient.RideCommsItem(
        id = id,
        kind = kind,
        senderShortId = sender,
        targetShortId = 65_535u,
        text = text,
        signalKind = signalKind,
        sequence = sequence,
        createdAtMillis = createdAt
    )

    private fun dispatcher(
        fetch: suspend (String, Long) -> Result<List<ClubApiClient.RideCommsItem>> =
            { _, _ -> Result.success(emptyList()) },
        post: suspend (String, ClubApiClient.RideCommsDraft) -> Result<ClubApiClient.ApiResponse> =
            { _, _ -> Result.success(ClubApiClient.ApiResponse(200, null)) },
        onSignal: (MeshRelayEngine.IncomingConvoySignal) -> Unit = {},
        pollDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
        pollIntervalMs: Long = 5_000L
    ) = HybridChatDispatcher(
        selfShortId = 2u,
        fetch = fetch,
        post = post,
        clock = { now },
        pollDispatcher = pollDispatcher,
        pollIntervalMs = pollIntervalMs,
        onSignal = onSignal
    )

    @Test
    fun `mensaje BLE y HTTP identico se deduplica`() {
        val dispatcher = dispatcher()

        TacticalChatStore.addMessage(
            TacticalChatMessage(
                id = "ble-1",
                senderShortId = 129u,
                senderName = "Rider #129",
                text = "llamada",
                isOutgoing = false,
                timestampMs = now
            )
        )

        assertTrue(dispatcher.deliver(commsItem(), now))
        assertEquals(1, TacticalChatStore.messages.value.size)

        // El mismo messageId repetido tampoco entra (LRU de 10 s del despachador).
        assertFalse(dispatcher.deliver(commsItem(), now))
        assertEquals(1, TacticalChatStore.messages.value.size)
    }

    @Test
    fun `poll de 5 s se programa sin bloquear el hilo llamante`() = runTest {
        var calls = 0
        val dispatcher = dispatcher(
            fetch = { _, _ ->
                calls++
                Result.success(emptyList())
            },
            pollDispatcher = StandardTestDispatcher(testScheduler),
            pollIntervalMs = 5_000L
        )

        dispatcher.start(rideId, this)
        assertEquals(0, calls) // start() no hace red de forma síncrona

        testScheduler.advanceTimeBy(4_999L)
        assertEquals(1, calls)

        testScheduler.advanceTimeBy(2L)
        assertEquals(2, calls)

        dispatcher.stop()
    }

    @Test
    fun `etiquetado 4G aparece en el mensaje entrante`() {
        val dispatcher = dispatcher()

        assertTrue(dispatcher.deliver(commsItem(id = "x", sender = 777u, text = "vía libre"), now))

        val message = TacticalChatStore.messages.value.single()
        assertEquals("Piloto #777 [4G]", message.senderName)
        assertTrue(message.senderName.contains("[4G]"))
        assertFalse(message.isOutgoing)
        assertEquals("vía libre", message.text)
    }

    @Test
    fun `senal HTTP se publica en el flow de convoy sin tocar chat`() {
        var received: MeshRelayEngine.IncomingConvoySignal? = null
        val dispatcher = dispatcher(onSignal = { received = it })

        assertTrue(
            dispatcher.deliver(
                commsItem(
                    id = "signal-1",
                    kind = "signal",
                    text = null,
                    signalKind = 2,
                    sequence = 7
                ),
                now
            )
        )

        assertEquals(ConvoySignalKind.REDUCE_SPEED, received?.signalKind)
        assertEquals(7, received?.sequenceNumber)
        assertTrue(TacticalChatStore.messages.value.isEmpty())
    }
}
