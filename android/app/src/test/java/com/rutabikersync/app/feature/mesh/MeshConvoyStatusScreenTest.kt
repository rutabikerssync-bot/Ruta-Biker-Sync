package com.rutabikersync.app.feature.mesh

import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.text.FriendNodeBuffer
import com.rutabikersync.app.core.mesh.text.TextMessageCrypto
import com.rutabikersync.app.core.mesh.text.TextMessagePacket
import org.junit.Assert.*
import org.junit.Test

/**
 * Pruebas unitarias para MeshConvoyStatusScreen y la supervisión de radiofrecuencia (Fase 7.0 · Hito 7.0.2).
 * Valida niveles de barras RSSI, estados de nodo, formato de saltos y roles, semáforo global y empty state.
 */
class MeshConvoyStatusScreenTest {

    @Test
    fun testNivelDeBarrasRssi() {
        // Nivel Excelente (4 barras): >= -65 dBm
        assertEquals(RssiSignalLevel.EXCELLENT, RssiSignalLevel.fromDbm(-50))
        assertEquals(RssiSignalLevel.EXCELLENT, RssiSignalLevel.fromDbm(-65))
        assertEquals(4, RssiSignalLevel.fromDbm(-55).bars)

        // Nivel Bueno (3 barras): >= -75 dBm
        assertEquals(RssiSignalLevel.GOOD, RssiSignalLevel.fromDbm(-66))
        assertEquals(RssiSignalLevel.GOOD, RssiSignalLevel.fromDbm(-75))
        assertEquals(3, RssiSignalLevel.fromDbm(-70).bars)

        // Nivel Regular (2 barras): >= -85 dBm
        assertEquals(RssiSignalLevel.FAIR, RssiSignalLevel.fromDbm(-76))
        assertEquals(RssiSignalLevel.FAIR, RssiSignalLevel.fromDbm(-85))
        assertEquals(2, RssiSignalLevel.fromDbm(-80).bars)

        // Nivel Crítico (1 barra): < -85 dBm
        assertEquals(RssiSignalLevel.CRITICAL, RssiSignalLevel.fromDbm(-86))
        assertEquals(RssiSignalLevel.CRITICAL, RssiSignalLevel.fromDbm(-95))
        assertEquals(1, RssiSignalLevel.fromDbm(-90).bars)
    }

    @Test
    fun testEstadosDeNodo() {
        // 1. Nodo Amigo con mensajes en cola
        val friendWithQueue = MeshPeerLiveState(
            shortId = 2u,
            name = "Halcón",
            roleInConvoy = "Capitán",
            rssiDbm = -60,
            hopCount = 0,
            batteryPercent = 80,
            isFriendNode = true,
            pendingMessagesCount = 3
        )
        assertTrue("Debe identificarse como Nodo Amigo", friendWithQueue.isFriendNode)
        assertEquals(3, friendWithQueue.pendingMessagesCount)

        // 2. Nodo Amigo sin mensajes en cola
        val friendEmptyQueue = friendWithQueue.copy(pendingMessagesCount = 0)
        assertTrue(friendEmptyQueue.isFriendNode)
        assertEquals(0, friendEmptyQueue.pendingMessagesCount)

        // 3. Nodo LPN con batería baja
        val lpnNode = MeshPeerLiveState(
            shortId = 4u,
            name = "Sargento",
            roleInConvoy = "Sweep",
            rssiDbm = -85,
            hopCount = 2,
            batteryPercent = 10,
            isLpn = true
        )
        assertTrue("Debe identificarse como nodo LPN", lpnNode.isLpn)

        // 4. Nodo normal en línea
        val onlineNode = MeshPeerLiveState(
            shortId = 3u,
            name = "Lobo",
            roleInConvoy = "Rider",
            rssiDbm = -70,
            hopCount = 1,
            batteryPercent = 60,
            isLpn = false,
            isFriendNode = false
        )
        assertFalse(onlineNode.isLpn)
        assertFalse(onlineNode.isFriendNode)
    }

    @Test
    fun testFormatoDeSaltosYRoles() {
        // Formato de saltos
        assertEquals("Directo", formatHopCount(0))
        assertEquals("1 salto", formatHopCount(1))
        assertEquals("2 saltos", formatHopCount(2))
        assertEquals("4 saltos", formatHopCount(4))

        // Formato canónico de roles
        assertEquals("Punta", formatConvoyRole("Punta"))
        assertEquals("Punta", formatConvoyRole("Líder"))
        assertEquals("Punta", formatConvoyRole("Capitán de Ruta"))
        assertEquals("Scout", formatConvoyRole("Scout"))
        assertEquals("Sweep", formatConvoyRole("Sweep"))
        assertEquals("Sweep", formatConvoyRole("Sargento"))
        assertEquals("Sweep", formatConvoyRole("Cola"))
        assertEquals("Rider", formatConvoyRole("Rider"))
        assertEquals("Rider", formatConvoyRole("Cualquiera"))
    }

