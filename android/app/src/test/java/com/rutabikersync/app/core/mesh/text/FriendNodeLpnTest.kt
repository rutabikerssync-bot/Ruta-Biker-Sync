package com.rutabikersync.app.core.mesh.text

import com.rutabikersync.app.core.mesh.HeartbeatFrame
import com.rutabikersync.app.core.mesh.LowPowerNodeMode
import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto
import com.rutabikersync.app.core.mesh.sos.InMemorySosPublicKeyResolver
import com.rutabikersync.app.core.mesh.sos.SosPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * Suite de Pruebas Unitarias para Nodos Amigos (Store & Forward) y Nodos LPN (Fase 6 · Hito 5 · Etapa 2).
 *
 * Valida:
 * 1. Búfer circular de 256 KB en RAM con retención Zero-Knowledge.
 * 2. Drenaje atómico por peer al descubrir reaparición en rango.
 * 3. Expiración estricta de mensajes por TTL (30 minutos) con sanitización de memoria (R12).
 * 4. Saturación y rechazo elegante ante desbordamiento de 256 KB.
 * 5. Rechazo de almacenamiento para mensajes broadcast (solo unicast táctico).
 * 6. Simulación Store & Forward: N1 emite a N3 ausente -> N2 (Nodo Amigo) almacena -> N3 reaparece -> N2 descarga -> N3 descifra.
 * 7. LPN: Supresión de retransmisión de Heartbeats y PTT Voice ajenos.
 * 8. LPN: Supresión de retransmisión de mensajes de texto de terceros.
 * 9. LPN: Recepción y descifrado de mensajes dirigidos al nodo local.
 * 10. LPN: Despertar forzado y retransmisión de alertas SOS con prioridad absoluta.
 * 11. LPN: Activación automática por batería <= 15% y control manual del usuario.
 * 12. LPN: Cálculo del ciclo de temporización de radio (200 ms activo / 4.800 ms reposo).
 */
class FriendNodeLpnTest {

    private val testRideKey = ByteArray(32) { (it + 5).toByte() }
    private val testNetworkId: UInt = 0x52425301u

    private fun createHeader(
        sender: UShort = 1u,
        seq: UShort = 10u,
        ttl: Int = 5,
        type: Int = MeshHeader.TYPE_TEXT_MESSAGE
    ): MeshHeader = MeshHeader(
        clubNetworkId = testNetworkId,
        senderShortId = sender,
        packetType = type,
        initTtl = 5,
        currentTtl = ttl,
        sequenceNum = seq
    )

    @Test
    fun testFriendNodeBufferStoreAndDrain() {
        val buffer = FriendNodeBuffer(maxCapacityBytes = 256 * 1024)
        val header = createHeader(sender = 1u, seq = 101u)
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Esperando en el cruce de montaña",
            header = header,
            targetShortId = 3u // Unicast para nodo 3
        )

        assertFalse(buffer.hasMessagesFor(3u))
        val stored = buffer.storeMessage(packet, nowMs = 1000L)
        assertTrue("El mensaje unicast debe ser almacenado en el búfer", stored)
        assertTrue(buffer.hasMessagesFor(3u))
        assertEquals(1, buffer.messageCount())
        assertTrue(buffer.currentSizeBytes() > 0)

