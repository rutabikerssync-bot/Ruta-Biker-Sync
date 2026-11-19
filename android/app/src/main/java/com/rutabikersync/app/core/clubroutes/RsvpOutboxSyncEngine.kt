package com.rutabikersync.app.core.clubroutes

/**
 * Resultado tipado de un envío. Distinguir "rechazado" de "error" es crítico:
 * un 409 `INVALID_RSVP_TRANSITION` (p. ej. `home -> confirmed`) es terminal y
 * debe descartarse; un fallo de red o 5xx debe conservarse para reintento.
 */
sealed class RsvpSendResult {
    object Accepted : RsvpSendResult()
    data class Rejected(val reason: String) : RsvpSendResult()
    data class Error(val message: String) : RsvpSendResult()
}

/** Transporte inyectable: `ClubApiClient` en producción, fake en tests. */
interface RsvpTransport {
    suspend fun sendRsvp(routeId: String, userId: String, status: String): RsvpSendResult
}

/**
 * Motor de sincronización del outbox. Reenvía estados al endpoint idempotente
 * del backend (Fase 3), sin lógica de reintentos propia: WorkManager aporta el
 * backoff exponencial y [RsvpSendResult.Error] devuelve `retry`.
 */
class RsvpOutboxSyncEngine(
    private val dao: RsvpOutboxDao,
    private val transport: RsvpTransport
) {

    sealed class SyncResult {
        data class Success(val syncedCount: Int, val droppedCount: Int) : SyncResult()
        data class Failure(val message: String, val syncedCount: Int) : SyncResult()
        object NoOp : SyncResult()
    }

    suspend fun flush(): SyncResult {
        val pending = dao.getUnsynced()
        if (pending.isEmpty()) return SyncResult.NoOp

        var synced = 0
        var dropped = 0

        for (item in pending) {
            val result = try {
                transport.sendRsvp(item.routeId, item.userId, item.targetStatus)
            } catch (e: Exception) {
                RsvpSendResult.Error(e.message ?: "excepción de transporte")
            }

            when (result) {
                is RsvpSendResult.Accepted -> {
                    dao.markSynced(item.routeId, item.userId)
                    synced++
                }
                is RsvpSendResult.Rejected -> {
                    // Terminal: el estado es inalcanzable (home es terminal).
                    // Descartar evita un bucle infinito de reintentos.
                    dao.delete(item.routeId, item.userId)
                    dropped++
                }
                is RsvpSendResult.Error -> {
                    dao.markAttempt(item.routeId, item.userId, result.message)
                    return SyncResult.Failure(result.message, synced)
                }
            }
        }

        dao.deleteSynced()
        return SyncResult.Success(synced, dropped)
    }
}
