package com.rutabikersync.app.core.clubroutes

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Transiciones globales de ciclo que pueden quedar pendientes de ACK.
 *
 * A diferencia del RSVP (un ESTADO coalescido por routeId+userId), aquí cada
 * transición es un EVENTO ordenado y no conmutativo: START → ARRIVE_DESTINATION
 * → RETURN → COMPLETE. Por eso la tabla tiene clave UUID y un `sequence`.
 */
enum class RideLifecycleTransition(val endpoint: String) {
    START_OUTBOUND("start"),
    ARRIVE("arrive"),
    ARRIVE_DESTINATION("arrive-destination"),
    START_RETURN("return"),
    COMPLETE_RIDE("complete");

    companion object {
        fun fromStored(value: String): RideLifecycleTransition? =
            values().firstOrNull { it.name == value }
    }
}

@Entity(tableName = "ride_lifecycle_outbox")
data class RideLifecycleOutboxEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val routeId: String,
    val event: String,
    val sequence: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val attempts: Int = 0,
    val lastError: String? = null
) {
    val transition: RideLifecycleTransition? get() = RideLifecycleTransition.fromStored(event)
}
