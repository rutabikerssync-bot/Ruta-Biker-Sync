package com.rutabikersync.app.feature.livemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rutabikersync.app.core.mesh.LocalGpsPositionPolicy
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.mesh.MeshTelemetry
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
    val markerColorHex: Long = 0xFF00E676,
    val avatarUrl: String? = null,
    val avatarBase64: String? = null,
    val roleLabel: String = "Desconocido",
    val userId: String? = null,
    /** groupId real del rider (roster backend). Nunca se inventa: null = desconocido. */
    val groupId: String? = null
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
    // FIX distancia al líder: sin fix GPS confirmado no se muestra ninguna distancia.
    val gpsFixed: Boolean = false,
    val localBatteryPercent: Int = 88,
    val connectedNodesCount: Int = 0,
    // FIX contador HUD: riders activos renderizados (local + peers con señal).
    val ridersCount: Int = 1,
    val lostNodesCount: Int = 0,
    val connectivityState: LiveMapConnectivityState = LiveMapConnectivityState.CONNECTING,
    val cameraMode: CameraMode = CameraMode.FREE,
    // M2 — Coordenadas iniciales a 0.0 (UI_AUDIT M2): sin pin falso de CDMX.
    //       Al iniciar GPS real la cámara se centrará en la posición del dispositivo.
    val cameraLatitude: Double = 0.0,
    val cameraLongitude: Double = 0.0,
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
    /** groupId del usuario local (perfil/roster). null = privacidad por defecto. */
    private var localGroupId: String? = null

    fun setLocalGroupId(groupId: String?) {
        localGroupId = groupId?.trim()?.takeIf { it.isNotBlank() }
        flushMapFrame(System.currentTimeMillis(), force = true)
    }

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

    /**
     * Perfil cacheado en SharedPreferences (offline). Nunca inventa datos: si no hay alias,
     * se usa el fallback honrado "Piloto #X". La foto se guarda como base64 en el perfil local.
     */
    fun applyCachedLocalProfile(
        fullName: String?,
        avatarBase64: String?,
        avatarUrl: String?,
        pinColorHex: String?,
        userId: String?
    ) {
        val current = directory[localShortId]
        val safeName = fullName?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Piloto #${localShortId.toInt()}"
        directory[localShortId] = RiderDirectoryEntry(
            shortId = localShortId,
            fullName = safeName,
            nickname = current?.nickname,
            relation = RiderRelation.SAME_GROUP,
            rideRole = current?.rideRole
                ?: if (localShortId == leaderShortId) RiderRideRole.LEADER else RiderRideRole.NONE,
            markerColorHex = parseHexColor(pinColorHex)
                ?: current?.markerColorHex ?: 0xFFFFB300,
            avatarUrl = avatarUrl ?: current?.avatarUrl,
            avatarBase64 = avatarBase64?.takeIf { it.isNotBlank() } ?: current?.avatarBase64,
            roleLabel = current?.roleLabel ?: if (localShortId == leaderShortId) "Punta" else "Desconocido",
            userId = userId ?: current?.userId
        )
        flushMapFrame(System.currentTimeMillis(), force = true)
    }

    /**
     * Aplica el roster del backend solo al peer local, porque el backend no conoce `shortId`.
     * Los peers remotos siguen con "Piloto #X" hasta que exista un mapa userId↔shortId real.
     */
    fun applyBackendRoster(entries: List<RideRosterUiEntry>, localUserId: String?) {
        val own = entries.firstOrNull { entry -> entry.userId == localUserId } ?: return
        val current = directory[localShortId]
        // El roster backend es la fuente de verdad del club local cuando lo reporta.
        if (!own.groupId.isNullOrBlank()) localGroupId = own.groupId
        directory[localShortId] = RiderDirectoryEntry(
            shortId = localShortId,
            fullName = own.displayName,
            nickname = current?.nickname,
            relation = RiderRelation.SAME_GROUP,
            rideRole = when {
                own.roleInConvoy.equals("Punta", ignoreCase = true) -> RiderRideRole.LEADER
                own.roleInConvoy.equals("Scout", ignoreCase = true) -> RiderRideRole.SCOUT
                own.roleInConvoy.equals("Sweep", ignoreCase = true) -> RiderRideRole.SWEEP
                current?.rideRole != null && current.rideRole != RiderRideRole.NONE -> current.rideRole
                else -> RiderRideRole.NONE
            },
            markerColorHex = own.mapColorHex ?: current?.markerColorHex ?: 0xFFFFB300,
            avatarUrl = own.avatarUrl ?: current?.avatarUrl,
            avatarBase64 = current?.avatarBase64,
            roleLabel = own.roleInConvoy,
            userId = own.userId,
            groupId = own.groupId ?: current?.groupId
        )
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

        private const val FALLBACK_CDMX_LATITUDE = 19.4326
        private const val FALLBACK_CDMX_LONGITUDE = -99.1332

        /** Un peer sin fix real no se dibuja sobre una coordenada centinela/CDMX. */
        internal fun isRenderablePeerPosition(latitude: Double, longitude: Double): Boolean {
            if (!MeshTelemetry.isValidPosition(latitude, longitude)) return false
            return !(latitude == FALLBACK_CDMX_LATITUDE && longitude == FALLBACK_CDMX_LONGITUDE)
        }

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
    // Posición local del dispositivo: inicia en 0,0 hasta recibir GPS real (UI_AUDIT M2)
    private var localLat: Double = 0.0
    private var localLon: Double = 0.0
    private var localSpeedKmh: Int = 75
    private var localBearing: Float = 180f
    private var localBattery: Int = 88
    private var localSos: Boolean = false
    // Fecha real del último fix recibido del sistema. Las fixtures sintéticas sin fecha
    // mantienen su comportamiento previo; la cabina real siempre proporciona la fecha OS.
    private var localPositionObservedAtEpochMs: Long? = null

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
        isSos: Boolean = false,
        observedAtEpochMs: Long? = null
    ) {
        // (0,0) y NaN son centinelas, nunca una posición: marcar "sin fix" y conservar
        // la última coordenada real conocida en lugar de contaminar los cálculos.
        if (!MeshTelemetry.isValidPosition(latitude, longitude)) {
            setGpsFixed(false)
            return
        }
        localLat = latitude
        localLon = longitude
        localSpeedKmh = speedKmh
        localBearing = bearingDegrees
        localBattery = batteryPercent
        localSos = isSos
        observedAtEpochMs?.let { localPositionObservedAtEpochMs = it }
        setGpsFixed(true)
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

        // FIX distancia al líder: si no hay posición válida del líder NO se inventa un
        // punto de referencia (antes se caía a la posición local y se mostraba "0 m").
        val localPositionIsFresh = localPositionObservedAtEpochMs?.let {
            LocalGpsPositionPolicy.isFresh(it, nowEpochMs)
        } != false
        val localPositionValid = MeshTelemetry.isValidPosition(localLat, localLon)
        val leaderPose: Pair<Double, Double>? = if (leaderShortId == localShortId) {
            if (localPositionValid) localLat to localLon else null
        } else {
            val leaderTrack = latestPeersFromMesh[leaderShortId]
            if (leaderTrack != null && !leaderTrack.isLost &&
                MeshTelemetry.isValidPosition(leaderTrack.lastLatitude, leaderTrack.lastLongitude)
            ) {
                leaderTrack.lastLatitude to leaderTrack.lastLongitude
            } else {
                null
            }
        }

        val leaderEntry = directory[leaderShortId]
        val leaderPin = leaderPose?.let { pose ->
            LeaderPinUiModel(
                leaderShortId = leaderShortId,
                abbreviatedLabel = leaderEntry?.let {
                    abbreviateRiderName(it.fullName, it.relation, it.shortId)
                } ?: "Líder",
                latitude = pose.first,
                longitude = pose.second
            )
        }

        val updatedMarkers = mutableListOf<RiderMarkerUiModel>()

        // 1. Marcador propio (Yo)
        val selfEntry = directory[localShortId] ?: RiderDirectoryEntry(
            shortId = localShortId,
            fullName = "Piloto #${localShortId.toInt()}",
            relation = RiderRelation.SAME_GROUP,
            rideRole = if (localShortId == leaderShortId) RiderRideRole.LEADER else RiderRideRole.NONE,
            markerColorHex = 0xFFFFB300,
            roleLabel = if (localShortId == leaderShortId) "Punta" else "Desconocido"
        )
        val prevSelf = previousByShortId[localShortId]
        val selfStatus = when {
            localSos -> RiderRouteStatus.SOS_ACTIVE
            localShortId != leaderShortId && isDeviatedFromCorridor(localLat, localLon) -> RiderRouteStatus.OFF_ROUTE_DEVIATED
            else -> RiderRouteStatus.ON_ROUTE
        }
        // El piloto local siempre ve su propio perfil; no hay tercero al que proteger.
        val selfCanShowReal = PeerProfilePrivacy.canShowRealProfile(
            isSelf = true,
            peerGroupId = selfEntry.groupId,
            localGroupId = localGroupId
        )
        if (localPositionValid) {
            updatedMarkers.add(
                RiderMarkerUiModel(
                    shortId = localShortId,
                    fullName = PeerProfilePrivacy.visibleName(localShortId, selfEntry.fullName, selfCanShowReal),
                    nickname = if (selfCanShowReal) selfEntry.nickname else null,
                    relation = if (selfCanShowReal) selfEntry.relation else RiderRelation.ALLIED_STRANGER,
                    rideRole = if (selfCanShowReal) selfEntry.rideRole else RiderRideRole.NONE,
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
                    distanceToLeaderMeters = if (leaderPose != null) {
                        haversineMeters(localLat, localLon, leaderPose.first, leaderPose.second)
                    } else {
                        null
                    },
                    lastHops = 0,
                    isSelf = true,
                    avatarUrl = if (selfCanShowReal) selfEntry.avatarUrl else null,
                    avatarBase64 = if (selfCanShowReal) selfEntry.avatarBase64 else null,
                    roleLabel = if (selfCanShowReal) selfEntry.roleLabel else "Desconocido",
                    groupId = selfEntry.groupId,
                    profileRedacted = !selfCanShowReal
                )
            )
        }

        // 2. Marcadores de los peers recibidos por la mesh. Sin fix GPS real no
        // se coloca un pin geográfico artificial sobre CDMX u otro fallback.
        for ((peerId, track) in latestPeersFromMesh.entries.sortedBy { it.key }) {
            if (!isRenderablePeerPosition(track.lastLatitude, track.lastLongitude)) continue
            val entry = directory[peerId] ?: RiderDirectoryEntry(
                shortId = peerId,
                fullName = "Piloto #${peerId.toInt()}",
                relation = RiderRelation.ALLIED_STRANGER,
                rideRole = RiderRideRole.NONE,
                markerColorHex = 0xFF29B6F6,
                roleLabel = "Desconocido"
            )
            val prev = previousByShortId[peerId]
            val routeStatus = when {
                track.isSos -> RiderRouteStatus.SOS_ACTIVE
                track.isLost -> RiderRouteStatus.OFF_MESH_LOST
                isDeviatedFromCorridor(track.lastLatitude, track.lastLongitude) -> RiderRouteStatus.OFF_ROUTE_DEVIATED
                else -> RiderRouteStatus.ON_ROUTE
            }
            // FIX privacidad: sin groupId idéntico al local, el peer es desconocido.
            val canShowReal = PeerProfilePrivacy.canShowRealProfile(
                isSelf = false,
                peerGroupId = entry.groupId,
                localGroupId = localGroupId
            )
            updatedMarkers.add(
                RiderMarkerUiModel(
                    shortId = peerId,
                    fullName = PeerProfilePrivacy.visibleName(peerId, entry.fullName, canShowReal),
                    nickname = if (canShowReal) entry.nickname else null,
                    relation = if (canShowReal) entry.relation else RiderRelation.ALLIED_STRANGER,
                    rideRole = if (canShowReal) entry.rideRole else RiderRideRole.NONE,
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
                    distanceToLeaderMeters = if (
                        leaderPose != null &&
                        !track.isLost &&
                        MeshTelemetry.isValidPosition(track.lastLatitude, track.lastLongitude)
                    ) {
                        haversineMeters(
                            track.lastLatitude,
                            track.lastLongitude,
                            leaderPose.first,
                            leaderPose.second
                        )
                    } else {
                        null
                    },
                    lastHops = track.lastHops,
                    isSelf = false,
                    avatarUrl = if (canShowReal) entry.avatarUrl else null,
                    avatarBase64 = if (canShowReal) entry.avatarBase64 else null,
                    roleLabel = if (canShowReal) entry.roleLabel else "Desconocido",
                    groupId = entry.groupId,
                    profileRedacted = !canShowReal
                )
            )
        }

        val connectedPeers = latestPeersFromMesh.values.count { !it.isLost }
        val lostPeers = latestPeersFromMesh.values.count { it.isLost }
        val totalPeers = latestPeersFromMesh.size

        // El HUD usa los mismos riders activos y con posición válida que pinta el mapa.
        // Sin fix local no se puede afirmar "1 rider" ni dibujar un avatar en (0,0).
        val ridersCount = updatedMarkers.count {
            it.routeStatus != RiderRouteStatus.OFF_MESH_LOST
        }

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
            CameraMode.FOLLOWING_LEADER ->
                (leaderPin?.latitude ?: localLat) to (leaderPin?.longitude ?: localLon)
            CameraMode.CENTERED_ON_SELF -> localLat to localLon
            CameraMode.FREE -> current.cameraLatitude to current.cameraLongitude
        }

        _uiState.value = current.copy(
            gpsFixed = localPositionValid,
            localBatteryPercent = localBattery,
            connectedNodesCount = connectedPeers,
            ridersCount = ridersCount,
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

    var corridorReferenceLon: Double? = null

    private fun isDeviatedFromCorridor(latitude: Double, longitude: Double): Boolean {
        val refLon = corridorReferenceLon ?: CORRIDOR_REFERENCE_LON
        val distanceToAxisMeters = haversineMeters(latitude, longitude, latitude, refLon)
        return distanceToAxisMeters > DEVIATION_THRESHOLD_METERS
    }
}

/**
 * ViewModel de Android para la pantalla `C1. Mapa en vivo`.
 */
class LiveMapViewModel : ViewModel() {
    val controller = LiveMapController(scope = viewModelScope, initialDirectory = emptyList())
    val uiState: StateFlow<LiveMapUiState> = controller.uiState

    init {
        // Inicializar con un frame base para que la pantalla C1 nunca muestre skeleton (SCREENS.md §3 C)
        controller.flushMapFrame(nowEpochMs = System.currentTimeMillis(), force = true)
    }
}
