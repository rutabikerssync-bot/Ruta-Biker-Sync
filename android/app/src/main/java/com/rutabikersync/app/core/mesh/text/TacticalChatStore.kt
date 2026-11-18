package com.rutabikersync.app.core.mesh.text

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Almacén volátil en memoria para mensajes tácticos de la sesión de rodada activa.
 * Cumple con la Regla R12: purga total al finalizar la rodada y cero escritura en almacenamiento persistente.
 */
object TacticalChatStore {
    private val _messages = MutableStateFlow<List<TacticalChatMessage>>(emptyList())
    val messages: StateFlow<List<TacticalChatMessage>> = _messages.asStateFlow()

    fun addMessage(message: TacticalChatMessage) {
        val list = _messages.value.toMutableList()
        list.add(message)
        _messages.value = list
    }

    fun markAckReceived(targetShortId: UShort) {
        _messages.value = _messages.value.map { msg ->
            if (msg.isOutgoing && msg.targetShortId == targetShortId) {
                msg.copy(ackReceived = true, deliveryStatus = DeliveryStatus.ACK_RECEIVED)
            } else {
                msg
            }
        }
    }

    fun markDeliveryStatus(messageId: String, status: DeliveryStatus) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(deliveryStatus = status)
            } else {
                msg
            }
        }
    }

    fun purgeR12() {
        _messages.value = emptyList()
    }
}
