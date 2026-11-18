package com.rutabikersync.app.core.mesh.sos

import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Suite de pruebas unitarias para el Puente BLE Mesh a Radar SOS HTTP (Fase 7.1.5.5).
 * Valida deduplicación, verificación de consentimiento del motero puente y cola persistente.
 */
class BleMeshSosBridgeTest {

    private lateinit var fakeDao: FakePendingSosRetryDao

    @Before
    fun setUp() {
        fakeDao = FakePendingSosRetryDao()
        BleMeshSosBridge.clearCacheForTesting()
    }

    private fun createFakeSosPacket(
        senderShortId: UShort = 101u,
        sequenceNum: UShort = 1u,
        timestampEpochSec: Int = 1700000000,
        latRaw: Int = 104064000, // 10.4064
        lngRaw: Int = -672886000 // -67.2886
    ): SosPacket {
        val header = MeshHeader(
            clubNetworkId = 1u,
            senderShortId = senderShortId,
            packetType = MeshHeader.TYPE_SOS,
            initTtl = 5,
            currentTtl = 5,
            sequenceNum = sequenceNum
        )
        return SosPacket(
            header = header,
            latitudeRaw = latRaw,
            longitudeRaw = lngRaw,
            incidentCode = 1,
            bloodType = 1,
            timestampEpochSec = timestampEpochSec,
            signature = ByteArray(64) { 0x01 },
            hopCount = 0
        )
    }

    @Test
    fun `test 1 - Deduplicacion en memoria suprime paquetes identicos recientes`() = runBlocking {
        val sos = createFakeSosPacket(senderShortId = 200u, sequenceNum = 5u)
        val now = 1_000_000L

        // Primer procesamiento: con consentimiento falso para ver que pasa el filtro de deduplicación
        val status1 = BleMeshSosBridge.processSosPacket(
            sos = sos,
            isRelayConsentGranted = false, // Provoca SUPPRESSED_NO_CONSENT en primera instancia
            apiClient = ClubApiClient.getInstance(),
            dao = fakeDao,
            nowEpochMs = now
        )
        assertEquals(BleMeshSosBridge.BridgeStatus.SUPPRESSED_NO_CONSENT, status1)

        // Ahora probamos con consentimiento = true
        val status2 = BleMeshSosBridge.processSosPacket(
            sos = sos,
            isRelayConsentGranted = true,
            apiClient = ClubApiClient.getInstance(),
            dao = fakeDao,
            nowEpochMs = now
        )
        // Entra a la cola o intenta envío
        assertTrue(
            status2 == BleMeshSosBridge.BridgeStatus.SUCCESS_DIRECT ||
            status2 == BleMeshSosBridge.BridgeStatus.QUEUED_FOR_RETRY
        )

        // Segundo envío IDÉNTICO dentro de la ventana de 5 min debe ser ignorado por deduplicación
        val statusDuplicate = BleMeshSosBridge.processSosPacket(
            sos = sos,
            isRelayConsentGranted = true,
            apiClient = ClubApiClient.getInstance(),
            dao = fakeDao,
            nowEpochMs = now + 10_000L
        )
        assertEquals(BleMeshSosBridge.BridgeStatus.DUPLICATE_IGNORED, statusDuplicate)
    }

    @Test
    fun `test 2 - Supresion estricta si el motero puente NO tiene consentimiento de paquete de emergencia`() = runBlocking {
        val sos = createFakeSosPacket(senderShortId = 300u, sequenceNum = 1u)

        val status = BleMeshSosBridge.processSosPacket(
            sos = sos,
            isRelayConsentGranted = false, // Motero puente denegó consentimiento R15
            apiClient = ClubApiClient.getInstance(),
            dao = fakeDao,
            nowEpochMs = 2_000_000L
        )

        assertEquals(BleMeshSosBridge.BridgeStatus.SUPPRESSED_NO_CONSENT, status)
        // Asegurar que nada se encoló en el DAO
        assertEquals(0, fakeDao.countAll())
    }

    @Test
    fun `test 3 - Si la red falla el SOS se persiste en la cola de reintentos Room`() = runBlocking {
        val sos = createFakeSosPacket(senderShortId = 400u, sequenceNum = 10u)
        val now = 3_000_000L

        // Llamada con fallo garantizado hacia DAO
        val status = BleMeshSosBridge.processSosPacket(
            sos = sos,
            isRelayConsentGranted = true,
            apiClient = ClubApiClient.getInstance(),
            dao = fakeDao,
            nowEpochMs = now
        )

        // Como ClubApiClient en test unitario JVM no tiene servidor vivo o arroja IO, se encola en Room
        assertEquals(BleMeshSosBridge.BridgeStatus.QUEUED_FOR_RETRY, status)
        assertEquals(1, fakeDao.countAll())

        val item = fakeDao.items.first()
        assertEquals("ble_mesh", item.source)
        assertEquals(BleMeshSosBridge.buildMeshPacketId(sos), item.meshPacketId)
        assertEquals(400, item.senderShortId)
        assertEquals(now, item.createdAtEpochMs)
    }

    @Test
    fun `test 4 - Purga automatica de paquetes con antiguedad mayor a 5 minutos (TTL)`() = runBlocking {
        val now = 10_000_000L
        val expiredTime = now - BleMeshSosBridge.MAX_RETRY_TTL_MS - 5000L // 5 min y 5 seg atrás
        val validTime = now - 60_000L // 1 minuto atrás

        fakeDao.insert(
            PendingSosRetryEntity(
                id = "expired_1",
                meshPacketId = "mesh_1",
                senderShortId = 1,
                latitude = 10.0,
                longitude = -66.0,
                hasMedicalSheet = false,
                createdAtEpochMs = expiredTime
            )
        )
        fakeDao.insert(
            PendingSosRetryEntity(
                id = "valid_1",
                meshPacketId = "mesh_2",
                senderShortId = 2,
                latitude = 10.0,
                longitude = -66.0,
                hasMedicalSheet = false,
                createdAtEpochMs = validTime
            )
        )

        assertEquals(2, fakeDao.countAll())

        // Ejecutar barrido de reintento/purga con tiempo 'now'
        fakeDao.purgeExpired(now - BleMeshSosBridge.MAX_RETRY_TTL_MS)

        // El expirado debió eliminarse, el válido debe permanecer
        assertEquals(1, fakeDao.countAll())
        assertEquals("valid_1", fakeDao.items.first().id)
    }
}

class FakePendingSosRetryDao : PendingSosRetryDao {
    val items = mutableListOf<PendingSosRetryEntity>()

    override suspend fun insert(entity: PendingSosRetryEntity) {
        items.removeAll { it.id == entity.id || it.meshPacketId == entity.meshPacketId }
        items.add(entity)
    }

    override suspend fun getActivePending(minValidTimestamp: Long): List<PendingSosRetryEntity> {
        return items.filter { it.createdAtEpochMs >= minValidTimestamp }
    }

    override suspend fun delete(id: String, meshPacketId: String) {
        items.removeAll { it.id == id || it.meshPacketId == meshPacketId }
    }

    override suspend fun purgeExpired(expirationTimestamp: Long): Int {
        val countBefore = items.size
        items.removeAll { it.createdAtEpochMs < expirationTimestamp }
        return countBefore - items.size
    }

    override suspend fun update(entity: PendingSosRetryEntity) {
        val idx = items.indexOfFirst { it.id == entity.id }
        if (idx >= 0) {
            items[idx] = entity
        }
    }

    override suspend fun countAll(): Int = items.size
}
