package com.rutabikersync.app.core.mesh

/**
 * P0/H1: cola en memoria de eventos ya autorizados por REST que llegaron antes
 * de que existiera el MeshRelayEngine (arranque frío). Evita perder la orden
 * cuando la UI reacciona al `onStarted` y el servicio todavía no publicó RUNNING.
 */
class PendingLifecycleEvents {

    private val events = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<MeshRelayEngine.RideLifecycleEvent, Boolean>()
    )

    fun enqueue(event: MeshRelayEngine.RideLifecycleEvent) {
        events.add(event)
    }

    fun snapshot(): List<MeshRelayEngine.RideLifecycleEvent> = events.toList()

    fun remove(event: MeshRelayEngine.RideLifecycleEvent) {
        events.remove(event)
    }

    fun clear() = events.clear()

    val isEmpty: Boolean get() = events.isEmpty()
}
