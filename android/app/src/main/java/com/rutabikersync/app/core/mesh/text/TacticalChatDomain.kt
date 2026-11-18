package com.rutabikersync.app.core.mesh.text

enum class DeliveryStatus {
    SENDING,
    SENT,
    ACK_RECEIVED,
    FAILED
}

/**
 * Modelo de datos inmutable para mensajes de texto tácticos en la interfaz de usuario.
 * Cero persistencia en flash; vive exclusivamente en memoria durante la rodada (R12).
 */
data class TacticalChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val senderShortId: UShort,
    val senderName: String,
    val senderRole: String = "PILOTO",
    val targetShortId: UShort = TextMessagePacket.BROADCAST_TARGET,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean,
    val hopCount: Byte = 0,
    val ackReceived: Boolean = false,
    val deliveryStatus: DeliveryStatus = if (isOutgoing) (if (ackReceived) DeliveryStatus.ACK_RECEIVED else DeliveryStatus.SENT) else DeliveryStatus.ACK_RECEIVED
) {
    val isBroadcast: Boolean get() = targetShortId == TextMessagePacket.BROADCAST_TARGET
}
