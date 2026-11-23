package com.rutabikersync.app.core.clubroutes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Outbox de EVENTOS de ciclo: orden estricto por `sequence`, sin coalescencia. */
@Dao
interface RideLifecycleOutboxDao {

    @Insert
    suspend fun enqueue(item: RideLifecycleOutboxEntity)

    @Query(
        "SELECT * FROM ride_lifecycle_outbox WHERE synced = 0 " +
            "ORDER BY sequence ASC, createdAt ASC"
    )
    suspend fun getUnsynced(): List<RideLifecycleOutboxEntity>

    @Query("UPDATE ride_lifecycle_outbox SET synced = 1, lastError = NULL WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query(
        "UPDATE ride_lifecycle_outbox SET attempts = attempts + 1, lastError = :error " +
            "WHERE id = :id"
    )
    suspend fun markAttempt(id: String, error: String)

    @Query("DELETE FROM ride_lifecycle_outbox WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM ride_lifecycle_outbox WHERE synced = 1")
    suspend fun deleteSynced(): Int

    @Query("SELECT COUNT(*) FROM ride_lifecycle_outbox WHERE synced = 0")
    suspend fun pendingCount(): Int

    @Query(
        "SELECT COUNT(*) FROM ride_lifecycle_outbox " +
            "WHERE synced = 0 AND routeId = :routeId"
    )
    suspend fun pendingCountForRoute(routeId: String): Int

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM ride_lifecycle_outbox")
    suspend fun nextSequence(): Long
}

/** Implementación en memoria para tests JVM. */
class InMemoryRideLifecycleOutboxDao : RideLifecycleOutboxDao {

    private val storage = linkedMapOf<String, RideLifecycleOutboxEntity>()

    override suspend fun enqueue(item: RideLifecycleOutboxEntity) {
        val seq = if (item.sequence > 0) item.sequence else nextSequence()
        storage[item.id] = item.copy(sequence = seq)
    }

    override suspend fun getUnsynced(): List<RideLifecycleOutboxEntity> =
        storage.values.filter { !it.synced }.sortedWith(
            compareBy<RideLifecycleOutboxEntity> { it.sequence }.thenBy { it.createdAt }
        )

    override suspend fun markSynced(id: String) {
        storage[id]?.let { storage[id] = it.copy(synced = true, lastError = null) }
    }

    override suspend fun markAttempt(id: String, error: String) {
        storage[id]?.let {
            storage[id] = it.copy(attempts = it.attempts + 1, lastError = error)
        }
    }

    override suspend fun delete(id: String): Int = if (storage.remove(id) != null) 1 else 0

    override suspend fun deleteSynced(): Int {
        val ids = storage.filterValues { it.synced }.keys.toList()
        ids.forEach { storage.remove(it) }
        return ids.size
    }

    override suspend fun pendingCount(): Int = storage.values.count { !it.synced }

    override suspend fun pendingCountForRoute(routeId: String): Int =
        storage.values.count { !it.synced && it.routeId == routeId }

    override suspend fun nextSequence(): Long =
        (storage.values.maxOfOrNull { it.sequence } ?: 0L) + 1L
}
