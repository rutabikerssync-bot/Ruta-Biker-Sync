package com.rutabikersync.app.feature.mesh

import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.text.DeliveryStatus
import com.rutabikersync.app.core.mesh.text.TacticalChatMessage
import com.rutabikersync.app.core.mesh.text.TacticalChatStore
import com.rutabikersync.app.core.mesh.text.TextMessageCrypto
import com.rutabikersync.app.core.mesh.text.TextMessagePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Suite de Pruebas Unitarias para la Pantalla 1: Chat Táctico Off-Grid (Fase 7.0 · Hito 7.0.2).
 *
 * Valida:
 * 1. TacticalChatStore: inserción, recuperación reactiva y purga volátil R12.
 * 2. Límite de payload BLE: aceptación estricta de 120 bytes UTF-8 y rechazo de 121 bytes.
 * 3. Selector de enrutamiento: Broadcast (0xFFFF) vs Unicast a peer específico.
 * 4. Presets de Modo Guantes: los 6 presets requeridos generan mensajes válidos dentro del límite.
 * 5. Ciclo de entrega y ACK: transición de DeliveryStatus (SENT -> ACK_RECEIVED).
 */
class TacticalChatScreenTest {

    private val testRideKey = ByteArray(32) { (it + 7).toByte() }
    private val testClubNetworkId: UInt = 0x52425301u
    private val localShortId: UShort = 1u
    private val peerShortId: UShort = 2u

    private fun createHeader(sender: UShort = localShortId, seq: UShort = 100u): MeshHeader = MeshHeader(
        clubNetworkId = testClubNetworkId,
        senderShortId = sender,
        packetType = MeshHeader.TYPE_TEXT_MESSAGE,
        initTtl = 5,
        currentTtl = 5,
        sequenceNum = seq
    )

    @Before
    fun setUp() {
        TacticalChatStore.purgeR12()
    }

    @Test
    fun `Test 1 - TacticalChatStore insercion recuperacion y purga volatil R12`() {
        assertTrue("El store debe iniciar vacío", TacticalChatStore.messages.value.isEmpty())

        val msg1 = TacticalChatMessage(
            senderShortId = localShortId,
            senderName = "Tú",
            senderRole = "LÍDER",
            targetShortId = TextMessagePacket.BROADCAST_TARGET,
            text = "Prueba de canal táctico",
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.SENT
        )
        TacticalChatStore.addMessage(msg1)

        val msg2 = TacticalChatMessage(
            senderShortId = peerShortId,
            senderName = "Halcón Redmi",
            senderRole = "SCOUT",
            targetShortId = localShortId,
            text = "Copiado fuerte y claro",
            isOutgoing = false,
            deliveryStatus = DeliveryStatus.ACK_RECEIVED
        )
        TacticalChatStore.addMessage(msg2)

        val stored = TacticalChatStore.messages.value
        assertEquals("Debe contener exactamente 2 mensajes", 2, stored.size)
        assertEquals("Prueba de canal táctico", stored[0].text)
        assertEquals("Copiado fuerte y claro", stored[1].text)
        assertTrue(stored[0].isOutgoing)
        assertFalse(stored[1].isOutgoing)

        // Purga R12 al cerrar la rodada
        TacticalChatStore.purgeR12()
        assertTrue("Tras purgeR12() no debe quedar ningún mensaje en memoria", TacticalChatStore.messages.value.isEmpty())
    }

    @Test
    fun `Test 2 - Limite de tamano exacto 120 bytes aceptado y 121 bytes rechazado`() {
        // Exactamente 120 bytes ASCII
        val text120 = "A".repeat(120)
        assertEquals(120, text120.toByteArray(Charsets.UTF_8).size)

        // Cifrado y empaquetado debe ser exitoso con 120 bytes
        val packet120 = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = text120,
            header = createHeader(),
            targetShortId = TextMessagePacket.BROADCAST_TARGET
        )
        val decrypted120 = TextMessageCrypto.decrypt(testRideKey, packet120)
        assertEquals(text120, decrypted120)

        // 121 bytes debe ser rechazado por TextMessageCrypto
        val text121 = "A".repeat(121)
        assertEquals(121, text121.toByteArray(Charsets.UTF_8).size)

        var rejected = false
        try {
            TextMessageCrypto.encrypt(
                key = testRideKey,
                plaintext = text121,
                header = createHeader(),
                targetShortId = TextMessagePacket.BROADCAST_TARGET
            )
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Mensajes de 121 bytes deben ser rechazados con IllegalArgumentException", rejected)
    }

