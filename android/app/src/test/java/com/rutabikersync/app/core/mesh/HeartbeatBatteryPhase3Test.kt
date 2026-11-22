package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.power.BatteryAwareness
import com.rutabikersync.app.core.power.ManufacturerBatteryGuide
import com.rutabikersync.app.core.power.OemBatteryVendor
import com.rutabikersync.app.core.power.PowerProfile
import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias JVM del Bloque 3.5 (`HeartbeatScheduler` + `PeerHealthMonitor` + `BatteryAwareness` + `ManufacturerBatteryGuide`):
 * 1. Convoy de 5 riders sobre `SimulatedBleMedium`: pérdida tras 5 latidos (~15 s), histéresis de reconexión
 *    (requiere 2 latidos consecutivos para volver a `ONLINE`) y detección de islas que solo anuncia por TTS
 *    si la partición dura más de 60 segundos (Decisión 3.4 C).
 * 2. `BatteryAwareness` en `RideSessionController`: modo ahorro automático pero configurable a < 20% (Decisión 3.1 C),
 *    override manual del usuario y modo supervivencia a <= 10% / 5% (heartbeat cada 10 s, mapa congelado, solo SOS — Decisión 3.3 B).
 * 3. `ManufacturerBatteryGuide`: detección de Xiaomi/Redmi (HyperOS), Tecno (HiOS), Huawei (EMUI), Samsung (One UI),
 *    Oppo y Vivo como guía recomendada no obligatoria (Decisión 3.2 B).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatBatteryPhase3Test {

    private val secretKey = "rbs-phase3-hb-battery-key-32b!!!".encodeToByteArray()

    @Test
    fun `Bloque 3_5 convoy de 5 riders verifica histeresis de 2 latidos e islas anunciadas solo tras 60s`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val nodeIds = listOf<UShort>(1u, 2u, 3u, 4u, 5u)
        medium.connectChain(nodeIds)

        val engines = nodeIds.associateWith { id ->
            MeshRelayEngine(
                localShortId = id,
                transport = medium.createNodeTransport(id),
                rideSecretKey = secretKey
            ).also { it.start(backgroundScope) }
        }

        val islandTtsAlerts = mutableListOf<String>()
        val leaderEngine = engines.getValue(1u)
        val healthMonitor = PeerHealthMonitor(
            engine = leaderEngine,
            onIslandTtsAnnouncement = { islandTtsAlerts.add(it) }
        )
        val scheduler = HeartbeatScheduler(
            scope = backgroundScope,
            engine = leaderEngine,
            peerHealthMonitor = healthMonitor
        )

        // Todos los riders (2..5) emiten su primer heartbeat en t0 = 1_000_000 ms
        val t0 = 1_000_000L
        for (id in listOf<UShort>(2u, 3u, 4u, 5u)) {
            val pkt = MeshPacket.createSigned(
                shortId = id,
                latitude = 19.4300,
                longitude = -99.1332,
                speedKmh = 70,
                bearingDegrees = 180f,
                batteryPercent = 85,
                sequenceNumber = 1,
                ttl = 4,
                rideSecretKey = secretKey
            )
            leaderEngine.onRawPacketReceived(pkt.toByteArray(), nowEpochMs = t0)
        }
        val initialHealth = healthMonitor.evaluateHealth(t0)
        assertEquals(4, initialHealth.onlinePeersCount)
        assertEquals(0, initialHealth.lostPeersCount)
        assertFalse(initialHealth.isIslandActive)

        // Pasan LOST_TIMEOUT_MS (40,5 s derivados de campo): riders 2 y 3 envían su latido en tLoss,
        // mientras 4 y 5 quedan en silencio -> isla de 2 nodos.
        val tLoss = t0 + MeshRelayEngine.LOST_TIMEOUT_MS
        for (id in listOf<UShort>(2u, 3u)) {
            val pkt = MeshPacket.createSigned(
                shortId = id,
                latitude = 19.4310,
                longitude = -99.1332,
                speedKmh = 70,
                bearingDegrees = 180f,
                batteryPercent = 84,
                sequenceNumber = 2,
                ttl = 4,
                rideSecretKey = secretKey
            )
            leaderEngine.onRawPacketReceived(pkt.toByteArray(), nowEpochMs = tLoss)
        }

        val islandAtStart = healthMonitor.evaluateHealth(tLoss)
        assertTrue(islandAtStart.isIslandActive)
        assertEquals(2, islandAtStart.lostPeersCount)
        // Decisión 3.4 (C): En t = 0 s de isla y a los 30 s de isla NO se anuncia por TTS
        assertTrue(islandTtsAlerts.isEmpty())

        for (id in listOf<UShort>(2u, 3u)) {
            val pkt = MeshPacket.createSigned(
                shortId = id,
                latitude = 19.4320,
                longitude = -99.1332,
                speedKmh = 70,
                bearingDegrees = 180f,
                batteryPercent = 83,
                sequenceNumber = 3,
                ttl = 4,
                rideSecretKey = secretKey
            )
            leaderEngine.onRawPacketReceived(pkt.toByteArray(), nowEpochMs = tLoss + 30_000L)
        }
        val islandAt30s = healthMonitor.evaluateHealth(tLoss + 30_000L)
        assertTrue(islandAt30s.isIslandActive)
        assertFalse(islandAt30s.islandTtsAnnouncedForCurrentEpisode)
        assertTrue(islandTtsAlerts.isEmpty())

        // Al superar los 60 s de isla continua (tLoss + 61_000 ms), se anuncia una sola vez por TTS
        for (id in listOf<UShort>(2u, 3u)) {
            val pkt = MeshPacket.createSigned(
                shortId = id,
                latitude = 19.4330,
                longitude = -99.1332,
                speedKmh = 70,
                bearingDegrees = 180f,
                batteryPercent = 82,
                sequenceNumber = 4,
                ttl = 4,
                rideSecretKey = secretKey
            )
            leaderEngine.onRawPacketReceived(pkt.toByteArray(), nowEpochMs = tLoss + 61_000L)
        }
        val islandAt61s = healthMonitor.evaluateHealth(tLoss + 61_000L)
        assertTrue(islandAt61s.isIslandActive)
        assertTrue(islandAt61s.islandTtsAnnouncedForCurrentEpisode)
        assertEquals(1, islandTtsAlerts.size)
        assertEquals("Mesh parcial: 2 nodos fuera de contacto", islandTtsAlerts.first())

        // Histéresis de reconexión (RECONNECT_HYSTERESIS_BEATS = 2):
        // El Rider 5 envía 1 latido -> sigue en isLost = true (aún falta el 2.º latido consecutivo)
        val pktBeat1 = MeshPacket.createSigned(
            shortId = 5u,
            latitude = 19.4280,
            longitude = -99.1332,
            speedKmh = 72,
            bearingDegrees = 180f,
            batteryPercent = 80,
            sequenceNumber = 2,
            ttl = 4,
            rideSecretKey = secretKey
        )
        leaderEngine.onRawPacketReceived(pktBeat1.toByteArray(), nowEpochMs = tLoss + 62_000L)
        assertTrue(leaderEngine.peers.value.getValue(5u).isLost)

        // El Rider 5 envía el 2.º latido consecutivo -> supera la histéresis y vuelve a ONLINE (isLost = false)
        val pktBeat2 = MeshPacket.createSigned(
            shortId = 5u,
            latitude = 19.4285,
            longitude = -99.1332,
            speedKmh = 72,
            bearingDegrees = 180f,
            batteryPercent = 80,
            sequenceNumber = 3,
            ttl = 4,
            rideSecretKey = secretKey
        )
        leaderEngine.onRawPacketReceived(pktBeat2.toByteArray(), nowEpochMs = tLoss + 65_000L)
        assertFalse(leaderEngine.peers.value.getValue(5u).isLost)

        // Verificar que HeartbeatScheduler respeta el intervalo del perfil activo (3 s -> 5 s -> 10 s)
        val pose = HeartbeatScheduler.TelemetryPose()
        assertTrue(scheduler.tickIfDue(pose, nowEpochMs = 10_000L))
        assertFalse(scheduler.tickIfDue(pose, nowEpochMs = 12_000L)) // A los 2 s en modo NORMAL (3 s) aún no toca
        assertTrue(scheduler.tickIfDue(pose, nowEpochMs = 13_000L))  // A los 3 s sí emite

        engines.values.forEach { it.stop() }
    }

    @Test
    fun `Bloque 3_5 BatteryAwareness aplica ahorro automatico configurable a menos de 20 y modo supervivencia a 5 por ciento`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val controller = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null },
            simulatedMedium = medium
        )

        controller.startRide(
            rideId = "ride-f3-battery-modes",
            localShortId = 1u,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )

        // 1. Con 80% de batería -> PowerProfile.NORMAL (heartbeat cada 3 s, mapa cada 1 s)
        val normalState = controller.onBatteryStateChanged(batteryPercent = 80, isCharging = false)
        assertEquals(PowerProfile.NORMAL, normalState.powerProfile)
        assertEquals(3_000L, normalState.heartbeatIntervalMs)
        assertEquals(1_000L, normalState.mapUpdateCadenceMs)
        assertFalse(normalState.isMapFrozen)

        // 2. Decisión 3.1 (C): Cae a 18% (< 20%) sin cargador -> entra automáticamente en POWER_SAVER (5 s, mapa 3 s)
        val saverState = controller.onBatteryStateChanged(batteryPercent = 18, isCharging = false)
        assertEquals(PowerProfile.POWER_SAVER, saverState.powerProfile)
        assertEquals(5_000L, saverState.heartbeatIntervalMs)
        assertEquals(3_000L, saverState.mapUpdateCadenceMs)
        assertEquals(BatteryAwareness.TTS_POWER_SAVER_ACTIVATED, saverState.lastBatteryTtsAlert)

        // 3. Si el usuario desactiva el modo ahorro automático en Ajustes -> vuelve a NORMAL en 18%
        val disabledAutoState = controller.setAutoBatterySaverEnabled(false)
        assertEquals(PowerProfile.NORMAL, disabledAutoState.powerProfile)
        assertEquals(3_000L, disabledAutoState.heartbeatIntervalMs)

        // 4. Si el usuario pulsa manualmente "Modo ahorro" -> respeta la elección manual aunque tenga 75%
        controller.onBatteryStateChanged(batteryPercent = 75, isCharging = false)
        val manualSaverState = controller.setManualPowerSaverOverride(true)
        assertEquals(PowerProfile.POWER_SAVER, manualSaverState.powerProfile)
        assertEquals(5_000L, manualSaverState.heartbeatIntervalMs)
        controller.setManualPowerSaverOverride(null)

        // 5. Decisión 3.3 (B): Batería crítica a 5% sin cargador -> Modo supervivencia (10 s, mapa congelado, solo SOS)
        val survivalState = controller.onBatteryStateChanged(batteryPercent = 5, isCharging = false)
        assertEquals(PowerProfile.SURVIVAL, survivalState.powerProfile)
        assertEquals(10_000L, survivalState.heartbeatIntervalMs)
        assertTrue(survivalState.isMapFrozen)
        assertTrue(survivalState.powerProfile.onlySosActive)
        assertEquals(BatteryAwareness.TTS_SURVIVAL_MODE_ACTIVATED, survivalState.lastBatteryTtsAlert)

        // 6. Al conectar el cargador en 5%, sale de supervivencia y recupera NORMAL
        val chargingState = controller.onBatteryStateChanged(batteryPercent = 5, isCharging = true)
        assertEquals(PowerProfile.NORMAL, chargingState.powerProfile)
        assertFalse(chargingState.isMapFrozen)

        controller.stopAll()
    }

    @Test
    fun `Bloque 3_5 ManufacturerBatteryGuide detecta Xiaomi Tecno Huawei Samsung Oppo Vivo y es recomendada no obligatoria`() {
        val guide = ManufacturerBatteryGuide(initialManufacturer = "Xiaomi", initialBrand = "Redmi")

        // 1. Xiaomi / Redmi (HyperOS / MIUI)
        val xiaomiSnap = guide.snapshot()
        assertEquals(OemBatteryVendor.XIAOMI_HYPEROS, xiaomiSnap.vendor)
        assertFalse("Decisión 3.2 (B): La guía es recomendada, nunca obligatoria", xiaomiSnap.isMandatory)
        assertTrue(xiaomiSnap.shouldPromptBeforeRide)

        // 2. Tecno (HiOS)
        assertEquals(OemBatteryVendor.TECNO_HIOS, guide.updateDetectedDevice("TECNO MOBILE LIMITED", "TECNO").vendor)

        // 3. Huawei (EMUI / HarmonyOS)
        assertEquals(OemBatteryVendor.HUAWEI_EMUI, guide.updateDetectedDevice("HUAWEI", "HUAWEI").vendor)

        // 4. Samsung (One UI)
        assertEquals(OemBatteryVendor.SAMSUNG_ONEUI, guide.updateDetectedDevice("Samsung", "samsung").vendor)

        // 5. Oppo / Realme (ColorOS)
        assertEquals(OemBatteryVendor.OPPO_COLOROS, guide.updateDetectedDevice("OPPO", "Realme").vendor)

        // 6. Vivo (FuntouchOS)
        assertEquals(OemBatteryVendor.VIVO_FUNTOUCH, guide.updateDetectedDevice("vivo", "vivo").vendor)

        // 7. Botones "Saltar" y "No volver a mostrar"
        val afterSkip = guide.skipForNow()
        assertFalse(afterSkip.shouldPromptBeforeRide)
        assertTrue(afterSkip.skippedForCurrentSession)

        val afterDontShow = guide.markCompletedOrDontShowAgain()
        assertFalse(afterDontShow.shouldPromptBeforeRide)
        assertTrue(afterDontShow.permanentlyDismissed)
    }
}