        // Drenar mensajes para el nodo 3
        val drained = buffer.drainForPeer(3u)
        assertEquals(1, drained.size)
        assertEquals(packet, drained[0])
        assertFalse(buffer.hasMessagesFor(3u))
        assertEquals(0, buffer.messageCount())
        assertEquals(0, buffer.currentSizeBytes())
    }

    @Test
    fun testFriendNodeBufferRejectsBroadcast() {
        val buffer = FriendNodeBuffer()
        val header = createHeader(sender = 1u)
        val broadcastPacket = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Aviso a todos los pilotos",
            header = header,
            targetShortId = TextMessagePacket.BROADCAST_TARGET
        )

        val stored = buffer.storeMessage(broadcastPacket)
        assertFalse("Los mensajes broadcast no deben almacenarse en Nodos Amigos", stored)
        assertEquals(0, buffer.messageCount())
    }

    @Test
    fun testFriendNodeBufferTtlExpiration() {
        val ttlMs = 30 * 60 * 1000L // 30 minutos
        val buffer = FriendNodeBuffer(messageTtlMs = ttlMs)
        val header = createHeader(sender = 1u)
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje con tiempo de vida finito",
            header = header,
            targetShortId = 4u
        )

        val t0 = 10_000L
        buffer.storeMessage(packet, nowMs = t0)
        assertEquals(1, buffer.messageCount())

        // Consultar purga a los 29 minutos (aún válido)
        val purged29Min = buffer.purgeExpired(nowMs = t0 + 29 * 60 * 1000L)
        assertEquals(0, purged29Min)
        assertEquals(1, buffer.messageCount())

        // Consultar purga a los 31 minutos (expirado)
        val purged31Min = buffer.purgeExpired(nowMs = t0 + 31 * 60 * 1000L)
        assertEquals(1, purged31Min)
        assertEquals(0, buffer.messageCount())
        assertEquals(0, buffer.currentSizeBytes())
    }

    @Test
    fun testFriendNodeBufferMaxCapacity256KbRejection() {
        // Búfer configurado con capacidad miniatura de 180 bytes (caben exactamente 2 paquetes de ~76B, el 3ro desborda)
        val tinyBuffer = FriendNodeBuffer(maxCapacityBytes = 180)

        val packet1 = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje número 1 que ocupa espacio",
            header = createHeader(seq = 1u),
            targetShortId = 5u
        )
        val packet2 = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje número 2 que ocupa espacio",
            header = createHeader(seq = 2u),
            targetShortId = 5u
        )
        val packet3 = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje número 3 que desborda el búfer",
            header = createHeader(seq = 3u),
            targetShortId = 5u
        )

        assertTrue(tinyBuffer.storeMessage(packet1))
        assertTrue(tinyBuffer.storeMessage(packet2))
        assertFalse("El tercer paquete debe rechazarse por capacidad excedida", tinyBuffer.storeMessage(packet3))
    }

    @Test
    fun testFriendNodeBufferClearAllZeroesMemory() {
        val buffer = FriendNodeBuffer()
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Texto confidencial de prueba",
            header = createHeader(),
            targetShortId = 2u
        )

        buffer.storeMessage(packet)
        assertEquals(1, buffer.messageCount())

        buffer.clearAll()
        assertEquals(0, buffer.messageCount())
        assertEquals(0, buffer.currentSizeBytes())
        assertFalse(buffer.hasMessagesFor(2u))
    }

    @Test
    fun testStoreAndForwardSimulation3Nodes() {
        // Escenario: N1 (Emisor) emite mensaje unicast para N3 (Destinatario fuera de rango).
        // N2 actúa como Nodo Amigo con FriendNodeBuffer habilitado.
        // N3 reaparece en el convoy y emite Heartbeat; N2 detecta a N3 y le descarga el mensaje en Store & Forward.

        var n2TransmittedBytes: ByteArray? = null
        val n2FriendBuffer = FriendNodeBuffer()

        val node2 = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId,
            onTransmitRelay = { bytes -> n2TransmittedBytes = bytes }
        ,
        requireAuthorization = false
        ).apply {
            customRideSecretKey = testRideKey
            isFriendNodeEnabled = true
            friendNodeBuffer = n2FriendBuffer
        }

        var n3ReceivedText: String? = null
        val node3 = MeshRelayEngine(
            localShortId = 3u,
            clubNetworkId = testNetworkId
        ,
        requireAuthorization = false
        ).apply {
            customRideSecretKey = testRideKey
            onTextMessageReceived = { _, text -> n3ReceivedText = text }
        }

        // 1. N1 emite un mensaje unicast para N3
        val n1Packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Gasolinera en 5 km, reagrupemos",
            header = createHeader(sender = 1u, seq = 50u, ttl = 5),
            targetShortId = 3u
        )

        // 2. N2 (Nodo Amigo) recibe el paquete. Como N3 no está en rango directo, lo guarda en su búfer
        val decision = node2.processIncomingPacket(n1Packet.toByteArray())
        assertTrue("N2 debe procesar el paquete", decision is MeshRelayEngine.RelayDecision.Relayed)
        assertTrue("N2 debe retener el mensaje en su búfer para N3", n2FriendBuffer.hasMessagesFor(3u))
        assertEquals(1, n2FriendBuffer.messageCount())

        // 3. N3 reaparece en el convoy. N2 lo descubre mediante onPeerDiscovered
        val drainedPackets = node2.onPeerDiscovered(discoveredShortId = 3u)
        assertEquals(1, drainedPackets.size)
        assertNotNull("N2 debe transmitir el mensaje descargado por radio", n2TransmittedBytes)
        assertEquals(0, n2FriendBuffer.messageCount())

        // 4. N3 recibe la descarga de Store & Forward de N2
        val n3Decision = node3.processIncomingPacket(n2TransmittedBytes!!)
        assertTrue("N3 debe consumir localmente el mensaje por ser el destinatario", n3Decision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)
        assertEquals("Gasolinera en 5 km, reagrupemos", n3ReceivedText)
    }

    @Test
    fun testLpnSuppressesHeartbeatAndPttRelay() {
        val lpn = LowPowerNodeMode(batteryPercent = 10) // Batería crítica: LPN activo
        assertTrue(lpn.isLpnActive)

        var relayedBytes: ByteArray? = null
        val node = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId,
            onTransmitRelay = { bytes -> relayedBytes = bytes }
        ,
        requireAuthorization = false
        ).apply {
            lpnMode = lpn
        }

        // Simular recepción de un HeartbeatFrame ajeno con TTL=5
        val heartbeatHeader = createHeader(sender = 1u, type = MeshHeader.TYPE_HEARTBEAT, ttl = 5)
        val heartbeatBytes = ByteArray(24)
        System.arraycopy(heartbeatHeader.toByteArray(), 0, heartbeatBytes, 0, 9)

        val decision = node.processIncomingPacket(heartbeatBytes)
        assertTrue("LPN no debe retransmitir heartbeats ajenos", decision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)
        assertNull("No debe haber emisión de relay en radio", relayedBytes)
    }

    @Test
    fun testLpnSuppressesThirdPartyTextRelay() {
        val lpn = LowPowerNodeMode(batteryPercent = 8)
        var relayedBytes: ByteArray? = null

        val node = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId,
            onTransmitRelay = { bytes -> relayedBytes = bytes }
        ,
        requireAuthorization = false
        ).apply {
            lpnMode = lpn
            customRideSecretKey = testRideKey
        }

        // Mensaje de texto unicast de N1 para N3 (N2 es LPN intermedio)
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje ajeno a N2",
            header = createHeader(sender = 1u, ttl = 5),
            targetShortId = 3u
        )

        val decision = node.processIncomingPacket(packet.toByteArray())
        assertTrue("LPN no debe retransmitir mensajes de terceros", decision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)
        assertNull(relayedBytes)
    }

    @Test
    fun testLpnConsumesTextTargetedToSelf() {
        val lpn = LowPowerNodeMode(batteryPercent = 12)
        var deliveredText: String? = null

        val node = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId
        ,
        requireAuthorization = false
        ).apply {
            lpnMode = lpn
            customRideSecretKey = testRideKey
            onTextMessageReceived = { _, text -> deliveredText = text }
        }

        // Mensaje dirigido específicamente a N2
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje directo para LPN N2",
            header = createHeader(sender = 1u, ttl = 5),
            targetShortId = 2u
        )

        val decision = node.processIncomingPacket(packet.toByteArray())
        assertTrue(decision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)
        assertEquals("Mensaje directo para LPN N2", deliveredText)
    }

    @Test
    fun testLpnAlwaysAwakesAndRelaysSos() {
        val lpn = LowPowerNodeMode(batteryPercent = 5) // Batería al 5%
        var relayedBytes: ByteArray? = null
        val senderKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        val node = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId,
            onTransmitRelay = { bytes -> relayedBytes = bytes }
        ,
        requireAuthorization = false
        ).apply {
            lpnMode = lpn
            sosCrypto = Ed25519SosCrypto(context = null)
            sosPublicKeyResolver = InMemorySosPublicKeyResolver().apply {
                registerPeer(1u, senderKeys.public)
            }
        }

        // Simular SosPacket con TTL=5
        val sosHeader = createHeader(sender = 1u, type = MeshHeader.TYPE_SOS, ttl = 5)
        val sosPacket = SosPacket(
            header = sosHeader,
            latitudeRaw = 104064000,
            longitudeRaw = -672886000,
            bloodType = 1,
            incidentCode = 2,
            timestampEpochSec = 1731550000,
            signature = ByteArray(64) { 0x55.toByte() },
            hopCount = 0
        )

        val signedSos = Ed25519SosCrypto(context = null).signSos(sosPacket, senderKeys.private)
        val decision = node.processIncomingPacket(signedSos.toByteArray())
        assertTrue("LPN SIEMPRE debe retransmitir alertas SOS prioritarias", decision is MeshRelayEngine.RelayDecision.Relayed)
        assertNotNull(relayedBytes)
    }

    @Test
    fun testLpnAutomaticBatteryThresholdAndManualOverride() {
        val lpn = LowPowerNodeMode(batteryPercent = 80)
        assertFalse("Con 80% de batería, LPN debe estar inactivo", lpn.isLpnActive)

        lpn.batteryPercent = 15
        assertTrue("Con 15% de batería, LPN debe activarse automáticamente", lpn.isLpnActive)

        lpn.batteryPercent = 10
        assertTrue("Con 10% de batería, LPN debe permanecer activo", lpn.isLpnActive)

        // El usuario anula manualmente y fuerza LPN inactivo
        lpn.manualOverrideEnabled = false
        assertFalse("Si el usuario desactiva LPN manualmente, debe estar inactivo incluso con batería baja", lpn.isLpnActive)

        // El usuario fuerza LPN activo con batería al 100%
        lpn.batteryPercent = 100
        lpn.manualOverrideEnabled = true
        assertTrue("Si el usuario fuerza LPN manualmente, debe activarse con cualquier batería", lpn.isLpnActive)
    }

    @Test
    fun testLpnAwakeCycleWindowCalculation() {
        val lpn = LowPowerNodeMode(batteryPercent = 10)
        assertTrue(lpn.isLpnActive)

        // Ciclo: 200 ms activo (0..199), 4800 ms reposo (200..4999)
        assertTrue("t = 0 ms debe estar despierto", lpn.isAwakeAt(0L))
        assertTrue("t = 100 ms debe estar despierto", lpn.isAwakeAt(100L))
        assertTrue("t = 199 ms debe estar despierto", lpn.isAwakeAt(199L))

        assertFalse("t = 200 ms debe estar dormido", lpn.isAwakeAt(200L))
        assertFalse("t = 1000 ms debe estar dormido", lpn.isAwakeAt(1000L))
        assertFalse("t = 4999 ms debe estar dormido", lpn.isAwakeAt(4999L))

        // Inicio del siguiente ciclo (5.000 ms)
        assertTrue("t = 5000 ms debe estar despierto (siguiente ciclo)", lpn.isAwakeAt(5000L))
        assertTrue("t = 5150 ms debe estar despierto", lpn.isAwakeAt(5150L))
        assertFalse("t = 5250 ms debe estar dormido", lpn.isAwakeAt(5250L))
    }
}