    @Test
    fun `Test 3 - Selector de destinatario discriminacion Broadcast vs Unicast`() {
        val broadcastMsg = TacticalChatMessage(
            senderShortId = localShortId,
            senderName = "Tú",
            targetShortId = TextMessagePacket.BROADCAST_TARGET,
            text = "Aviso a todos los miembros",
            isOutgoing = true
        )
        assertTrue("Target 0xFFFF debe marcarse como Broadcast", broadcastMsg.isBroadcast)
        assertEquals(TextMessagePacket.BROADCAST_TARGET, broadcastMsg.targetShortId)

        val unicastMsg = TacticalChatMessage(
            senderShortId = localShortId,
            senderName = "Tú",
            targetShortId = peerShortId,
            text = "Mensaje directo a Halcón",
            isOutgoing = true
        )
        assertFalse("Target específico no debe marcarse como Broadcast", unicastMsg.isBroadcast)
        assertEquals(peerShortId, unicastMsg.targetShortId)
    }

    @Test
    fun `Test 4 - Presets de Modo Guantes los 6 presets son validos y respetan el limite`() {
        val requiredPresets = listOf(
            "⛽ Gasolinera a 2 km",
            "🛑 Parada técnica (5 min)",
            "⚠️ Peligro / Baches",
            "🏍️ Reagrupar convoy",
            "👮 Retén adelante",
            "🌧️ Lluvia adelante"
        )
        assertEquals("Deben existir exactamente 6 presets requeridos", 6, requiredPresets.size)

        for ((idx, preset) in requiredPresets.withIndex()) {
            val byteSize = preset.toByteArray(Charsets.UTF_8).size
            assertTrue("El preset '$preset' ($byteSize B) debe ser <= 120 bytes", byteSize <= 120)
            assertTrue("El preset '$preset' no debe estar vacío", byteSize > 0)

            // Validar que se puede cifrar y empaquetar en malla BLE
            val packet = TextMessageCrypto.encrypt(
                key = testRideKey,
                plaintext = preset,
                header = createHeader(seq = (100 + idx).toUShort()),
                targetShortId = TextMessagePacket.BROADCAST_TARGET
            )
            val recovered = TextMessageCrypto.decrypt(testRideKey, packet)
            assertEquals("El preset debe recuperarse intacto tras ChaCha20-Poly1305", preset, recovered)
        }
    }

    @Test
    fun `Test 5 - Transicion de estado de entrega y confirmacion de ACK por malla`() {
        val msg = TacticalChatMessage(
            id = "msg-001",
            senderShortId = localShortId,
            senderName = "Tú",
            targetShortId = peerShortId,
            text = "⛽ Parada en la próxima salida",
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.SENT
        )
        TacticalChatStore.addMessage(msg)

        val initialMsg = TacticalChatStore.messages.value.first { it.id == "msg-001" }
        assertEquals(DeliveryStatus.SENT, initialMsg.deliveryStatus)
        assertFalse(initialMsg.ackReceived)

        // Simular recepción de ACK desde peerShortId (Hito 5)
        TacticalChatStore.markAckReceived(peerShortId)

        val updatedMsg = TacticalChatStore.messages.value.first { it.id == "msg-001" }
        assertEquals(DeliveryStatus.ACK_RECEIVED, updatedMsg.deliveryStatus)
        assertTrue(updatedMsg.ackReceived)
    }

    @Test
    fun `Test 6 - Deduplicacion automatica de rafagas RF repetidas en TacticalChatStore`() {
        val incoming1 = TacticalChatMessage(
            senderShortId = peerShortId,
            senderName = "El Búfalo",
            targetShortId = TextMessagePacket.BROADCAST_TARGET,
            text = "hola",
            isOutgoing = false
        )
        val incomingDuplicate = TacticalChatMessage(
            senderShortId = peerShortId,
            senderName = "El Búfalo",
            targetShortId = TextMessagePacket.BROADCAST_TARGET,
            text = "hola",
            isOutgoing = false
        )
        TacticalChatStore.addMessage(incoming1)
        TacticalChatStore.addMessage(incomingDuplicate)

        assertEquals("El mensaje duplicado por ráfaga RF debe ser descartado", 1, TacticalChatStore.messages.value.size)
    }
}
