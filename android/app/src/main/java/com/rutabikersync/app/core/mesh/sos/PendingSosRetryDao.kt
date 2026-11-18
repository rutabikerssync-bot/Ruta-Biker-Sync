package com.rutabikersync.app.core.mesh.sos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object (DAO) para la cola persistente de reintentos SOS (Fase 7.1.5.5).
 */
@Dao
interface PendingSosRetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingSosRetryEntity)

    @Query("SELECT * FROM pending_sos_retries WHERE createdAtEpochMs >= :minValidTimestamp ORDER BY createdAtEpochMs ASC")
    suspend fun getActivePending(minValidTimestamp: Long): List<PendingSosRetryEntity>

    @Query("DELETE FROM pending_sos_retries WHERE id = :id OR meshPacketId = :meshPacketId")
    suspend fun delete(id: String, meshPacketId: String)

    @Query("DELETE FROM pending_sos_retries WHERE createdAtEpochMs < :expirationTimestamp")
    suspend fun purgeExpired(expirationTimestamp: Long): Int

    @Update
    suspend fun update(entity: PendingSosRetryEntity)

    @Query("SELECT count(*) FROM pending_sos_retries")
    suspend fun countAll(): Int
}
