package com.rutabikersync.app.core.mesh

/**
 * P0/H4: orden REST → BLE con fail-closed.
 *
 * Ningún evento de ciclo puede salir a la malla antes de que el backend haya
 * respondido autorizando la acción. Este es el único punto que encadena ambas
 * operaciones, por lo que el orden se puede verificar en tests JVM sin Android.
 */
object RestFirstLifecycleGate {

    suspend fun <T> runAfterRest(
        restCall: suspend () -> Result<T>,
        authorized: (T) -> Boolean,
        onAuthorized: suspend (T) -> Unit,
        onDenied: (response: T?, error: Throwable?) -> Unit,
        lifecycleEvent: MeshRelayEngine.RideLifecycleEvent,
        emit: suspend (MeshRelayEngine.RideLifecycleEvent) -> Unit = { event ->
            RideService.dispatchSendRideLifecycleEvent(event)
        }
    ) {
        val result = restCall()
        result.fold(
            onSuccess = { response ->
                if (authorized(response)) {
                    onAuthorized(response)
                    // Solo después de REST y de aplicar el estado local autorizado.
                    emit(lifecycleEvent)
                } else {
                    onDenied(response, null)
                }
            },
            onFailure = { error -> onDenied(null, error) }
        )
    }
}
