package com.rutabikersync.app.core.clubroutes

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.mesh.RideService

/**
 * Puente REST → BLE para el outbox de ciclo (FASE 1.2).
 *
 * Solo se llama con eventos ya confirmados por el backend (`Accepted`). El mapping es
 * el mismo que usa `Phase1ViewModel`: `ARRIVE` no tiene evento BLE equivalente.
 * No se conecta `RestFirstLifecycleGate` aquí para evitar una doble emisión.
 */
object RideLifecycleBleAckBridge {

    fun toMeshEvent(transition: RideLifecycleTransition): MeshRelayEngine.RideLifecycleEvent? =
        when (transition) {
            RideLifecycleTransition.START_OUTBOUND -> MeshRelayEngine.RideLifecycleEvent.START_OUTBOUND
            RideLifecycleTransition.ARRIVE_DESTINATION -> MeshRelayEngine.RideLifecycleEvent.ARRIVE_DESTINATION
            RideLifecycleTransition.START_RETURN -> MeshRelayEngine.RideLifecycleEvent.START_RETURN
            RideLifecycleTransition.COMPLETE_RIDE -> MeshRelayEngine.RideLifecycleEvent.COMPLETE_RIDE
            RideLifecycleTransition.ARRIVE -> null
        }

    suspend fun dispatch(events: List<RideLifecycleTransition>) {
        events.mapNotNull(::toMeshEvent).forEach { meshEvent ->
            runCatching { RideService.dispatchSendRideLifecycleEvent(meshEvent) }
        }
    }
}
