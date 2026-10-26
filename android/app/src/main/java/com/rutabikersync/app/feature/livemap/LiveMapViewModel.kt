package com.rutabikersync.app.feature.livemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estados propios del encabezado de conectividad en C1 Mapa en vivo (SCREENS.md C1 y ICONS.md §4).
 */
enum class LiveMapConnectivityState(val label: String) {
    CONNECTING("Conectando · buscando riders"),
    MESH_CONNECTED("Mesh conectada"),
    MESH_PARTIAL_ISLANDS("⚠ Mesh parcial (islas)"),
    ALONE("Solo · sin nadie en rango")
}

/**
 * Modo de cámara del mapa en vivo (Decisión 3.3 B):
 * - `FREE`: navegación libre; el pin del líder sigue fijo y visible en el mapa.
 * - `FOLLOWING_LEADER`: centra la cámara en el líder durante 10 s y luego vuelve a `FREE`.
 * - `CENTERED_ON_SELF`: centrado en la ubicación propia.
 */
enum class CameraMode {
    FREE,
    FOLLOWING_LEADER,
    CENTERED_ON_SELF
}

data class RiderDirectoryEntry(
    val shortId: UShort,
    val fullName: String,
    val nickname: String? = null,
    val relation: RiderRelation = RiderRelation.SAME_GROUP,
    val rideRole: RiderRideRole = RiderRideRole.NONE,
    val markerColorHex: Long = 0xFF00E676
)

data class LeaderPinUiModel(
    val leaderShortId: UShort,
    val abbreviatedLabel: String,
    val latitude: Double,
    val longitude: Double
)

data class LiveMapUiState(
    val pmtilesAssetUri: String = LiveMapController.DEFAULT_PMTILES_ASSET_URI,
    val styleAssetUri: String = LiveMapController.DEFAULT_STYLE_ASSET_URI,
    val osmAttribution: String = LiveMapController.OSM_ATTRIBUTION,
    val isDarkTheme: Boolean = true,
    val glovesMode: Boolean = true,
    val gpsFixed: Boolean = true,
    val localBatteryPercent: Int = 88,
    val connectedNodesCount: Int = 0,
    val lostNodesCount: Int = 0,
    val connectivityState: LiveMapConnectivityState = LiveMapConnectivityState.CONNECTING,
    val cameraMode: CameraMode = CameraMode.FREE,
    val cameraLatitude: Double = 19.4326,
    val cameraLongitude: Double = -99.1332,
    val cameraZoom: Double = 14.0,
    val followLeaderUntilEpochMs: Long? = null,
    val followLeaderRemainingSeconds: Int = 0,
    val leaderPin: LeaderPinUiModel? = null,
    val markers: List<RiderMarkerUiModel> = emptyList(),
    val selectedRiderShortId: UShort? = null,
    val lastFrameUpdatedAtEpochMs: Long = 0L,
    val interpolationAlpha: Float = 1.0f,
    val statusBannerMessage: String? = null
)

/**
 * Controlador reactivo y determinista del Mapa en Vivo (Bloque 3.3).
 *
 * Implementa:
 * - Decisión 3.1 (A): PMTiles offline (`asset://tiles/sample_corridor.pmtiles`) + atribución OpenStreetMap.
 * - Decisión 3.2 (C): Nombre abreviado siempre visible (`"J.M."`) + nombre completo al tocar, respetando R4.7/R4.8.
 * - Decisión 3.3 (B): Pin del líder fijo + botón `"Seguir al líder"` que centra la cámara por 10 s y vuelve a `FREE`.
 * - Cadencia de refresco de 1 s (`MAP_UPDATE_CADENCE_MS = 1_000L`) e interpolación lineal (`interpolateMarkers`)
 *   entre paquetes BLE para evitar que los avatares salten.
 */
