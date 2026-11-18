package com.rutabikersync.app.core.gamification

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Tipos de eventos de gamificación admitidos para acumulación off-grid (GAMIFICATION.md · Fase 6 · Hito 5 · Etapa 3).
 */
enum class GamificationEventType {
    MESH_KM,
    RELAY_COUNT,
    SOS_ASSIST,
    OFFLINE_MINUTES
}

/**
 * Entidad Room persistente para encolar eventos de gamificación generados durante la rodada
 * sin conexión a internet (Fase 6 · Hito 5 · Etapa 3).
 *
 * Características:
 * - Persiste en SQLite local (Room) bajo la tabla `pending_gamification_events`.
 * - Permite acumular métricas generadas en malla fuera de cobertura celular.
 * - Sincronización asíncrona mediante [GamificationSyncWorker] al recuperar conectividad.
 * - Cero datos de ubicación geográfica: solo almacena escalares agregados ([value]).
 */
@Entity(tableName = "pending_gamification_events")
data class PendingGamificationEvent(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val rideId: String,
    val eventType: String,
    val value: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
