package com.rutabikersync.app.feature.routes

/**
 * Desenlace de la recalculación de ruta iniciada desde el banner de desvío.
 *
 * - [SUCCESS]: se obtuvo un trazado vial real y se sustituyó la ruta activa.
 * - [OFFLINE]: no hay conectividad; se conserva la ruta cacheada actual.
 * - [NO_ROUTE]: con conectividad pero sin respuesta válida o sin datos de origen/destino;
 *   se conserva la ruta actual.
 */
enum class RecalculateRouteOutcome {
    SUCCESS,
    OFFLINE,
    NO_ROUTE
}

/**
 * Recalcula la ruta activa desde la posición real del piloto hasta el destino de la rodada.
 *
 * La búsqueda de vías reales y la comprobación de red se inyectan para que los tres
 * desenlaces sean verificables en JVM sin depender de Android ni de Mapbox.
 * No modifica malla BLE, GATT ni criptografía.
 */
class RecalculateRouteUseCase(
    private val isOnline: () -> Boolean,
    private val fetchRoadRoute: suspend (
        origin: LatLngPoint,
        destination: LatLngPoint,
        destinationName: String
    ) -> RouteAlternative?,
    private val isReturnPhase: () -> Boolean = { false }
) {
    suspend operator fun invoke(
        currentLocation: LatLngPoint?,
        destination: WaypointLocation?
    ): RecalculateRouteOutcome {
        // Sin red no se intenta la llamada remota: se mantiene la ruta offline ya cargada.
        if (!isOnline()) return RecalculateRouteOutcome.OFFLINE

        val origin = currentLocation ?: return RecalculateRouteOutcome.NO_ROUTE
        val returnPhase = isReturnPhase()
        val target = if (returnPhase) {
            RideScheduleRepository.currentRide.value
                ?.let { RouteOrientation.effectiveDestination(it, isReturnPhase = true) }
                ?: destination
        } else {
            destination
        } ?: return RecalculateRouteOutcome.NO_ROUTE

        val recalculated = fetchRoadRoute(
            origin,
            LatLngPoint(target.latitude, target.longitude),
            target.name
        ) ?: return RecalculateRouteOutcome.NO_ROUTE

        RideScheduleRepository.updateSelectedRoute(recalculated)
        RouteDeviationDetector.setActiveRoutePolyline(
            RouteOrientation.effectiveWaypoints(recalculated.waypoints, returnPhase)
        )

        // Reevaluar contra la nueva polilínea para que el banner desaparezca si el
        // piloto ya quedó dentro del corredor recalculado.
        val speed = RouteDeviationDetector.deviationState.value.speedKmh
        RouteDeviationDetector.updateRiderLocation(origin, speed)

        return RecalculateRouteOutcome.SUCCESS
    }
}