class LiveMapController(
    private val scope: CoroutineScope,
    private var localShortId: UShort = 1u,
    private var leaderShortId: UShort = 1u,
    initialDirectory: List<RiderDirectoryEntry> = defaultConvoyDirectory()
) {
    fun updateConvoyIds(localId: UShort, targetLeaderId: UShort) {
        this.localShortId = localId
        this.leaderShortId = targetLeaderId
        flushMapFrame(System.currentTimeMillis(), force = true)
    }

    fun setConvoyRole(isLeader: Boolean, targetLeaderShortId: UShort = 1u) {
        leaderShortId = targetLeaderShortId
        localShortId = if (isLeader) targetLeaderShortId else (if (targetLeaderShortId == 1u.toUShort()) 2u else 1u).toUShort()
        flushMapFrame(System.currentTimeMillis(), force = true)
    }
    companion object {
        const val DEFAULT_PMTILES_ASSET_URI: String = "asset://tiles/sample_corridor.pmtiles"
        const val DEFAULT_STYLE_ASSET_URI: String = "asset://tiles/dark_corridor_style.json"
        const val OSM_ATTRIBUTION: String = "© OpenStreetMap contributors"
        const val MAP_UPDATE_CADENCE_MS: Long = 1_000L
        const val FOLLOW_LEADER_DURATION_MS: Long = 10_000L
        const val DEVIATION_THRESHOLD_METERS: Double = 150.0
        const val CORRIDOR_REFERENCE_LON: Double = -99.1332

        fun defaultConvoyDirectory(): List<RiderDirectoryEntry> = listOf(
            RiderDirectoryEntry(
                shortId = 1u,
                fullName = "Juan Martínez",
                nickname = "El Capi",
                relation = RiderRelation.SAME_GROUP,
                rideRole = RiderRideRole.LEADER,
                markerColorHex = 0xFFFFB300
            ),
            RiderDirectoryEntry(
                shortId = 2u,
                fullName = "Carlos Vega",
                nickname = "Halcón",
                relation = RiderRelation.SAME_GROUP,
                rideRole = RiderRideRole.SCOUT,
                markerColorHex = 0xFF00E676
            ),
            RiderDirectoryEntry(
                shortId = 3u,
                fullName = "Miguel Torres",
                nickname = "Rayo",
                relation = RiderRelation.MUTUAL_FRIEND,
                rideRole = RiderRideRole.CAPTAIN,
                markerColorHex = 0xFF29B6F6
            ),
            RiderDirectoryEntry(
                shortId = 4u,
                fullName = "Elena Gómez",
                nickname = "Valkiria",
                relation = RiderRelation.SAME_GROUP,
                rideRole = RiderRideRole.SWEEP,
                markerColorHex = 0xFFAB47BC
            ),
            RiderDirectoryEntry(
                shortId = 5u,
                fullName = "Roberto Desconocido",
                nickname = "Sombra",
                relation = RiderRelation.ALLIED_STRANGER, // R4.7/R4.8: desconocido en rodada aliada
                rideRole = RiderRideRole.NONE,
                markerColorHex = 0xFFFF7043
            )
        )

        fun lerp(start: Double, end: Double, alpha: Float): Double {
            val clamped = alpha.coerceIn(0f, 1f).toDouble()
            return start + (end - start) * clamped
        }

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return (r * c).roundToInt()
        }
    }

    private val directory = initialDirectory.associateBy { it.shortId }.toMutableMap()
    private var latestPeersFromMesh: Map<UShort, MeshRelayEngine.PeerTrack> = emptyMap()
    private var localLat: Double = 19.4326
    private var localLon: Double = -99.1332
    private var localSpeedKmh: Int = 75
    private var localBearing: Float = 180f
    private var localBattery: Int = 88
    private var localSos: Boolean = false

    private var meshObserveJob: Job? = null
    private var cadenceLoopJob: Job? = null
    private var followTimeoutJob: Job? = null

    private val _uiState = MutableStateFlow(LiveMapUiState())
    val uiState: StateFlow<LiveMapUiState> = _uiState.asStateFlow()

    /**
     * Conecta el controlador al `MeshRelayEngine` para escuchar los cambios de `peers`.
     * Si `enablePeriodicCadenceLoop = true`, ejecuta un bucle cada 1 s (`MAP_UPDATE_CADENCE_MS`)
     * para volcar los paquetes recibidos sin saturar el renderizado del mapa.
     */
    fun bindToMeshEngine(
        engine: MeshRelayEngine,
        enablePeriodicCadenceLoop: Boolean = false
    ) {
        meshObserveJob?.cancel()
        meshObserveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.peers.collect { peerMap ->
                val hadNewEmergency = peerMap.values.any { it.isSos }
                latestPeersFromMesh = peerMap
                // Si hay un SOS activo, refresca de inmediato sin esperar el tick de 1 s
                if (hadNewEmergency) {
                    flushMapFrame(System.currentTimeMillis())
                }
            }
        }

        cadenceLoopJob?.cancel()
        if (enablePeriodicCadenceLoop) {
            cadenceLoopJob = scope.launch {
                while (isActive) {
                    flushMapFrame(System.currentTimeMillis())
                    delay(MAP_UPDATE_CADENCE_MS)
                }
            }
        }
    }

    fun updateLocalRiderPose(
        latitude: Double,
        longitude: Double,
        speedKmh: Int,
        bearingDegrees: Float = 180f,
        batteryPercent: Int = 88,
        isSos: Boolean = false
    ) {
        localLat = latitude
        localLon = longitude
        localSpeedKmh = speedKmh
        localBearing = bearingDegrees
        localBattery = batteryPercent
        localSos = isSos
    }

    /**
     * Vuelca el estado acumulado de la mesh al mapa respetando la cadencia de 1 s (`MAP_UPDATE_CADENCE_MS`).
     * Si se invoca antes de que transcurra 1 s desde el último frame y `force = false` (y sin SOS),
     * conserva el frame actual para no saturar la UI con cada paquete BLE individual.
     */
    fun flushMapFrame(nowEpochMs: Long, force: Boolean = false): LiveMapUiState {
        val current = _uiState.value
        val anySos = localSos || latestPeersFromMesh.values.any { it.isSos }
        if (!force && !anySos && current.lastFrameUpdatedAtEpochMs > 0L &&
            nowEpochMs - current.lastFrameUpdatedAtEpochMs < MAP_UPDATE_CADENCE_MS
        ) {
            return current
        }

        val previousByShortId = current.markers.associateBy { it.shortId }

        // Ubicación del líder para calcular distancias y fijar el pin del líder (Decisión 3.3 B)
        val leaderPose: Pair<Double, Double> = if (leaderShortId == localShortId) {
            localLat to localLon
        } else {
            val leaderTrack = latestPeersFromMesh[leaderShortId]
            if (leaderTrack != null) {
                leaderTrack.lastLatitude to leaderTrack.lastLongitude
            } else {
                localLat to localLon
            }
        }

        val leaderEntry = directory[leaderShortId]
        val leaderPin = LeaderPinUiModel(
            leaderShortId = leaderShortId,
            abbreviatedLabel = leaderEntry?.let {
                abbreviateRiderName(it.fullName, it.relation, it.shortId)
            } ?: "Líder",
            latitude = leaderPose.first,
            longitude = leaderPose.second
        )

        val updatedMarkers = mutableListOf<RiderMarkerUiModel>()

        // 1. Marcador propio (Yo)
        val selfEntry = directory[localShortId] ?: RiderDirectoryEntry(
            shortId = localShortId,
            fullName = "Juan Martínez",
            relation = RiderRelation.SAME_GROUP,
            rideRole = if (localShortId == leaderShortId) RiderRideRole.LEADER else RiderRideRole.NONE,
            markerColorHex = 0xFFFFB300
        )
        val prevSelf = previousByShortId[localShortId]
        val selfStatus = when {
            localSos -> RiderRouteStatus.SOS_ACTIVE
            isDeviatedFromCorridor(localLat, localLon) -> RiderRouteStatus.OFF_ROUTE_DEVIATED
            else -> RiderRouteStatus.ON_ROUTE
        }
        updatedMarkers.add(
            RiderMarkerUiModel(
                shortId = localShortId,
                fullName = selfEntry.fullName,
                nickname = selfEntry.nickname,
                relation = selfEntry.relation,
                rideRole = selfEntry.rideRole,
                routeStatus = selfStatus,
                markerColorHex = selfEntry.markerColorHex,
                prevLatitude = prevSelf?.displayLatitude ?: localLat,
                prevLongitude = prevSelf?.displayLongitude ?: localLon,
                targetLatitude = localLat,
                targetLongitude = localLon,
                displayLatitude = localLat,
                displayLongitude = localLon,
                speedKmh = localSpeedKmh,
                bearingDegrees = localBearing,
                batteryPercent = localBattery,
                distanceToLeaderMeters = haversineMeters(localLat, localLon, leaderPose.first, leaderPose.second),
                lastHops = 0,
                isSelf = true
            )
        )

        // 2. Marcadores de los peers recibidos por la mesh
        for ((peerId, track) in latestPeersFromMesh.entries.sortedBy { it.key }) {
            val entry = directory[peerId] ?: RiderDirectoryEntry(
                shortId = peerId,
                fullName = "Rider #${peerId.toInt()}",
                relation = RiderRelation.ALLIED_STRANGER,
                rideRole = RiderRideRole.NONE,
                markerColorHex = 0xFF29B6F6
            )
            val prev = previousByShortId[peerId]
            val routeStatus = when {
                track.isSos -> RiderRouteStatus.SOS_ACTIVE
                track.isLost -> RiderRouteStatus.OFF_MESH_LOST
                isDeviatedFromCorridor(track.lastLatitude, track.lastLongitude) -> RiderRouteStatus.OFF_ROUTE_DEVIATED
                else -> RiderRouteStatus.ON_ROUTE
            }
            updatedMarkers.add(
                RiderMarkerUiModel(
                    shortId = peerId,
                    fullName = entry.fullName,
                    nickname = entry.nickname,
                    relation = entry.relation,
                    rideRole = entry.rideRole,
                    routeStatus = routeStatus,
                    markerColorHex = entry.markerColorHex,
                    prevLatitude = prev?.displayLatitude ?: track.lastLatitude,
                    prevLongitude = prev?.displayLongitude ?: track.lastLongitude,
                    targetLatitude = track.lastLatitude,
                    targetLongitude = track.lastLongitude,
                    displayLatitude = track.lastLatitude,
                    displayLongitude = track.lastLongitude,
                    speedKmh = track.lastSpeedKmh,
                    bearingDegrees = 180f,
                    batteryPercent = track.lastBatteryPercent,
                    distanceToLeaderMeters = haversineMeters(
                        track.lastLatitude,
                        track.lastLongitude,
                        leaderPose.first,
                        leaderPose.second
                    ),
                    lastHops = track.lastHops,
                    isSelf = false
                )
            )
        }

        val connectedPeers = latestPeersFromMesh.values.count { !it.isLost }
        val lostPeers = latestPeersFromMesh.values.count { it.isLost }
        val totalPeers = latestPeersFromMesh.size

        val connectivityState = when {
            totalPeers == 0 -> LiveMapConnectivityState.ALONE
            lostPeers > 0 && lostPeers * 2 >= totalPeers -> LiveMapConnectivityState.MESH_PARTIAL_ISLANDS
            connectedPeers > 0 -> LiveMapConnectivityState.MESH_CONNECTED
            else -> LiveMapConnectivityState.ALONE
        }

        // Evaluar si la ventana de 10 s de "Seguir al líder" sigue activa o expiró
        val followUntil = current.followLeaderUntilEpochMs
        val stillFollowingLeader = current.cameraMode == CameraMode.FOLLOWING_LEADER &&
            followUntil != null && nowEpochMs < followUntil

        val effectiveCameraMode = if (current.cameraMode == CameraMode.FOLLOWING_LEADER && !stillFollowingLeader) {
            CameraMode.FREE
        } else {
            current.cameraMode
        }

        val remainingFollowSec = if (stillFollowingLeader) {
            (((followUntil!! - nowEpochMs) + 999L) / 1_000L).toInt().coerceAtLeast(1)
        } else {
            0
        }

        val (camLat, camLon) = when (effectiveCameraMode) {
            CameraMode.FOLLOWING_LEADER -> leaderPin.latitude to leaderPin.longitude
            CameraMode.CENTERED_ON_SELF -> localLat to localLon
            CameraMode.FREE -> current.cameraLatitude to current.cameraLongitude
        }

        _uiState.value = current.copy(
            localBatteryPercent = localBattery,
            connectedNodesCount = connectedPeers,
            lostNodesCount = lostPeers,
            connectivityState = connectivityState,
            cameraMode = effectiveCameraMode,
            cameraLatitude = camLat,
            cameraLongitude = camLon,
            followLeaderUntilEpochMs = if (stillFollowingLeader) followUntil else null,
            followLeaderRemainingSeconds = remainingFollowSec,
            leaderPin = leaderPin,
            markers = updatedMarkers,
            lastFrameUpdatedAtEpochMs = nowEpochMs,
            interpolationAlpha = 1.0f
        )
        return _uiState.value
    }

    /**
     * Interpola suavemente las posiciones de todos los marcadores entre `prev` y `target` (`alpha` en `0f..1f`)
     * para que los avatares se desplacen con fluidez entre paquetes BLE sin dar saltos bruscos.
     */
    fun interpolateMarkers(alpha: Float): LiveMapUiState {
        val clamped = alpha.coerceIn(0f, 1f)
        val current = _uiState.value
        val interpolated = current.markers.map { marker ->
            marker.copy(
                displayLatitude = lerp(marker.prevLatitude, marker.targetLatitude, clamped),
                displayLongitude = lerp(marker.prevLongitude, marker.targetLongitude, clamped)
            )
        }
        _uiState.value = current.copy(
            markers = interpolated,
            interpolationAlpha = clamped
        )
        return _uiState.value
    }

    /**
     * Decisión 3.3 (B): Al tocar "Seguir al líder", centra la cámara en el pin del líder durante 10 s
     * (`FOLLOW_LEADER_DURATION_MS = 10_000L`) y luego vuelve automáticamente a modo libre (`CameraMode.FREE`).
     */
    fun followLeader(
        nowEpochMs: Long = System.currentTimeMillis(),
        scheduleAutoRevert: Boolean = false
    ): LiveMapUiState {
        val current = _uiState.value
        val pin = current.leaderPin
        val targetLat = pin?.latitude ?: localLat
        val targetLon = pin?.longitude ?: localLon
        val untilEpoch = nowEpochMs + FOLLOW_LEADER_DURATION_MS

        _uiState.value = current.copy(
            cameraMode = CameraMode.FOLLOWING_LEADER,
            cameraLatitude = targetLat,
            cameraLongitude = targetLon,
            followLeaderUntilEpochMs = untilEpoch,
            followLeaderRemainingSeconds = 10,
            statusBannerMessage = "Siguiendo al líder por 10 s"
        )

        if (scheduleAutoRevert) {
            followTimeoutJob?.cancel()
            followTimeoutJob = scope.launch {
                delay(FOLLOW_LEADER_DURATION_MS)
                evaluateCameraTimeout(nowEpochMs + FOLLOW_LEADER_DURATION_MS)
            }
        }
        return _uiState.value
    }

    fun evaluateCameraTimeout(nowEpochMs: Long = System.currentTimeMillis()): LiveMapUiState {
        val current = _uiState.value
        val untilEpoch = current.followLeaderUntilEpochMs ?: return current
        if (current.cameraMode == CameraMode.FOLLOWING_LEADER && nowEpochMs >= untilEpoch) {
            _uiState.value = current.copy(
                cameraMode = CameraMode.FREE,
                followLeaderUntilEpochMs = null,
                followLeaderRemainingSeconds = 0,
                statusBannerMessage = "Mapa en modo libre (pin del líder visible)"
            )
        }
        return _uiState.value
    }

    fun centerOnMyLocation(): LiveMapUiState {
        val current = _uiState.value
        _uiState.value = current.copy(
            cameraMode = CameraMode.CENTERED_ON_SELF,
            cameraLatitude = localLat,
            cameraLongitude = localLon,
            followLeaderUntilEpochMs = null,
            followLeaderRemainingSeconds = 0,
            statusBannerMessage = "Centrado en mi ubicación"
        )
        return _uiState.value
    }

    fun selectRiderMarker(shortId: UShort?): LiveMapUiState {
        val current = _uiState.value
        val nextSelection = if (current.selectedRiderShortId == shortId) null else shortId
        _uiState.value = current.copy(selectedRiderShortId = nextSelection)
        return _uiState.value
    }

    fun toggleGlovesMode(enabled: Boolean): LiveMapUiState {
        _uiState.value = _uiState.value.copy(glovesMode = enabled)
        return _uiState.value
    }

    fun setGpsFixed(fixed: Boolean): LiveMapUiState {
        _uiState.value = _uiState.value.copy(gpsFixed = fixed)
        return _uiState.value
    }

    fun zoomBy(delta: Double): LiveMapUiState {
        val current = _uiState.value
        val nextZoom = (current.cameraZoom + delta).coerceIn(8.0, 18.0)
        _uiState.value = current.copy(cameraZoom = nextZoom)
        return _uiState.value
    }

    private fun isDeviatedFromCorridor(latitude: Double, longitude: Double): Boolean {
        val distanceToAxisMeters = haversineMeters(latitude, longitude, latitude, CORRIDOR_REFERENCE_LON)
        return distanceToAxisMeters > DEVIATION_THRESHOLD_METERS
    }
}

/**
 * ViewModel de Android para la pantalla `C1. Mapa en vivo`.
 */
class LiveMapViewModel : ViewModel() {
    val controller = LiveMapController(scope = viewModelScope)
    val uiState: StateFlow<LiveMapUiState> = controller.uiState

    init {
        // Inicializar con un frame base para que la pantalla C1 nunca muestre skeleton (SCREENS.md §3 C)
        controller.flushMapFrame(nowEpochMs = System.currentTimeMillis(), force = true)
    }
}