    @Test
    fun testSemaforoGlobal() {
        // 1. Malla Activa: Varios peers con buen RSSI y bajo PLR
        val healthyPeers = listOf(
            MeshPeerLiveState(shortId = 2u, name = "A", roleInConvoy = "Punta", rssiDbm = -60, hopCount = 0, batteryPercent = 90, plrPercent = 0.5),
            MeshPeerLiveState(shortId = 3u, name = "B", roleInConvoy = "Scout", rssiDbm = -70, hopCount = 1, batteryPercent = 70, plrPercent = 1.2)
        )
        assertEquals(GlobalMeshStatus.ACTIVE, GlobalMeshStatus.evaluate(healthyPeers))

        // 2. Malla Débil: RSSI degradado o PLR alto (> 8%)
        val weakPeers = listOf(
            MeshPeerLiveState(shortId = 2u, name = "A", roleInConvoy = "Punta", rssiDbm = -85, hopCount = 2, batteryPercent = 20, plrPercent = 9.5),
            MeshPeerLiveState(shortId = 3u, name = "B", roleInConvoy = "Rider", rssiDbm = -88, hopCount = 3, batteryPercent = 15, plrPercent = 12.0)
        )
        assertEquals(GlobalMeshStatus.WEAK, GlobalMeshStatus.evaluate(weakPeers))

        // 3. Malla Caída: Sin peers
        assertEquals(GlobalMeshStatus.DOWN, GlobalMeshStatus.evaluate(emptyList()))
    }

    @Test
    fun testEmptyState() {
        val emptyPeers = emptyList<MeshPeerLiveState>()
        val status = GlobalMeshStatus.evaluate(emptyPeers)
        assertEquals("Malla caída", status.label)
        assertEquals("🔴", status.emoji)
        assertTrue("El conjunto de peers debe estar vacío", emptyPeers.isEmpty())
    }

    @Test
    fun testPendingMessageCountEnFriendNodeBuffer() {
        val buffer = FriendNodeBuffer()
        val peerId: UShort = 42u

        // Inicialmente 0 mensajes
        assertEquals(0, buffer.getPendingMessageCount(peerId))

        // Almacenar mensaje unicast cifrado
        val header = MeshHeader(
            clubNetworkId = 0x52425301u,
            senderShortId = 1u,
            packetType = MeshHeader.TYPE_TEXT_MESSAGE,
            initTtl = 5,
            currentTtl = 5,
            sequenceNum = 101u
        )
        val packet = TextMessageCrypto.encrypt(
            key = ByteArray(32) { 0x01 },
            plaintext = "Prueba de conteo de mensajes pendientes",
            header = header,
            targetShortId = peerId
        )
        assertTrue(buffer.storeMessage(packet))

        // Ahora el conteo debe ser 1
        assertEquals(1, buffer.getPendingMessageCount(peerId))

        // Drenar el mensaje
        val drained = buffer.drainForPeer(peerId)
        assertEquals(1, drained.size)
        assertEquals(0, buffer.getPendingMessageCount(peerId))
    }

    @Test
    fun testMapeoPeersVacios() {
        val mapped = mapPeerTracksToMeshPeerLiveStates(emptyMap(), nowEpochMs = 1_700_000_000_000L)
        assertTrue("Sin peers reales el monitor debe mostrar estado vacío", mapped.isEmpty())
        assertEquals(GlobalMeshStatus.DOWN, GlobalMeshStatus.evaluate(mapped))
    }

    @Test
    fun testMapeoPeerSimuladoConDatosRealesDisponibles() {
        val now = 1_700_000_100_000L
        val track = com.rutabikersync.app.core.mesh.MeshRelayEngine.PeerTrack(
            shortId = 7u,
            lastLatitude = 19.4326,
            lastLongitude = -99.1332,
            lastSpeedKmh = 42,
            lastBatteryPercent = 73,
            isSos = false,
            lastSeenEpochMs = now - 12_000L,
            firstSequence = 10,
            lastSequence = 19,
            expectedBeats = 20,
            receivedBeats = 15,
            consecutiveAfterLossBeats = 0,
            isLost = false,
            lastHops = 2
        )

        val peer = mapPeerTrackToMeshPeerLiveState(track, nowEpochMs = now)

        assertEquals(7.toUShort(), peer.shortId)
        assertEquals(2.toByte(), peer.hopCount)
        assertEquals(73, peer.batteryPercent)
        assertEquals(12, peer.lastSeenSecondsAgo)
        assertEquals(20, peer.expectedBeats)
        assertEquals(15, peer.receivedBeats)
        assertEquals(25.0, peer.plrPercent!!, 0.001)

        // Campos no expuestos por PeerTrack: no inventar valores
        assertEquals("No disponible", peer.name)
        assertEquals("No disponible", peer.roleInConvoy)
        assertNull(peer.rssiDbm)

        assertEquals(GlobalMeshStatus.WEAK, GlobalMeshStatus.evaluate(listOf(peer)))
        assertEquals("No disponible", formatConvoyRole(peer.roleInConvoy))
        assertNull(RssiSignalLevel.fromDbmOrNull(peer.rssiDbm))
    }

    @Test
    fun testMapeoPropagaRssiRealDelStack() {
        val now = 1_700_000_100_000L
        val track = com.rutabikersync.app.core.mesh.MeshRelayEngine.PeerTrack(
            shortId = 9u,
            lastLatitude = 19.3,
            lastLongitude = -99.1,
            lastSpeedKmh = 0,
            lastBatteryPercent = 87,
            isSos = false,
            lastSeenEpochMs = now - 3_000L,
            firstSequence = 1,
            lastSequence = 40,
            expectedBeats = 40,
            receivedBeats = 38,
            consecutiveAfterLossBeats = 0,
            isLost = false,
            lastHops = 1,
            lastRssiDbm = -45
        )

        val peer = mapPeerTrackToMeshPeerLiveState(track, nowEpochMs = now)

        assertEquals(-45, peer.rssiDbm)
        assertEquals(RssiSignalLevel.EXCELLENT, RssiSignalLevel.fromDbmOrNull(peer.rssiDbm))
        assertEquals(4, RssiSignalLevel.fromDbm(peer.rssiDbm!!).bars)
    }
}
