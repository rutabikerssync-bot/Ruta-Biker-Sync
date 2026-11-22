package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Origen de la posición que finalmente se pinta en el mapa. */
enum class TelemetrySource {
    BLE,
    HTTP_4G
}

/** Posición de un peer ya consolidada para la UI, sin depender de GATT ni de cifrado. */
data class FusedPeerPosition(
    val shortId: UShort,
    val userId: String?,
    val latitude: Double,
    val longitude: Double,
    val heading: Double,
    val speedKmh: Double,
    val observedAtMillis: Long,
    val source: TelemetrySource,
    val profileRedacted: Boolean
)

/** Instantánea BLE mínima que necesita el fusionador; la llena LiveMapViewModel desde PeerTrack. */
data class BlePeerSnapshot(
    val shortId: UShort,
    val latitude: Double,
    val longitude: Double,
    val observedAtMillis: Long,
    val heading: Double = 0.0,
    val speedKmh: Double = 0.0,
    val userId: String? = null
)

/**
 * Corte 1 de redundancia BLE ↔ 4G.
 *
 * BLE sigue siendo la fuente de verdad: este componente solo añade posiciones recibidas
 * por HTTP cuando no hay un peer BLE equivalente. Nunca escribe en el motor BLE, no
 * marca peers como perdidos y no interviene en GATT, cifrado, PTT ni liderazgo.
 */
class HybridTransportDispatcher(
    private val http: suspend (rideId: String) -> Result<List<ClubApiClient.RemoteTelemetrySample>>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pollIntervalMs: Long = 5_000L,
    private val ttlMs: Long = 30_000L,
    private val resolveShortId: (String) -> UShort? = { null }
) {
    private val _httpSamples = MutableStateFlow<List<ClubApiClient.RemoteTelemetrySample>>(emptyList())
    val httpSamples: StateFlow<List<ClubApiClient.RemoteTelemetrySample>> = _httpSamples.asStateFlow()

    private var pollJob: Job? = null

    fun start(rideId: String?, scope: CoroutineScope) {
        stop()
        if (rideId == null || runCatching { UUID.fromString(rideId) }.isFailure) return
        pollJob = scope.launch {
            while (isActive) {
                pollOnce(rideId)
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _httpSamples.value = emptyList()
    }

    /** Un sondeo HTTP. Si falla, conserva la última muestra válida; nunca bloquea el frame BLE. */
    suspend fun pollOnce(rideId: String) {
        val result = http(rideId)
        if (result.isSuccess) {
            _httpSamples.value = result.getOrThrow()
        }
    }

    /**
     * Fusión pura y determinista. Se puede llamar en cada frame del mapa sin red.
     * Los peer BLE con el mismo shortId resuelto ganan sobre la muestra HTTP.
     */
    fun fuse(
        blePeers: List<BlePeerSnapshot>,
        nowEpochMs: Long = clock(),
        httpSamples: List<ClubApiClient.RemoteTelemetrySample> = _httpSamples.value
    ): List<FusedPeerPosition> {
        val merged = mutableListOf<FusedPeerPosition>()
        val occupiedShortIds = mutableSetOf<UShort>()

        blePeers.forEach { peer ->
            if (!isRenderable(peer.latitude, peer.longitude)) return@forEach
            if (!occupiedShortIds.add(peer.shortId)) return@forEach
            merged.add(
                FusedPeerPosition(
                    shortId = peer.shortId,
                    userId = peer.userId,
                    latitude = peer.latitude,
                    longitude = peer.longitude,
                    heading = peer.heading,
                    speedKmh = peer.speedKmh,
                    observedAtMillis = peer.observedAtMillis,
                    source = TelemetrySource.BLE,
                    profileRedacted = peer.userId == null
                )
            )
        }

        var nextSyntheticShortId = SYNTHETIC_SHORT_ID_START
        httpSamples.forEach { sample ->
            val age = nowEpochMs - sample.observedAtMillis
            if (age !in 0..ttlMs || !isRenderable(sample.latitude, sample.longitude)) return@forEach

            val resolvedShortId = resolveShortId(sample.userId)
            if (resolvedShortId != null && resolvedShortId in occupiedShortIds) return@forEach

            val shortId = when {
                resolvedShortId != null && resolvedShortId !in occupiedShortIds -> resolvedShortId
                else -> {
                    while (nextSyntheticShortId <= SYNTHETIC_SHORT_ID_END &&
                        nextSyntheticShortId.toUShort() in occupiedShortIds
                    ) {
                        nextSyntheticShortId++
                    }
                    nextSyntheticShortId++.toUShort()
                }
            }
            if (!occupiedShortIds.add(shortId)) return@forEach

            merged.add(
                FusedPeerPosition(
                    shortId = shortId,
                    userId = sample.userId,
                    latitude = sample.latitude,
                    longitude = sample.longitude,
                    heading = sample.heading,
                    speedKmh = sample.speedKmh,
                    observedAtMillis = sample.observedAtMillis,
                    source = TelemetrySource.HTTP_4G,
                    profileRedacted = resolvedShortId == null
                )
            )
        }

        return merged
    }

    private fun isRenderable(latitude: Double, longitude: Double): Boolean {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        return latitude != 0.0 || longitude != 0.0
    }

    private companion object {
        const val SYNTHETIC_SHORT_ID_START = 60_000
        const val SYNTHETIC_SHORT_ID_END = 65_534
    }
}
