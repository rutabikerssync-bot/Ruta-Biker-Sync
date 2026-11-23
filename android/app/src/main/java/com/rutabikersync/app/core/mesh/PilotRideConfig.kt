package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.BuildConfig

/**
 * Rodada usada por los builds piloto. El UUID real se inyecta al compilar con
 * `-Prbs.pilot.rideId` o `RBS_PILOT_RIDE_ID`; no es un dato versionado.
 * Sin ese valor se conserva el identificador local histórico y el downlink HTTP
 * permanece desactivado, porque el backend solo acepta UUID reales.
 */
object PilotRideConfig {
    private const val LEGACY_RIDE_ID = "ride-live-01"

    private val UUID_PATTERN = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    val rideId: String = BuildConfig.PILOT_RIDE_ID
        .takeIf { UUID_PATTERN.matches(it) }
        ?: LEGACY_RIDE_ID

    val isRealRide: Boolean = rideId != LEGACY_RIDE_ID
}
