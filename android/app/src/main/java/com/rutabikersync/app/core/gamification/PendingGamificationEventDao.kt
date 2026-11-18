package com.rutabikersync.app.core.gamification

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO para la gestión de eventos de gamificación pendientes de sincronización (Fase 6 · Hito 5 · Etapa 3).
 */
@Dao
interface PendingGamificationEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PendingGamificationEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<PendingGamificationEvent>)

    @Query("SELECT * FROM pending_gamification_events WHERE synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedEvents(): List<PendingGamificationEvent>

    @Query("SELECT * FROM pending_gamification_events WHERE rideId = :rideId")
    suspend fun getEventsForRide(rideId: String): List<PendingGamificationEvent>

    @Query("UPDATE pending_gamification_events SET synced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)

    @Query("DELETE FROM pending_gamification_events WHERE synced = 1")
    suspend fun deleteSyncedEvents(): Int

    @Query("DELETE FROM pending_gamification_events WHERE rideId = :rideId")
    suspend fun deleteEventsForRide(rideId: String): Int

    @Query("SELECT * FROM pending_gamification_events")
    suspend fun getAllEvents(): List<PendingGamificationEvent>
}

/**
 * Implementación en memoria thread-safe de [PendingGamificationEventDao] para entornos de prueba
 * y verificación determinista en JVM sin necesidad de SQLite nativo de dispositivo.
 */
class InMemoryPendingGamificationEventDao : PendingGamificationEventDao {

    private val storage = mutableMapOf<String, PendingGamificationEvent>()

    override suspend fun insert(event: PendingGamificationEvent) {
        synchronized(storage) {
            storage[event.id] = event
        }
    }

    override suspend fun insertAll(events: List<PendingGamificationEvent>) {
        synchronized(storage) {
            events.forEach { storage[it.id] = it }
        }
    }

    override suspend fun getUnsyncedEvents(): List<PendingGamificationEvent> {
        synchronized(storage) {
            return storage.values.filter { !it.synced }.sortedBy { it.timestamp }
        }
    }

    override suspend fun getEventsForRide(rideId: String): List<PendingGamificationEvent> {
        synchronized(storage) {
            return storage.values.filter { it.rideId == rideId }
        }
    }

    override suspend fun markAsSynced(id: String) {
        synchronized(storage) {
            storage[id]?.let {
                storage[id] = it.copy(synced = true)
            }
        }
    }

    override suspend fun deleteSyncedEvents(): Int {
        synchronized(storage) {
            val toRemove = storage.values.filter { it.synced }.map { it.id }
            toRemove.forEach { storage.remove(it) }
            return toRemove.size
        }
    }

    override suspend fun deleteEventsForRide(rideId: String): Int {
        synchronized(storage) {
            val toRemove = storage.values.filter { it.rideId == rideId }.map { it.id }
            toRemove.forEach { storage.remove(it) }
            return toRemove.size
        }
    }

    override suspend fun getAllEvents(): List<PendingGamificationEvent> {
        synchronized(storage) {
            return storage.values.toList()
        }
    }
}
