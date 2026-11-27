package com.rutabikersync.app.core.clubroutes

/**
 * Resultado tipado de un envío de ciclo.
 *
 * `Rejected` (403/409 de fase) es terminal: se descarta para no reenviar
 * perpetuamente una transición imposible. `Error` (red/5xx) conserva la fila
 * para que WorkManager la reintente con backoff.
 */
sealed class RideLifecycleSendResult {
    object Accepted : RideLifecycleSendResult()
    data class Rejected(val reason: String) : RideLifecycleSendResult()
    data class Error(val message: String) : RideLifecycleSendResult()
}

/** Transporte inyectable: `ClubApiClient` en producción, fake en tests. */
interface RideLifecycleTransport {
    suspend fun send(
        routeId: String,
        event: RideLifecycleTransition
    ): RideLifecycleSendResult
}

/** Vacía la cola en orden estricto; un error transitorio detiene el flush. */
class RideLifecycleOutboxSyncEngine(
    private val dao: RideLifecycleOutboxDao,
    private val transport: RideLifecycleTransport
) {

    sealed class SyncResult {
        data class Success(
            val syncedCount: Int,
            val droppedCount: Int,
            /** Solo eventos confirmados por REST (Accepted); alimentan el puente BLE. */
            val ackedEvents: List<RideLifecycleTransition> = emptyList()
        ) : SyncResult()

        data class Failure(val message: String, val syncedCount: Int) : SyncResult()
        object NoOp : SyncResult()
    }

    suspend fun flush(): SyncResult {
        val pending = dao.getUnsynced()
        if (pending.isEmpty()) return SyncResult.NoOp

        var synced = 0
        var dropped = 0
        val acked = mutableListOf<RideLifecycleTransition>()

        for (item in pending) {
            val transition = item.transition
            if (transition == null) {
                // Fila corrupta: terminal, no bloquea el resto de la cola.
                dao.delete(item.id)
                dropped++
                continue
            }
            val result = try {
                transport.send(item.routeId, transition)
            } catch (e: Exception) {
                RideLifecycleSendResult.Error(e.message ?: "excepción de transporte")
            }

            when (result) {
                is RideLifecycleSendResult.Accepted -> {
                    dao.markSynced(item.id)
                    synced++
                    acked += transition
                }
                is RideLifecycleSendResult.Rejected -> {
                    dao.delete(item.id)
                    dropped++
                }
                is RideLifecycleSendResult.Error -> {
                    dao.markAttempt(item.id, result.message)
                    return SyncResult.Failure(result.message, synced)
                }
            }
        }

        dao.deleteSynced()
        return SyncResult.Success(synced, dropped, acked)
    }
}
