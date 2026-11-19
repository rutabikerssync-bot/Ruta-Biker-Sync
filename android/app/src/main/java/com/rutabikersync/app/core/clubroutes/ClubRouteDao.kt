package com.rutabikersync.app.core.clubroutes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** DAO de la caché de rutas y asistencia. */
@Dao
interface ClubRouteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutes(routes: List<ClubRouteEntity>)

    @Query(
        "SELECT * FROM club_routes_cache WHERE groupId = :groupId " +
            "ORDER BY (startsAt IS NULL), startsAt ASC, createdAt DESC"
    )
    suspend fun getRoutes(groupId: String): List<ClubRouteEntity>

    @Query("DELETE FROM club_route_rsvps_cache WHERE routeId = :routeId")
    suspend fun deleteRsvpsForRoute(routeId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRsvps(rsvps: List<RsvpEntity>)

    @Query("SELECT * FROM club_route_rsvps_cache WHERE routeId = :routeId ORDER BY respondedAt, userId")
    suspend fun getRsvps(routeId: String): List<RsvpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: RsvpSummaryEntity)

    @Query("SELECT * FROM club_route_rsvp_summaries WHERE routeId = :routeId")
    suspend fun getSummary(routeId: String): RsvpSummaryEntity?

    @Query("SELECT COUNT(*) FROM club_routes_cache")
    suspend fun cachedRouteCount(): Int
}

/**
 * Implementación en memoria para tests JVM deterministas (sin SQLite nativo).
 * Espejo de [InMemoryPendingGamificationEventDao] en `core.gamification`.
 */
class InMemoryClubRouteDao : ClubRouteDao {

    private val routes = linkedMapOf<String, ClubRouteEntity>()
    private val rsvps = linkedMapOf<Pair<String, String>, RsvpEntity>()
    private val summaries = linkedMapOf<String, RsvpSummaryEntity>()

    override suspend fun upsertRoutes(items: List<ClubRouteEntity>) {
        items.forEach { routes[it.id] = it }
    }

    override suspend fun getRoutes(groupId: String): List<ClubRouteEntity> =
        routes.values
            .filter { it.groupId == groupId }
            .sortedWith(compareBy<ClubRouteEntity> { it.startsAt == null }.thenBy { it.startsAt })

    override suspend fun deleteRsvpsForRoute(routeId: String) {
        rsvps.keys.removeAll { it.first == routeId }
    }

    override suspend fun insertRsvps(items: List<RsvpEntity>) {
        items.forEach { rsvps[it.routeId to it.userId] = it }
    }

    override suspend fun getRsvps(routeId: String): List<RsvpEntity> =
        rsvps.values.filter { it.routeId == routeId }.sortedBy { it.respondedAt }

    override suspend fun upsertSummary(summary: RsvpSummaryEntity) {
        summaries[summary.routeId] = summary
    }

    override suspend fun getSummary(routeId: String): RsvpSummaryEntity? = summaries[routeId]

    override suspend fun cachedRouteCount(): Int = routes.size
}
