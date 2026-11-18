package com.rutabikersync.app.core.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite de Pruebas Unitarias para el Hito 1 de la Fase 6 (Red Mesh BLE & Telemetría Heartbeat).
 * Verifica los contratos cuantitativos, tramas binarias compactas, deduplicación y conmutación PHY.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleMeshHito1Test {

    @Test
    fun testMeshHeaderSerializationRoundTrip() {
        val originalHeader = MeshHeader(
            clubNetworkId = 0x52425301u, // "RBS1"
            senderShortId = 0x0042u,
            packetType = MeshHeader.TYPE_HEARTBEAT,
            initTtl = 3, // 5 saltos
            currentTtl = 2,
            sequenceNum = 1337u
        )

        val bytes = originalHeader.toByteArray()
        assertEquals("El tamaño de MeshHeader debe ser exactamente de 9 bytes", 9, bytes.size)

        val deserialized = MeshHeader.fromByteArray(bytes)
        assertEquals(originalHeader.clubNetworkId, deserialized.clubNetworkId)
        assertEquals(originalHeader.senderShortId, deserialized.senderShortId)
        assertEquals(originalHeader.packetType, deserialized.packetType)
        assertEquals(originalHeader.initTtl, deserialized.initTtl)
        assertEquals(originalHeader.currentTtl, deserialized.currentTtl)
        assertEquals(originalHeader.sequenceNum, deserialized.sequenceNum)
        assertEquals(originalHeader.dedupKey, deserialized.dedupKey)
    }

    @Test
    fun testHeartbeatFrameSerializationRoundTrip24Bytes() {
        val header = MeshHeader(
            clubNetworkId = 0xAABBCCDDu,
            senderShortId = 0x0102u,
            packetType = MeshHeader.TYPE_HEARTBEAT,
            initTtl = 0,
            currentTtl = 0,
            sequenceNum = 42u
        )

        val originalFrame = HeartbeatFrame(
            header = header,
            latitude = 10.4806543,
            longitude = -66.9036123,
            speedKmh = 85,
            headingDeg = 270f,
            batteryPercent = 95,
            flags = HeartbeatFrame.FLAG_MEDICAL_SHARED or HeartbeatFrame.FLAG_VOICE_ACTIVE,
            authTagTrunc = 0x00000000
        )

        val wireBytes = originalFrame.toByteArray()
        assertEquals(
            "La trama de Heartbeat completa en el aire debe medir exactamente 24 bytes (9B header + 15B payload)",
            HeartbeatFrame.FRAME_SIZE_BYTES,
            wireBytes.size
        )
        assertEquals(24, wireBytes.size)

        val deserialized = HeartbeatFrame.fromByteArray(wireBytes)
        assertEquals(originalFrame.header.clubNetworkId, deserialized.header.clubNetworkId)
        assertEquals(originalFrame.header.senderShortId, deserialized.header.senderShortId)
        assertEquals(originalFrame.speedKmh, deserialized.speedKmh)
        assertEquals(270f, deserialized.headingDeg, 2.0f) // Precisión dentro de resolución 2°
        assertEquals(originalFrame.latitude, deserialized.latitude, 0.00001)
        assertEquals(originalFrame.longitude, deserialized.longitude, 0.00001)
        assertTrue(deserialized.isMedicalSheetShared)
        assertTrue(deserialized.isVoiceActive)
        assertFalse(deserialized.isOffRoute)
        assertFalse(deserialized.isVoluntaryStop)
        assertEquals(0, deserialized.authTagTrunc)
    }

    @Test
    fun testDeduplicationTableLruAndExpiration() {
        val dedupTable = DeduplicationTable(maxCapacity = 1024, entryTtlMs = 30_000L)
        val now = 1_000_000L

        // 1. Primera inserción: debe aceptarse
        val acceptedFirst = dedupTable.shouldProcessAndMark(senderShortId = 10u, sequenceNum = 1u, nowMs = now)
        assertTrue("El primer paquete de una secuencia debe aceptarse", acceptedFirst)

        // 2. Duplicado inmediato: debe rechazarse
        val acceptedDuplicate = dedupTable.shouldProcessAndMark(senderShortId = 10u, sequenceNum = 1u, nowMs = now + 500)
        assertFalse("El paquete duplicado recibido a los 500ms debe descartarse", acceptedDuplicate)

        // 3. Expiración tras 30s (30,001 ms): debe volverse a aceptar
        val acceptedAfterTtl = dedupTable.shouldProcessAndMark(senderShortId = 10u, sequenceNum = 1u, nowMs = now + 30_001L)
        assertTrue("Tras expirar el TTL de 30s, el paquete debe ser re-admitido", acceptedAfterTtl)

        // 4. Capacidad máxima LRU de 1,024 entradas
        dedupTable.clear()
        for (i in 1..1024) {
            dedupTable.shouldProcessAndMark(senderShortId = 1u, sequenceNum = i.toUShort(), nowMs = now + i)
        }
        assertEquals(1024, dedupTable.size)
        assertTrue("La entrada 1 debe estar presente", dedupTable.containsKey(1u, 1u))

        // Inserción 1025: debe desalojar la entrada 1 (la más antigua en LRU)
        dedupTable.shouldProcessAndMark(senderShortId = 1u, sequenceNum = 1025u, nowMs = now + 1025)
        assertEquals("El tamaño se mantiene acotado a 1024", 1024, dedupTable.size)
        assertFalse("La entrada más antigua (secuencia 1) debe haber sido desalojada por LRU", dedupTable.containsKey(1u, 1u))
        assertTrue("La entrada más reciente (secuencia 1025) debe estar presente", dedupTable.containsKey(1u, 1025u))
    }

    @Test
    fun testCodedPhySwitcherTransitionsAndAjuste1Timeout() {
        var changeCount = 0
        var lastOldPhy: CodedPhySwitcher.PhyMode? = null
        var lastNewPhy: CodedPhySwitcher.PhyMode? = null

        val switcher = CodedPhySwitcher { oldPhy, newPhy, _ ->
            changeCount++
            lastOldPhy = oldPhy
            lastNewPhy = newPhy
        }

        val baseTime = 500_000L

        // 1. Estado inicial: PHY_1M
        assertEquals(CodedPhySwitcher.PhyMode.PHY_1M, switcher.currentPhy)

        // Señal buena: -75 dBm -> No debe conmutar
        switcher.onRssiSample(-75, baseTime)
        assertEquals(CodedPhySwitcher.PhyMode.PHY_1M, switcher.currentPhy)

        // 2. Degradación a Coded PHY: 3 muestras consecutivas < -88 dBm (-89, -90, -92 dBm)
        switcher.onRssiSample(-89, baseTime + 1000)
        switcher.onRssiSample(-90, baseTime + 2000)
        val phyAfter3Critical = switcher.onRssiSample(-92, baseTime + 3000)

        assertEquals("Debe degradar a PHY_CODED_S8 con 3 muestras < -88 dBm", CodedPhySwitcher.PhyMode.PHY_CODED_S8, phyAfter3Critical)
        assertEquals(CodedPhySwitcher.PhyMode.PHY_1M, lastOldPhy)
        assertEquals(CodedPhySwitcher.PhyMode.PHY_CODED_S8, lastNewPhy)
        assertEquals(1, changeCount)

        // 3. Retorno estándar: 5 muestras consecutivas > -78 dBm (-75, -74, -72, -76, -70 dBm)
        switcher.onRssiSample(-75, baseTime + 4000)
        switcher.onRssiSample(-74, baseTime + 5000)
        switcher.onRssiSample(-72, baseTime + 6000)
        switcher.onRssiSample(-76, baseTime + 7000)
        val phyAfterRecovery = switcher.onRssiSample(-70, baseTime + 8000)

        assertEquals("Debe regresar a PHY_1M con 5 muestras > -78 dBm", CodedPhySwitcher.PhyMode.PHY_1M, phyAfterRecovery)
        assertEquals(2, changeCount)

        // 4. Ajuste 1 (Auditoría): Zona gris (-80 a -88 dBm) con timeout de 60s
        // Degradamos nuevamente a Coded PHY
        switcher.onRssiSample(-90, baseTime + 9000)
        switcher.onRssiSample(-91, baseTime + 10000)
        switcher.onRssiSample(-89, baseTime + 11000)
        assertEquals(CodedPhySwitcher.PhyMode.PHY_CODED_S8, switcher.currentPhy)

        val codedStartTime = switcher.codedPhyActivatedAtMs

        // Pasamos 10 muestras en zona gris (-80 dBm) pero dentro de los 30s (< 60s timeout)
        for (i in 1..10) {
            switcher.onRssiSample(-80, codedStartTime + (i * 2000L)) // hasta 20s
        }
        assertEquals("A los 20s (dentro de 60s) debe mantenerse en Coded PHY", CodedPhySwitcher.PhyMode.PHY_CODED_S8, switcher.currentPhy)

        // Al superar los 60s con promedio >= -80 dBm -> Forzar retorno a 1M
        val phyAfter60sTimeout = switcher.onRssiSample(-80, codedStartTime + 61_000L)
        assertEquals("Ajuste 1: Al transcurrir >= 60s en zona gris con promedio >= -80 dBm, debe forzar retorno a PHY_1M", CodedPhySwitcher.PhyMode.PHY_1M, phyAfter60sTimeout)
    }

    @Test
    fun testBleMeshEngineIsolationAndBatteryMitigation() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val engine = BleMeshEngine(externalScope = testScope)
        val now = 2_000_000L

        try {
            // Iniciar motor con club y nodo
            engine.startMeshEngine(clubId = 0x52425301u, shortId = 1u)
            assertEquals(BleMeshEngine.LifecycleState.RUNNING, engine.lifecycleState.value)
            assertEquals(0, engine.connectedPeersCount.value)
            assertFalse(engine.isIsolatedMode.value)

            // 1. Simular recepción de Heartbeat de Peer #2
            val peerHeader = MeshHeader(
                clubNetworkId = 0x52425301u,
                senderShortId = 2u,
                packetType = MeshHeader.TYPE_HEARTBEAT,
                initTtl = 0,
                currentTtl = 0,
                sequenceNum = 1u
            )
            val peerFrame = HeartbeatFrame(
                header = peerHeader,
                latitude = 10.4810,
                longitude = -66.9040,
                speedKmh = 40,
                headingDeg = 180f,
                batteryPercent = 88
            )
            val processed = engine.onRawPacketReceived(peerFrame.toByteArray(), rssi = -75, nowMs = now)
            assertTrue("La trama de peer #2 debe ser procesada", processed)

            assertEquals("Debe reflejar 1 peer conectado", 1, engine.connectedPeersCount.value)
            assertEquals(1, engine.receivedHeartbeatsCount.value)
            assertFalse(engine.isIsolatedMode.value)

            // 2. Simular paso de 16 segundos sin heartbeats -> Peer pasa a LOST
            engine.evaluatePeersHealthAndIsolation(now + 16_000L)
            assertEquals("Tras 16s sin latido, el conteo de peers conectados cae a 0", 0, engine.connectedPeersCount.value)
            assertFalse("A los 16s aún no debe activarse el Modo Aislado (<30 min)", engine.isIsolatedMode.value)

            // 3. Simular paso de 30 minutos sin peers -> Ajuste 2: Modo Aislado activado
            engine.evaluatePeersHealthAndIsolation(now + BleMeshEngine.ISOLATED_TIMEOUT_MS + 1000L)
            assertTrue("Ajuste 2: Tras 30 minutos continuos sin peers, debe activar Modo Aislado", engine.isIsolatedMode.value)

            // 4. Mitigación de Batería Baja (Ajuste 3)
            engine.onBatteryLevelChanged(50)
            assertFalse(engine.isLowBatterySurvivalMode.value)

            engine.onBatteryLevelChanged(18) // < 20%
            assertFalse("A 18% es advertencia, aún no entra en supervivencia (<= 10%)", engine.isLowBatterySurvivalMode.value)

            engine.onBatteryLevelChanged(10) // <= 10%
            assertTrue("Ajuste 3: A <= 10% de batería debe conmutar a Modo Supervivencia", engine.isLowBatterySurvivalMode.value)
        } finally {
            engine.stopMeshEngine()
        }
    }
}
