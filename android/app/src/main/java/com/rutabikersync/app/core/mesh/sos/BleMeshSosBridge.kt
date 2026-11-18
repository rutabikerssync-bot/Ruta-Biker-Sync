package com.rutabikersync.app.core.mesh.sos

import android.content.Context
import android.util.Log
import com.rutabikersync.app.core.gamification.AppDatabase
import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.feature.groups.ClubRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Puente (Bridge) entre la red de radiofrecuencia local BLE Mesh y el radar SOS HTTP del servidor (Fase 7.1.5.5).
 *
 * Cuando un motero accidentado emite un [SosPacket] off-grid sin conexión a internet, cualquier compañero
 * del convoy que cuente con conectividad celular y haya otorgado consentimiento de paquete de emergencia (R15)
 * actúa como repetidor puente, reenviando el SOS a la nube (POST /v1/sos).
 *
 * Garantías:
 * 1. Verificación obligatoria de consentimiento del motero puente (R15).
 * 2. Deduplicación LRU en memoria para evitar tormentas de reenvío en malla.
 * 3. Deduplicación idempotente en backend mediante [meshPacketId].
 * 4. Cola de reintentos local persistente con Room ante caídas de cobertura (reintento cada 30s hasta 5 minutos).
 */
object BleMeshSosBridge {

    private const val TAG = "BleMeshSosBridge"
    private const val RETRY_INTERVAL_MS = 30_000L
    const val MAX_RETRY_TTL_MS = 300_000L // 5 minutos

    // Deduplicación en memoria: guarda meshPacketId -> timestamp de recepción
    private val processedMeshPackets = ConcurrentHashMap<String, Long>()

    enum class BridgeStatus {
        SUCCESS_DIRECT,
        QUEUED_FOR_RETRY,
        SUPPRESSED_NO_CONSENT,
        DUPLICATE_IGNORED,
        EXPIRED_DROPPED
    }

    /**
     * Identificador canónico y determinista del paquete SOS generado por malla.
     */
    fun buildMeshPacketId(sos: SosPacket): String {
        return "mesh_sos_${sos.header.senderShortId}_${sos.header.sequenceNum}_${sos.timestampEpochSec}"
    }

