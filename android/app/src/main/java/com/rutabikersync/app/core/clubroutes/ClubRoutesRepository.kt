package com.rutabikersync.app.core.clubroutes

import android.content.Context
import com.rutabikersync.app.core.network.ClubApiClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Vista ya resuelta para la UI: filas visibles + resumen + ámbito. */
data class RsvpView(
    val rsvps: List<RsvpEntity>,
    val summary: RsvpSummaryEntity?
) {
    val scope: String get() = summary?.scope ?: "member"
}

private fun JsonElement?.asText(): String? =
    (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

private fun JsonElement?.asInt(): Int =
    (this as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

/**
 * Repositorio cache-first de rutas y RSVP (Fase 5 · Hito 2).
 *
 * - Lee Room/DataStore primero: la pantalla pinta sin red.
 * - Refresca en segundo plano y reemplaza la caché solo si la respuesta es válida.
 * - Las mutaciones entran al outbox, nunca directamente a la red.
 */
class ClubRoutesRepository(
    private val api: ClubApiClient,
    private val routeDao: ClubRouteDao,
    private val outboxDao: RsvpOutboxDao,
    private val context: Context? = null
) {

    private val _pendingCount = MutableStateFlow(0)
    /** Nº de RSVP pendientes de sincronizar; alimenta el banner de la UI. */
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    suspend fun refreshPendingCount() {
        _pendingCount.value = outboxDao.pendingCount()
    }

    /** Caché primero; si hay red, reconcilia y devuelve el estado fresco. */
    suspend fun loadRoutes(groupId: String): List<ClubRouteEntity> {
        val cached = routeDao.getRoutes(groupId)
        val res = api.getClubRoutes(groupId).getOrNull()
        if (res == null || !res.isSuccess || res.body == null) return cached

        val arr = res.body["routes"] as? JsonArray ?: return cached
        val fresh = arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.asText() ?: return@mapNotNull null
            ClubRouteEntity(
                id = id,
                groupId = o["group_id"]?.asText() ?: groupId,
                createdBy = o["created_by"]?.asText().orEmpty(),
                title = o["title"]?.asText() ?: "Ruta",
                description = o["description"]?.asText(),
                routeRef = o["route_ref"]?.asText(),
                startsAt = o["starts_at"]?.asText(),
                endsAt = o["ends_at"]?.asText(),
                status = o["status"]?.asText() ?: "published",
                createdAt = o["created_at"]?.asText(),
                updatedAt = o["updated_at"]?.asText()
            )
        }
        routeDao.upsertRoutes(fresh)
        return routeDao.getRoutes(groupId)
    }

    /**
     * Próxima rodada publicada y futura del club (criterio D4): publicada,
     * con startsAt parseable y posterior a ahora, ordenada por startsAt asc.
     * Usa caché local; el llamador puede refrescar antes con [loadRoutes].
     */
    suspend fun getNextRoute(groupId: String): ClubRouteEntity? {
        val now = Instant.now()
        return routeDao.getRoutes(groupId)
            .asSequence()
            .filter { it.status == "published" }
            .mapNotNull { route ->
                val start = parseRouteInstant(route.startsAt) ?: return@mapNotNull null
                if (start.isAfter(now)) route to start else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun parseRouteInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * El propio endpoint ya limita la respuesta según `scope`: el miembro recibe
     * solo su fila y el presidente todas. Aquí no se refiltra: se respeta el
     * contrato del backend para no duplicar reglas de autorización en el cliente.
     */
    suspend fun loadRsvps(routeId: String): RsvpView {
        val cached = RsvpView(routeDao.getRsvps(routeId), routeDao.getSummary(routeId))
        val res = api.getRsvps(routeId).getOrNull()
        if (res == null || !res.isSuccess || res.body == null) return cached

        val arr = res.body["rsvps"] as? JsonArray ?: return cached
        val rows = arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val userId = o["user_id"]?.asText() ?: return@mapNotNull null
            RsvpEntity(
                routeId = routeId,
                userId = userId,
                status = o["status"]?.asText() ?: "pending",
                respondedAt = o["responded_at"]?.asText(),
                homeAt = o["home_at"]?.asText(),
                updatedAt = o["updated_at"]?.asText()
            )
        }

        val sObj = res.body["summary"] as? JsonObject
        val summary = RsvpSummaryEntity(
            routeId = routeId,
            scope = res.body["scope"]?.asText() ?: "member",
            confirmed = sObj?.get("confirmed").asInt(),
            enRoute = sObj?.get("en_route").asInt(),
            home = sObj?.get("home").asInt(),
            noGo = sObj?.get("no_go").asInt(),
            total = sObj?.get("total").asInt()
        )

        routeDao.deleteRsvpsForRoute(routeId)
        if (rows.isNotEmpty()) routeDao.insertRsvps(rows)
        routeDao.upsertSummary(summary)
        return RsvpView(routeDao.getRsvps(routeId), summary)
    }

    /**
     * Escribe la intención en el outbox y refleja el estado en caché al instante.
     * Nunca lanza la petición en primer plano: la hace [RsvpOutboxWorker] al volver la red.
     */
    suspend fun requestRsvp(routeId: String, userId: String, targetStatus: String) {
        outboxDao.enqueue(
            RsvpOutboxEntity(
                routeId = routeId,
                userId = userId,
                targetStatus = targetStatus
            )
        )
        routeDao.insertRsvps(
            listOf(RsvpEntity(routeId = routeId, userId = userId, status = targetStatus))
        )
        refreshPendingCount()
        context?.let { RsvpOutboxWorker.schedule(it) }
    }
}
