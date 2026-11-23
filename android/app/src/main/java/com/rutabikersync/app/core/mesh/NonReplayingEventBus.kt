package com.rutabikersync.app.core.mesh

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bus de eventos de malla con `replay = 0` (P1/T4).
 *
 * Evita que una señal o un evento de ciclo antiguo vuelva a ejecutarse al
 * recrear la cabina o el scaffold. Los eventos son exactamente eso: eventos,
 * no estado; quien llega tarde no recibe el último emitido.
 */
class NonReplayingEventBus<T>(extraBufferCapacity: Int = 64) {

    private val _events = MutableSharedFlow<T>(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<T> = _events.asSharedFlow()

    /**
     * Publica sin replay. Con `DROP_OLDEST`, `tryEmit` acepta el evento y descarta
     * el más antiguo cuando el buffer está lleno; si no hay suscriptores, el
     * evento se acepta pero ningún suscriptor futuro lo recibirá.
     */
    fun publish(event: T): Boolean = _events.tryEmit(event)
}
