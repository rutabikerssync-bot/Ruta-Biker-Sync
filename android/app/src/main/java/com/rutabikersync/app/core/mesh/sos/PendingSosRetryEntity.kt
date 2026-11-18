package com.rutabikersync.app.core.mesh.sos

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entidad de persistencia local para reintentos de SOS ante caídas de conectividad (Fase 7.1.5.5).
 * Permite garantizar que tanto SOS locales como puenteados desde BLE Mesh no se pierdan si
 * la conexión celular es inestable, reintentando durante un TTL de 5 minutos (300 s).
 */
@Entity(tableName = "pending_sos_retries")
data class PendingSosRetryEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val meshPacketId: String,
    val senderShortId: Int,
    val latitude: Double,
    val longitude: Double,
    val hasMedicalSheet: Boolean,
    val radiusKm: Double = 5.0,
    val notes: String? = null,
    val source: String = "ble_mesh",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val lastAttemptEpochMs: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)
