package com.rutabikersync.app.core.announcer

import android.media.AudioManager
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.mesh.RideSessionController
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias JVM del Bloque 3.4 (`UniqueAnnouncer` + `TtsAnnouncer` + `AnnouncerTracker`):
 * 1. Convoy de 5 riders sobre `SimulatedBleMedium`: al perderse un rider, el nodo más cercano por saltos
 *    (`proximityRank = 0`) anuncia primero, los otros 3 nodos cancelan su anuncio pendiente y solo 1 habla.
 *    Verifica también el recordatorio único a los 60 s (máx. 2 anuncios) y el texto por relación (3.3 A y C).
 * 2. Desempate simultáneo (dos nodos anuncian a la vez -> gana el de menor ID y el mayor corta su TTS)
 *    y cancelación de anuncio pendiente cuando llega `PEER_BACK`.
 * 3. Pérdida repetida (3 veces en 10 min -> `"X tiene conexión inestable"`, Decisión 3.4 C), Ducking (3.2 B),
 *    fallback a Google TTS (3.1 C) y apagado de TTS al cerrar `RideService`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UniqueAnnouncerPhase3Test {

    private val secretKey = "rbs-phase3-announcer-secret-32b!".encodeToByteArray()

    @Test
    fun `Bloque 3_4 convoy de 5 riders elige un unico anunciador por cercania y repite solo 1 vez a los 60s`() = runTest(StandardTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val nodeIds = listOf<UShort>(1u, 2u, 3u, 4u, 5u)
        medium.connectChain(nodeIds)

        val engines = nodeIds.associateWith { id ->
            MeshRelayEngine(
                localShortId = id,
                transport = medium.createNodeTransport(id),
                rideSecretKey = secretKey
            ).also { it.start(backgroundScope) }
        }

        val announcers = listOf<UShort>(1u, 2u, 3u, 4u).associateWith { id ->
            UniqueAnnouncer(
                scope = backgroundScope,
                localShortId = id,
                meshEngine = engines.getValue(id),
                randomFractionProvider = { _, _ -> 0.20 } // 200 ms base jitter
            ).also { it.start() }
        }
        runCurrent()

        // El Rider 5 (ALLIED_STRANGER en el extremo del convoy) emite su heartbeat inicial en t = 1_000 ms
        engines.getValue(5u).emitLocalPosition(19.4200, -99.1332, 70, 180f, 85, ttl = 4)
        runCurrent()

        // Verificar que todos los nodos (1..4) recibieron el paquete del Rider 5 con saltos crecientes:
        // Nodo 4 está a 1 salto (proximityRank = 0 -> delay = 200 ms)
        // Nodo 3 está a 2 saltos (proximityRank = 1 -> delay = 700 ms)
        // Nodo 2 está a 3 saltos (proximityRank = 2 -> delay = 1200 ms)
        // Nodo 1 está a 4 saltos (proximityRank = 3 -> delay = 1700 ms)
        assertEquals(1, engines.getValue(4u).peers.value.getValue(5u).lastHops)
        assertEquals(2, engines.getValue(3u).peers.value.getValue(5u).lastHops)
        assertEquals(3, engines.getValue(2u).peers.value.getValue(5u).lastHops)
        assertEquals(4, engines.getValue(1u).peers.value.getValue(5u).lastHops)

        // Simular silencio superior al timeout derivado -> los nodos 1, 2, 3 y 4 detectan PEER_LOST(5u) a la vez
        val lossTimeMs = System.currentTimeMillis() + MeshRelayEngine.LOST_TIMEOUT_MS
        for (id in listOf<UShort>(1u, 2u, 3u, 4u)) {
            engines.getValue(id).evaluatePeerTimeouts(lossTimeMs)
        }
        runCurrent()

        // Avanzar 250 ms: el Nodo 4 (más cercano, retardo = 200 ms) vence primero, emite ANNOUNCED y habla por TTS
        advanceTimeBy(250L)
        runCurrent()

        // Avanzar otros 3_000 ms: los nodos 1, 2 y 3 ya recibieron ANNOUNCED del Nodo 4, así que callan
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(1, announcers.getValue(4u).ttsAnnouncer.spokenHistory.size)
        assertEquals(0, announcers.getValue(3u).ttsAnnouncer.spokenHistory.size)
        assertEquals(0, announcers.getValue(2u).ttsAnnouncer.spokenHistory.size)
        assertEquals(0, announcers.getValue(1u).ttsAnnouncer.spokenHistory.size)

        // Decisión 3.3 (C): Rider 5 es ALLIED_STRANGER -> dice "Rider aliado #5 salió de la mesh"
        val firstUtterance = announcers.getValue(4u).ttsAnnouncer.spokenHistory.first()
        assertEquals("Rider aliado #5 salió de la mesh", firstUtterance.text)
        assertTrue(firstUtterance.usedDuckingAudioFocus)

        // Si el Rider 5 sigue perdido a los 60 s, el Nodo 4 repite el anuncio una sola vez (contador llega a 2)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, announcers.getValue(4u).ttsAnnouncer.spokenHistory.size)
        assertEquals(2, announcers.getValue(4u).getAnnouncementCount(firstUtterance.eventId))

        // Aunque pasen otros 120 s más, nunca supera 2 anuncios por evento
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(2, announcers.getValue(4u).ttsAnnouncer.spokenHistory.size)

        announcers.values.forEach { it.cancelAllAndStop() }
        engines.values.forEach { it.stop() }
    }

    @Test
    fun `Bloque 3_4 dos nodos anunciando a la vez gana el de menor ID y PEER_BACK cancela anuncio pendiente`() = runTest(StandardTestDispatcher()) {
        val medium = SimulatedBleMedium()
        medium.connectChain(listOf(2u, 3u))

        val engine2 = MeshRelayEngine(2u, medium.createNodeTransport(2u), secretKey).also { it.start(backgroundScope) }
        val engine3 = MeshRelayEngine(3u, medium.createNodeTransport(3u), secretKey).also { it.start(backgroundScope) }

        // Configurar el retardo para que el Nodo 2 y el Nodo 3 empaten exactamente en 500 ms
        // Nodo 3 tiene proximityRank = 0 (0 ms) + random 0.50 (500 ms) = 500 ms
        // Nodo 2 tiene proximityRank = 1 (500 ms) + random 0.00 (0 ms) = 500 ms
        val announcer2 = UniqueAnnouncer(
            scope = backgroundScope,
            localShortId = 2u,
            meshEngine = engine2,
            randomFractionProvider = { _, _ -> 0.0 }
        ).also { it.start() }

        val announcer3 = UniqueAnnouncer(
            scope = backgroundScope,
            localShortId = 3u,
            meshEngine = engine3,
            randomFractionProvider = { _, _ -> 0.5 }
        ).also { it.start() }
        runCurrent()

        val eventId = AnnouncerTracker.buildEventId(AnnouncerEventKind.PEER_LOST, 4u, 1)

        // Ambos detectan PEER_LOST(4u) con retardos idénticos de 500 ms -> anuncian a la vez
        announcer3.scheduleOrHandleTransition(
            PeerConnectivityTransition(
                subjectShortId = 4u,
                kind = AnnouncerEventKind.PEER_LOST,
                eventCycle = 1,
                eventId = eventId,
                proximityRank = 0,
                lossCountInWindow = 1
            )
        )
        announcer2.scheduleOrHandleTransition(
            PeerConnectivityTransition(
                subjectShortId = 4u,
                kind = AnnouncerEventKind.PEER_LOST,
                eventCycle = 1,
                eventId = eventId,
                proximityRank = 1,
                lossCountInWindow = 1
            )
        )

        advanceTimeBy(500L)
        runCurrent()

        // Gana el de menor ID (2u) y el Nodo 3 (3u > 2u) corta su TTS en cuanto recibe ANNOUNCED de 2u
        assertEquals(2u.toUShort(), announcer2.getWinnerForEvent(eventId))
        assertEquals(2u.toUShort(), announcer3.getWinnerForEvent(eventId))
        assertFalse(announcer2.ttsAnnouncer.spokenHistory.last().wasInterruptedByTieBreak)
        if (announcer3.ttsAnnouncer.spokenHistory.isNotEmpty()) {
            assertTrue(announcer3.ttsAnnouncer.spokenHistory.last().wasInterruptedByTieBreak)
        }

        // Ahora probar cancelación por PEER_BACK antes de que venza el retardo de PEER_LOST:
        val lossEvent2 = AnnouncerTracker.buildEventId(AnnouncerEventKind.PEER_LOST, 2u, 2)
        announcer3.scheduleOrHandleTransition(
            PeerConnectivityTransition(
                subjectShortId = 2u,
                kind = AnnouncerEventKind.PEER_LOST,
                eventCycle = 2,
                eventId = lossEvent2,
                proximityRank = 1, // retardo = 500 + 500 = 1000 ms
                lossCountInWindow = 1
            )
        )
        advanceTimeBy(300L) // Aún faltan 700 ms para anunciar la pérdida

        // Llega PEER_BACK(2u) -> cancela el anuncio pendiente de pérdida y anuncia reconexión
        val backEvent = AnnouncerTracker.buildEventId(AnnouncerEventKind.PEER_BACK, 2u, 1)
        announcer3.scheduleOrHandleTransition(
            PeerConnectivityTransition(
                subjectShortId = 2u,
                kind = AnnouncerEventKind.PEER_BACK,
                eventCycle = 1,
                eventId = backEvent,
                proximityRank = 0,
                lossCountInWindow = 1
            )
        )
        advanceTimeBy(1_500L)
        runCurrent()

        // Verificar que lossEvent2 fue cancelado y en su lugar se dijo "Carlos Vega se reconectó" (Decisión 3.3 A)
        assertTrue(announcer3.ttsAnnouncer.spokenHistory.none { it.eventId == lossEvent2 })
        assertEquals("Carlos Vega se reconectó", announcer3.ttsAnnouncer.spokenHistory.last().text)

        announcer2.cancelAllAndStop()
        announcer3.cancelAllAndStop()
        engine2.stop()
        engine3.stop()
    }

    @Test
    fun `Bloque 3_4 perdida repetida 3 veces en 10 min dice conexion inestable una sola vez y RideService apaga TTS al cerrar`() = runTest(StandardTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val engine1 = MeshRelayEngine(1u, medium.createNodeTransport(1u), secretKey).also { it.start(backgroundScope) }

        val announcer1 = UniqueAnnouncer(
            scope = backgroundScope,
            localShortId = 1u,
            meshEngine = engine1,
            randomFractionProvider = { _, _ -> 0.1 }
        ).also { it.start() }
        runCurrent()

        fun buildPeerSnapshot(isLost: Boolean, seenMs: Long): Map<UShort, MeshRelayEngine.PeerTrack> = mapOf(
            2u.toUShort() to MeshRelayEngine.PeerTrack(
                shortId = 2u,
                lastLatitude = 19.4310,
                lastLongitude = -99.1332,
                lastSpeedKmh = 70,
                lastBatteryPercent = 85,
                isSos = false,
                lastSeenEpochMs = seenMs,
                lastSequence = 1,
                expectedBeats = 1,
                receivedBeats = 1,
                consecutiveAfterLossBeats = 0,
                isLost = isLost,
                lastHops = 1
            )
        )

        // Estado inicial conectado en t = 1_000 ms
        announcer1.processPeerSnapshotAt(buildPeerSnapshot(isLost = false, seenMs = 1_000L), nowEpochMs = 1_000L)

        // 1.ª pérdida en t = 20_000 ms -> "Carlos Vega salió de la mesh"
        announcer1.processPeerSnapshotAt(buildPeerSnapshot(isLost = true, seenMs = 1_000L), nowEpochMs = 20_000L)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals("Carlos Vega salió de la mesh", announcer1.ttsAnnouncer.spokenHistory.last().text)

        // Reconecta en t = 40_000 ms y sufre 2.ª pérdida en t = 90_000 ms -> "Carlos Vega salió de la mesh"
        announcer1.processPeerSnapshotAt(buildPeerSnapshot(isLost = false, seenMs = 40_000L), nowEpochMs = 40_000L)
        advanceTimeBy(300L)
        runCurrent()
        announcer1.processPeerSnapshotAt(buildPeerSnapshot(isLost = true, seenMs = 40_000L), nowEpochMs = 90_000L)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals("Carlos Vega salió de la mesh", announcer1.ttsAnnouncer.spokenHistory.last().text)

        // Reconecta en t = 120_000 ms y sufre 3.ª pérdida en t = 180_000 ms (dentro de los 10 min) ->
        // Decisión 3.4 (C): Agrupa en "Carlos Vega tiene conexión inestable"
        announcer1.processPeerSnapshotAt(buildPeerSnapshot(isLost = false, seenMs = 120_000L), nowEpochMs = 120_000L)
        advanceTimeBy(300L)
        runCurrent()
        val transitions3 = announcer1.processPeerSnapshotAt(buildPeerSnapshot(isLost = true, seenMs = 120_000L), nowEpochMs = 180_000L)
        assertEquals(1, transitions3.size)
        assertEquals(AnnouncerEventKind.UNSTABLE_CONNECTION, transitions3.first().kind)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals("Carlos Vega tiene conexión inestable", announcer1.ttsAnnouncer.spokenHistory.last().text)

        val countAfterUnstable = announcer1.ttsAnnouncer.spokenHistory.size

        // 4.ª pérdida dentro de los mismos 10 min (t = 240_000 ms) -> NO vuelve a anunciar para no saturar
        announcer1.processPeerSnapshotAt(buildPeerSnapshot(isLost = false, seenMs = 210_000L), nowEpochMs = 210_000L)
        val transitions4 = announcer1.processPeerSnapshotAt(buildPeerSnapshot(isLost = true, seenMs = 210_000L), nowEpochMs = 240_000L)
        assertTrue(transitions4.isEmpty())
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(countAfterUnstable, announcer1.ttsAnnouncer.spokenHistory.size)

        // Verificar constantes de Decisión 3.1 (C) y Decisión 3.2 (B) e integración con RideSessionController
        assertEquals("com.google.android.tts", TtsAnnouncer.GOOGLE_TTS_PACKAGE)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, TtsAnnouncer.AUDIO_FOCUS_MODE_DUCKING)

        val rideController = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null },
            simulatedMedium = medium
        )
        rideController.startRide(
            rideId = "ride-f3-tts-lifecycle",
            localShortId = 9u,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )
        assertNotNull(rideController.uniqueAnnouncer)
        val stopped = rideController.stopAll()
        assertTrue(stopped.ttsShutdownOnClose)

        announcer1.cancelAllAndStop()
        engine1.stop()
    }
}
