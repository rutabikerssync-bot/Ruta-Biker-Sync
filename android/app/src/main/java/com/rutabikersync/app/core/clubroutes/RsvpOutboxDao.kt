package com.rutabikersync.app.core.clubroutes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** DAO del outbox de RSVP. Una sola fila pendiente por (routeId, userId). */
@Dao
interface RsvpOutboxDao {

    /** REPLACE implementa la coalescencia de estado: la última intención gana. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: RsvpOutboxEntity)

    @Query("SELECT * FROM rsvp_outbox WHERE synced = 0 ORDER BY createdAt ASC")
    suspend fun getUnsynced(): List<RsvpOutboxEntity>

    @Query(
        "UPDATE rsvp_outbox SET synced = 1, lastError = NULL " +
            "WHERE routeId = :routeId AND userId = :userId"
    )
    suspend fun markSynced(routeId: String, userId: String)

    @Query(
        "UPDATE rsvp_outbox SET attempts = attempts + 1, lastError = :error " +
            "WHERE routeId = :routeId AND userId = :userId"
    )
    suspend fun markAttempt(routeId: String, userId: String, error: String)

    @Query("DELETE FROM rsvp_outbox WHERE synced = 1")
    suspend fun deleteSynced(): Int

    @Query("DELETE FROM rsvp_outbox WHERE routeId = :routeId AND userId = :userId")
    suspend fun delete(routeId: String, userId: String): Int

    @Query("SELECT COUNT(*) FROM rsvp_outbox WHERE synced = 0")
    suspend fun pendingCount(): Int
}

/** Implementación en memoria para tests JVM. */
class InMemoryRsvpOutboxDao : RsvpOutboxDao {

    private val storage = linkedMapOf<String, RsvpOutboxEntity>()

    override suspend fun enqueue(item: RsvpOutboxEntity) {
        // REPLACE conserva el createdAt original para no reordenar la cola.
        val previous = storage[item.key]
        storage[item.key] = if (previous != null) {
            item.copy(createdAt = previous.createdAt, attempts = previous.attempts)
        } else {
            item
        }
    }

    override suspend fun getUnsynced(): List<RsvpOutboxEntity> =
        storage.values.filter { !it.synced }.sortedBy { it.createdAt }

    override suspend fun markSynced(routeId: String, userId: String) {
        val key = "$routeId:$userId"
        storage[key]?.let { storage[key] = it.copy(synced = true, lastError = null) }
    }

    override suspend fun markAttempt(routeId: String, userId: String, error: String) {
        val key = "$routeId:$userId"
        storage[key]?.let {
            storage[key] = it.copy(attempts = it.attempts + 1, lastError = error)
        }
    }

    override suspend fun deleteSynced(): Int {
        val keys = storage.filterValues { it.synced }.keys.toList()
        keys.forEach { storage.remove(it) }
        return keys.size
    }

    override suspend fun delete(routeId: String, userId: String): Int =
        if (storage.remove("$routeId:$userId") != null) 1 else 0

    override suspend fun pendingCount(): Int = storage.values.count { !it.synced }
}
