package com.rutabikersync.app.feature.routes

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Paquete de fragmento de polilínea para transmisión por Bluetooth Low Energy Mesh (MTU ≤ 512B).
 */
data class RouteMeshChunk(
    val routeId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val expectedSha256: String,
    val payloadString: String
)

/**
 * Evento emitido cuando una ruta compartida por la malla es recibida y validada.
 */
data class RouteMeshReceivedEvent(
    val route: RouteAlternative,
    val senderName: String,
    val sha256Checksum: String,
    val isHashVerified: Boolean,
    val receivedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Relé de malla para compartir polilíneas y alternativas de rutas entre participantes
 * sin requerir conexión a internet ni servidores centrales.
 * Implementa fragmentación en chunks de 512B y verificación estricta de hash SHA-256 (R6.1).
 */
object RoutePolylineMeshRelay {

    const val MAX_CHUNK_PAYLOAD_CHARS = 480 // Margen seguro bajo el límite de MTU BLE de 512 bytes

    // Buffer de fragmentos en proceso de ensamblado: routeId -> Map<chunkIndex, payload>
    private val assemblyBuffer = mutableMapOf<String, MutableMap<Int, String>>()
    private val expectedChunksCount = mutableMapOf<String, Int>()
    private val expectedHashes = mutableMapOf<String, String>()
    private val senderNames = mutableMapOf<String, String>()

    // Estado reactivo de rutas compartidas recibidas
    private val _lastReceivedRoute = MutableStateFlow<RouteMeshReceivedEvent?>(null)
    val lastReceivedRoute: StateFlow<RouteMeshReceivedEvent?> = _lastReceivedRoute.asStateFlow()

    private val _routeEventBus = MutableSharedFlow<RouteMeshReceivedEvent>(extraBufferCapacity = 16)
    val routeEventBus: SharedFlow<RouteMeshReceivedEvent> = _routeEventBus.asSharedFlow()

    /**
     * Fragmenta una [RouteAlternative] en paquetes de tamaño apto para Bluetooth Mesh con su checksum SHA-256.
     */
    fun fragmentRouteForMesh(
        route: RouteAlternative,
        senderName: String = "Líder de Rodada"
    ): List<RouteMeshChunk> {
        // Serialización compacta de coordenadas: lat,lon;lat,lon;...
        val pointsSerialized = route.waypoints.joinToString(";") {
            "${String.format(java.util.Locale.US, "%.5f", it.latitude)},${String.format(java.util.Locale.US, "%.5f", it.longitude)}"
        }
        val routeMetadataSerialized = "${route.id}#${route.name}#${route.subtitle}#${route.distanceKm}#${route.estimatedMinutes}#${route.roadCondition}#$senderName|"
        val fullContent = routeMetadataSerialized + pointsSerialized

        val sha256 = computeSha256(pointsSerialized)

        val chunks = mutableListOf<RouteMeshChunk>()
        val totalLength = fullContent.length
        val numChunks = ((totalLength + MAX_CHUNK_PAYLOAD_CHARS - 1) / MAX_CHUNK_PAYLOAD_CHARS).coerceAtLeast(1)

        for (i in 0 until numChunks) {
            val start = i * MAX_CHUNK_PAYLOAD_CHARS
            val end = (start + MAX_CHUNK_PAYLOAD_CHARS).coerceAtMost(totalLength)
            val chunkPayload = fullContent.substring(start, end)
            chunks.add(
                RouteMeshChunk(
                    routeId = route.id,
                    chunkIndex = i,
                    totalChunks = numChunks,
                    expectedSha256 = sha256,
                    payloadString = chunkPayload
                )
            )
        }
        return chunks
    }

    /**
     * Procesa un fragmento entrante proveniente de un nodo de la malla.
     * Retorna el [RouteMeshReceivedEvent] si el paquete se completó y verificó, o null si aún faltan fragmentos.
     */
    fun processIncomingChunk(
        chunk: RouteMeshChunk,
        senderName: String = "Líder Convoy"
    ): RouteMeshReceivedEvent? {
        val routeId = chunk.routeId
        if (!assemblyBuffer.containsKey(routeId)) {
            assemblyBuffer[routeId] = mutableMapOf()
            expectedChunksCount[routeId] = chunk.totalChunks
            expectedHashes[routeId] = chunk.expectedSha256
            senderNames[routeId] = senderName
        }

        assemblyBuffer[routeId]?.put(chunk.chunkIndex, chunk.payloadString)

        val currentChunks = assemblyBuffer[routeId] ?: return null
        val totalRequired = expectedChunksCount[routeId] ?: chunk.totalChunks

        if (currentChunks.size == totalRequired) {
            // Todos los fragmentos recibidos: reensamblar en orden secuencial
            val fullString = StringBuilder()
            for (i in 0 until totalRequired) {
                fullString.append(currentChunks[i] ?: "")
            }

            // Limpiar buffers
            assemblyBuffer.remove(routeId)
            expectedChunksCount.remove(routeId)
            val expectedHash = expectedHashes.remove(routeId) ?: chunk.expectedSha256
            val sender = senderNames.remove(routeId) ?: senderName

            // Parsear metadatos y polilínea
            val assembledText = fullString.toString()
            val metaIndex = assembledText.indexOf('|')
            if (metaIndex == -1) return null

            val metaPart = assembledText.substring(0, metaIndex)
            val pointsPart = assembledText.substring(metaIndex + 1)

            // Validación estricta de hash SHA-256
            val calculatedHash = computeSha256(pointsPart)
            val isVerified = calculatedHash.equals(expectedHash, ignoreCase = true)

            // Reconstruir waypoints
            val waypoints = pointsPart.split(";").mapNotNull { coordStr ->
                val parts = coordStr.split(",")
                if (parts.size == 2) {
                    val lat = parts[0].toDoubleOrNull()
                    val lon = parts[1].toDoubleOrNull()
                    if (lat != null && lon != null) LatLngPoint(lat, lon) else null
                } else null
            }

            val metaFields = metaPart.split("#")
            val id = metaFields.getOrNull(0) ?: routeId
            val name = metaFields.getOrNull(1) ?: "Ruta de Malla"
            val subtitle = metaFields.getOrNull(2) ?: "Recibida vía BLE Mesh"
            val distKm = metaFields.getOrNull(3)?.toDoubleOrNull() ?: MotorcycleRoutingEngine.calculateTotalDistanceKm(waypoints)
            val estMin = metaFields.getOrNull(4)?.toIntOrNull() ?: 60
            val roadCond = metaFields.getOrNull(5) ?: "Asfalto de montaña"

            val reconstructedRoute = RouteAlternative(
                id = id,
                name = name,
                subtitle = subtitle,
                distanceKm = distKm,
                estimatedMinutes = estMin,
                roadCondition = roadCond,
                difficulty = "Convoy Oficial",
                isRecommended = true,
                waypoints = waypoints
            )

            val event = RouteMeshReceivedEvent(
                route = reconstructedRoute,
                senderName = sender,
                sha256Checksum = calculatedHash,
                isHashVerified = isVerified
            )

            _lastReceivedRoute.value = event
            _routeEventBus.tryEmit(event)
            return event
        }

        return null
    }

    /**
     * Purga todos los buffers de reensamblado en memoria al terminar la rodada.
     */
    fun clearOnRideFinished() {
        assemblyBuffer.clear()
        expectedChunksCount.clear()
        expectedHashes.clear()
        senderNames.clear()
        _lastReceivedRoute.value = null
    }

    private fun computeSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
