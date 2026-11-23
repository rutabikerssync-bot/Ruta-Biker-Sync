package com.rutabikersync.app.feature.routes

/**
 * Orientación de la polilínea activa (M5).
 *
 * La ruta viaja y se almacena SIEMPRE en orden de emisión: invertir en el emisor
 * rompería el SHA-256 del relay de malla. La fase de retorno se aplica SOLO en
 * la lectura, de forma pura e idempotente.
 */
object RouteOrientation {

    fun effectiveWaypoints(
        waypoints: List<LatLngPoint>,
        isReturnPhase: Boolean
    ): List<LatLngPoint> = if (isReturnPhase) waypoints.reversed() else waypoints

    fun effectiveDestination(
        ride: ScheduledRide,
        isReturnPhase: Boolean
    ): WaypointLocation = if (isReturnPhase) ride.origin else ride.destination

    /** Una ruta de malla activada sustituye a la programada sin re-fragmentarla. */
    fun resolveActiveRoute(
        scheduled: RouteAlternative?,
        mesh: RouteMeshReceivedEvent?,
        meshIsActive: Boolean,
        isReturnPhase: Boolean
    ): List<LatLngPoint> {
        val source = if (meshIsActive && mesh != null) mesh.route else scheduled
        return effectiveWaypoints(source?.waypoints.orEmpty(), isReturnPhase)
    }
}
