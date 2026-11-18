package com.rutabikersync.app.core.gamification

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente para el envío de resúmenes de telemetría y gamificación al backend (Fase 6 · Hito 5 · Etapa 3).
 *
 * Restricción de Privacidad:
 * Este cliente envía únicamente métricas agregadas y escalares ([TelemetrySummaryPayload]).
 * **Bajo ninguna circunstancia** envía datos de latitud, longitud o trazados GPS.
 */
interface TelemetryApiClient {
    suspend fun sendTelemetrySummary(
        rideId: String,
        events: List<PendingGamificationEvent>,
        xpEarned: Int = 0
    ): Boolean
}

@Serializable
data class TelemetryEventItem(
    val eventId: String,
    val eventType: String,
    val value: Long,
    val timestamp: Long
)

@Serializable
data class TelemetrySummaryPayload(
    val rideId: String,
    val events: List<TelemetryEventItem>,
    val xpEarned: Int = 0
)

/**
 * Implementación por defecto de [TelemetryApiClient] para producción y pruebas.
 */
class DefaultTelemetryApiClient(
    private val context: Context? = null,
    private val baseUrl: String = "https://api.rutabikersync.com"
) : TelemetryApiClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun sendTelemetrySummary(
        rideId: String,
        events: List<PendingGamificationEvent>,
        xpEarned: Int
    ): Boolean {
        if (events.isEmpty()) return true

        val payload = TelemetrySummaryPayload(
            rideId = rideId,
            events = events.map {
                TelemetryEventItem(
                    eventId = it.id,
                    eventType = it.eventType,
                    value = it.value,
                    timestamp = it.timestamp
                )
            },
            xpEarned = xpEarned
        )

        val jsonString = json.encodeToString(payload)

        // Modo offline / mock si estamos en pruebas o entorno de desarrollo local
        if (baseUrl.startsWith("mock://") || baseUrl == "offline") {
            return false
        }

        return try {
            val url = URL("$baseUrl/v1/rides/$rideId/telemetry-summary")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }

            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            Log.w("TelemetryApiClient", "Error de red transmitiendo telemetría resumida: ${e.message}")
            false
        }
    }
}

/**
 * Motor de sincronización desacoplado para eventos de gamificación (Fase 6 · Hito 5 · Etapa 3).
 *
 * Se encarga de:
 * 1. Leer eventos pendientes no sincronizados desde [dao].
 * 2. Agrupar por `rideId` y transmitir resúmenes al backend con [apiClient].
 * 3. Al confirmar recepción por parte del servidor, marcar como sincronizados y purgar de Room.
 * 4. Ante error de red o rechazo, abortar y preservar los eventos para reintento exponencial posterior.
 */
class GamificationSyncEngine(
    private val dao: PendingGamificationEventDao,
    private val apiClient: TelemetryApiClient
) {

    sealed class SyncResult {
        data class Success(val syncedCount: Int) : SyncResult()
        data class Failure(val error: String) : SyncResult()
        object NoOp : SyncResult()
    }

    suspend fun syncPendingEvents(): SyncResult {
        val unsynced = dao.getUnsyncedEvents()
        if (unsynced.isEmpty()) {
            return SyncResult.NoOp
        }

        val byRide = unsynced.groupBy { it.rideId }
        var totalSynced = 0

        for ((rideId, events) in byRide) {
            val meshKmEvent = events.find { it.eventType == GamificationEventType.MESH_KM.name }
            val relaysEvent = events.find { it.eventType == GamificationEventType.RELAY_COUNT.name }
            val sosEvent = events.find { it.eventType == GamificationEventType.SOS_ASSIST.name }

            val meshKm = (meshKmEvent?.value ?: 0L) / 1000.0
            val relays = (relaysEvent?.value ?: 0L).toInt()
            val sos = (sosEvent?.value ?: 0L).toInt()

            val xp = 10 + (meshKm / 50.0).toInt() + (relays / 10) + (sos * 10)

            val success = try {
                apiClient.sendTelemetrySummary(rideId, events, xpEarned = xp)
            } catch (e: Exception) {
                return SyncResult.Failure("Excepción de transporte durante sincronización: ${e.message}")
            }

            if (success) {
                for (event in events) {
                    dao.markAsSynced(event.id)
                }
                totalSynced += events.size
            } else {
                return SyncResult.Failure("Fallo al sincronizar eventos para la rodada $rideId")
            }
        }

        // Purgar de SQLite los eventos sincronizados para cumplir con almacenamiento eficiente
        dao.deleteSyncedEvents()
        return SyncResult.Success(totalSynced)
    }
}
