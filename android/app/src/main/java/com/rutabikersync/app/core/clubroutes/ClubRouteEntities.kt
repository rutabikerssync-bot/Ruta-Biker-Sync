package com.rutabikersync.app.core.clubroutes

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caché local de rutas de club (Fase 5 · Hito 2).
 * Espejo de `club_routes` (migración 0017). Se lee primero y se refresca en segundo plano.
 */
@Entity(tableName = "club_routes_cache")
data class ClubRouteEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val createdBy: String,
    val title: String,
    val description: String? = null,
    val routeRef: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val status: String = "published",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val fetchedAt: Long = System.currentTimeMillis()
)

/**
 * Fila de asistencia visible para el usuario actual. El backend ya aplica
 * `scope: member` y devuelve solo la fila propia; el presidente recibe `full`.
 */
@Entity(tableName = "club_route_rsvps_cache", primaryKeys = ["routeId", "userId"])
data class RsvpEntity(
    val routeId: String,
    val userId: String,
    val status: String,
    val respondedAt: String? = null,
    val homeAt: String? = null,
    val updatedAt: String? = null
)

/**
 * Resumen agregado + ámbito de visibilidad. Se guarda aunque no haya filas,
 * para que la UI pueda mostrar contadores sin esperar a la red.
 */
@Entity(tableName = "club_route_rsvp_summaries")
data class RsvpSummaryEntity(
    @PrimaryKey val routeId: String,
    val scope: String = "member",
    val confirmed: Int = 0,
    val enRoute: Int = 0,
    val home: Int = 0,
    val noGo: Int = 0,
    val total: Int = 0,
    val fetchedAt: Long = System.currentTimeMillis()
)

/**
 * Outbox de mutaciones RSVP.
 *
 * Un RSVP es un ESTADO, no un evento: por eso hay como máximo una fila pendiente
 * por (routeId, userId). Un reintento o un cambio de opinión sobrescribe
 * `targetStatus` (REPLACE). Esto replica en el cliente la idempotencia del
 * backend (201 la primera vez, 200 con `changed=false` y mismo `responded_at`
 * en los reintentos) y hace innecesario deduplicar en el servidor.
 */
@Entity(tableName = "rsvp_outbox", primaryKeys = ["routeId", "userId"])
data class RsvpOutboxEntity(
    val routeId: String,
    val userId: String,
    val targetStatus: String,
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val attempts: Int = 0,
    val lastError: String? = null
) {
    val key: String get() = "$routeId:$userId"
}
