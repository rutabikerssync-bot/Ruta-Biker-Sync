package com.rutabikersync.app.core.clubroutes

/** Resultado de una transición solicitada desde la UI. */
sealed class RideLifecycleRequestResult {
    data class Applied(val statusCode: Int) : RideLifecycleRequestResult()
    data class Rejected(val reason: String) : RideLifecycleRequestResult()
    data class Queued(val event: RideLifecycleTransition) : RideLifecycleRequestResult()
}

/**
 * Decisiones de estado derivadas de un resultado confirmado por el servidor.
 *
 * La UI NO debe mutar roster/fase antes de recibir `Applied`. Este objeto es la
 * única fuente de verdad para esa decisión y permite testear 403/409 sin UI.
 */
object RideLifecycleStatePolicy {
    fun shouldMutateRoster(result: RideLifecycleRequestResult): Boolean =
        result is RideLifecycleRequestResult.Applied

    fun shouldOpenCockpit(
        event: RideLifecycleTransition,
        result: RideLifecycleRequestResult
    ): Boolean = shouldMutateRoster(result) &&
        (event == RideLifecycleTransition.START_OUTBOUND ||
            event == RideLifecycleTransition.START_RETURN)

    /** REST antes que BLE: solo se emite por malla cuando el servidor confirmó (ACK). */
    fun shouldBroadcastMesh(result: RideLifecycleRequestResult): Boolean =
        result is RideLifecycleRequestResult.Applied
}

/**
 * Intenta la transición en primer plano; ante fallo de red la encola y agenda
 * al worker. Si ya hay eventos pendientes para la ruta, encola directamente
 * para preservar el orden start → arrive-destination → return → complete.
 */
class RideLifecycleOutboxCoordinator(
    private val dao: RideLifecycleOutboxDao,
    private val transport: RideLifecycleTransport,
    private val scheduleWorker: () -> Unit = {}
) {

    suspend fun request(
        routeId: String,
        event: RideLifecycleTransition
    ): RideLifecycleRequestResult {
        if (dao.pendingCountForRoute(routeId) > 0) return enqueue(routeId, event)

        return when (val result = transport.send(routeId, event)) {
            is RideLifecycleSendResult.Accepted ->
                RideLifecycleRequestResult.Applied(200)
            is RideLifecycleSendResult.Rejected ->
                RideLifecycleRequestResult.Rejected(result.reason)
            is RideLifecycleSendResult.Error -> enqueue(routeId, event)
        }
    }

    private suspend fun enqueue(
        routeId: String,
        event: RideLifecycleTransition
    ): RideLifecycleRequestResult.Queued {
        dao.enqueue(
            RideLifecycleOutboxEntity(
                routeId = routeId,
                event = event.name,
                sequence = dao.nextSequence()
            )
        )
        scheduleWorker()
        return RideLifecycleRequestResult.Queued(event)
    }

    suspend fun flush(): RideLifecycleOutboxSyncEngine.SyncResult =
        RideLifecycleOutboxSyncEngine(dao, transport).flush()
}