    /**
     * Entrada principal invocada por [com.rutabikersync.app.core.mesh.BleMeshService] al recibir un SosPacket de malla.
     */
    fun onMeshSosReceived(
        context: Context,
        sos: SosPacket,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val isConsentGranted = ClubRepository.getInstance().consentsSummary.value.emergencyPackageGranted
            val db = try { AppDatabase.getInstance(context) } catch (_: Throwable) { null }
            val dao = db?.pendingSosRetryDao()
            val apiClient = ClubApiClient.getInstance()

            processSosPacket(
                sos = sos,
                isRelayConsentGranted = isConsentGranted,
                apiClient = apiClient,
                dao = dao,
                nowEpochMs = System.currentTimeMillis(),
                scope = scope
            )
        }
    }

    /**
     * Lógica pura desacoplada para procesamiento y testeo unitario exhaustivo.
     */
    suspend fun processSosPacket(
        sos: SosPacket,
        isRelayConsentGranted: Boolean,
        apiClient: ClubApiClient,
        dao: PendingSosRetryDao?,
        nowEpochMs: Long = System.currentTimeMillis(),
        scope: CoroutineScope? = null
    ): BridgeStatus {
        val meshPacketId = buildMeshPacketId(sos)

        // 1. Deduplicación en memoria (ventana de 5 minutos)
        cleanExpiredCache(nowEpochMs)
        if (processedMeshPackets.containsKey(meshPacketId)) {
            Log.d(TAG, "Deduplicación activa: SosPacket $meshPacketId ya procesado recientemente. Ignorando.")
            return BridgeStatus.DUPLICATE_IGNORED
        }

        // 2. Verificación estricta del consentimiento del MOTERO PUENTE (Observación 3 / RULES R15)
        if (!isRelayConsentGranted) {
            Log.w(TAG, "Reenvío suprimido: El motero puente no ha otorgado consentimiento emergency_package (R15).")
            return BridgeStatus.SUPPRESSED_NO_CONSENT
        }

        // Marcar como procesado en caché
        processedMeshPackets[meshPacketId] = nowEpochMs

        // 3. Intento de despacho HTTP inmediato
        val triggerResult = try {
            apiClient.triggerSos(
                lat = sos.latitude,
                lng = sos.longitude,
                hasMedicalSheet = sos.incident.code != 0.toByte(),
                radiusKm = 5.0,
                notes = "Puente BLE Mesh desde Piloto #${sos.header.senderShortId}",
                source = "ble_mesh",
                meshPacketId = meshPacketId
            )
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (triggerResult.getOrNull()?.isSuccess == true) {
            Log.i(TAG, "SOS puenteado exitosamente a la nube para meshPacketId=$meshPacketId")
            dao?.delete("", meshPacketId)
            return BridgeStatus.SUCCESS_DIRECT
        }

        // 4. Si falló la red, encolar en Room para reintentos periódicos (hasta 5 minutos)
        Log.w(TAG, "Fallo de conexión al enviar SOS ($meshPacketId). Encolando en cola de reintentos Room...")
        val retryEntity = PendingSosRetryEntity(
            meshPacketId = meshPacketId,
            senderShortId = sos.header.senderShortId.toInt(),
            latitude = sos.latitude,
            longitude = sos.longitude,
            hasMedicalSheet = sos.incident.code != 0.toByte(),
            radiusKm = 5.0,
            notes = "Puente BLE Mesh desde Piloto #${sos.header.senderShortId}",
            source = "ble_mesh",
            createdAtEpochMs = nowEpochMs,
            lastAttemptEpochMs = nowEpochMs,
            retryCount = 1
        )
        dao?.insert(retryEntity)

        // Iniciar bucle de reintento en background si hay scope disponible
        scope?.let { startRetryLoop(it, dao, apiClient) }

        return BridgeStatus.QUEUED_FOR_RETRY
    }

    /**
     * Ejecuta una ronda de reintentos sobre la cola persistente Room.
     * Descarta cualquier paquete con antigüedad superior a 5 minutos.
     */
    suspend fun executeRetryPass(
        dao: PendingSosRetryDao,
        apiClient: ClubApiClient,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int {
        val minValidTimestamp = nowEpochMs - MAX_RETRY_TTL_MS
        dao.purgeExpired(minValidTimestamp)

        val pending = dao.getActivePending(minValidTimestamp)
        var successCount = 0

        for (item in pending) {
            val res = try {
                apiClient.triggerSos(
                    lat = item.latitude,
                    lng = item.longitude,
                    hasMedicalSheet = item.hasMedicalSheet,
                    radiusKm = item.radiusKm,
                    notes = item.notes,
                    source = item.source,
                    meshPacketId = item.meshPacketId
                )
            } catch (_: Exception) {
                Result.failure(Exception("Network error on retry"))
            }

            if (res.getOrNull()?.isSuccess == true) {
                dao.delete(item.id, item.meshPacketId)
                successCount++
            } else {
                dao.update(
                    item.copy(
                        lastAttemptEpochMs = nowEpochMs,
                        retryCount = item.retryCount + 1
                    )
                )
            }
        }
        return successCount
    }

    private fun startRetryLoop(scope: CoroutineScope, dao: PendingSosRetryDao?, apiClient: ClubApiClient) {
        if (dao == null) return
        scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (isActive && attempts < 10) { // 10 intentos x 30s = 300s (5 minutos)
                delay(RETRY_INTERVAL_MS)
                attempts++
                val now = System.currentTimeMillis()
                val minValid = now - MAX_RETRY_TTL_MS
                dao.purgeExpired(minValid)
                val remaining = dao.getActivePending(minValid)
                if (remaining.isEmpty()) break
                executeRetryPass(dao, apiClient, now)
            }
        }
    }

    private fun cleanExpiredCache(nowEpochMs: Long) {
        val iterator = processedMeshPackets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowEpochMs - entry.value > MAX_RETRY_TTL_MS) {
                iterator.remove()
            }
        }
    }

    /**
     * Limpia la memoria para tests.
     */
    fun clearCacheForTesting() {
        processedMeshPackets.clear()
    }
}
