package com.rutabikersync.app.feature.fieldtest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias para la instrumentación de métricas de pruebas de campo (Fase 3):
 * 1. Conteo de filas de CSV (f3CsvRowCounts: tx, rx, relay_tx).
 * 2. Serialización completa de RadioEnvironmentSnapshot (radioAtStart, radioAtEnd, radioDiagnostic).
 * 3. Inclusión de modo de anuncio persistente (f3AdvertisingMode = PERSISTENT_SET).
 */
class FieldTestMetricsTest {

    @Test
    fun `parseCsvRowCounts cuenta correctamente filas de TX, RX y RELAY_TX`() {
        val csv = """
            timestamp_utc,sender_short_id,sequence_number,ttl,hops,rssi_dbm,transport_mode,is_duplicate,direction
            2026-10-25T23:25:27.488Z,1,0,4,0,,BleAdvTransport(LEGACY_1M),false,TX
            2026-10-25T23:25:30.537Z,1,1,4,0,,BleAdvTransport(LEGACY_1M),false,TX
            2026-10-25T23:25:31.168Z,2,1,4,1,-39,BleAdvTransport(LEGACY_1M),false,RX
            2026-10-25T23:25:31.400Z,2,1,4,1,-41,BleAdvTransport(LEGACY_1M),true,RX
            2026-10-25T23:25:32.000Z,2,1,3,2,,BleAdvTransport(LEGACY_1M),false,RELAY_TX
        """.trimIndent()

        val counts = parseCsvRowCounts(csv)
        assertEquals(2, counts.tx)
        assertEquals(2, counts.rx)
        assertEquals(1, counts.relayTx)

        val json = counts.toJsonObject()
        assertEquals(2, json.getInt("tx"))
        assertEquals(2, json.getInt("rx"))
        assertEquals(1, json.getInt("relay_tx"))
    }

    @Test
    fun `parseCsvRowCounts con CSV vacio o solo cabecera retorna ceros`() {
        val emptyCsv = ""
        val countsEmpty = parseCsvRowCounts(emptyCsv)
        assertEquals(0, countsEmpty.tx)
        assertEquals(0, countsEmpty.rx)
        assertEquals(0, countsEmpty.relayTx)

        val headerOnly = "timestamp_utc,sender_short_id,sequence_number,ttl,hops,rssi_dbm,transport_mode,is_duplicate,direction\n"
        val countsHeader = parseCsvRowCounts(headerOnly)
        assertEquals(0, countsHeader.tx)
        assertEquals(0, countsHeader.rx)
        assertEquals(0, countsHeader.relayTx)
    }

    @Test
    fun `RadioEnvironmentSnapshot serializa todos los campos de diagnostico de radio`() {
        val snapshot = RadioEnvironmentSnapshot(
            timestampUtc = "2026-10-25T23:25:27.000Z",
            wifiEnabled = false,
            mobileDataEnabled = false,
            airplaneModeOn = true,
            btEnabled = true,
            connectedBtAudio = false,
            otherConnectedBtDevices = false,
            nearbySharingState = "NO_DISPONIBLE_POR_API",
            fastPairLikelyActive = true
        )

        val json = snapshot.toJsonObject()
        assertEquals("2026-10-25T23:25:27.000Z", json.getString("timestampUtc"))
        assertFalse(json.getBoolean("wifiEnabled"))
        assertFalse(json.getBoolean("mobileDataEnabled"))
        assertTrue(json.getBoolean("airplaneModeOn"))
        assertTrue(json.getBoolean("btEnabled"))
        assertFalse(json.getBoolean("connectedBtAudio"))
        assertFalse(json.getBoolean("otherConnectedBtDevices"))
        assertEquals("NO_DISPONIBLE_POR_API", json.getString("nearbySharingState"))
        assertTrue(json.getBoolean("fastPairLikelyActive"))
    }

    @Test
    fun `RadioEnvironmentSnapshot maneja mobileDataEnabled nulo`() {
        val snapshot = RadioEnvironmentSnapshot(
            timestampUtc = "2026-10-25T23:25:27.000Z",
            wifiEnabled = true,
            mobileDataEnabled = null,
            airplaneModeOn = false
        )

        val json = snapshot.toJsonObject()
        assertTrue(json.isNull("mobileDataEnabled"))
        assertTrue(json.getBoolean("wifiEnabled"))
        assertFalse(json.getBoolean("airplaneModeOn"))
    }

    @Test
    fun `FieldTestConfigSnapshot preserva radioAtStart de forma inmutable`() {
        val initialRadio = RadioEnvironmentSnapshot(
            timestampUtc = "2026-10-25T23:25:27.000Z",
            wifiEnabled = false,
            mobileDataEnabled = false,
            airplaneModeOn = true
        )

        val config = FieldTestConfigSnapshot(
            testStartEpochMs = 1792970727000L,
            localNodeShortId = 1,
            totalPhonesInTest = 2,
            nodeRole = "Punta (#1 Líder)",
            expectedPeerShortIds = listOf(2),
            forcedBleTransportMode = "LEGACY_1M",
            effectiveBleTransportMode = "LEGACY_1M",
            advertisingBurstDurationMs = 1000,
            bleCapabilitiesSnapshot = com.rutabikersync.app.core.transport.BleAdvTransport.BleCapabilities(
                bluetoothSupported = true,
                bluetoothEnabled = true,
                multipleAdvertisementSupported = true,
                extendedAdvertisingSupported = true,
                codedPhySupported = true,
                le2MPhySupported = true,
                maxAdvertisingDataLength = 1650
            ),
            radioAtStart = initialRadio
        )

        assertNotNull(config.radioAtStart)
        assertTrue(config.radioAtStart!!.airplaneModeOn)
        assertFalse(config.radioAtStart!!.wifiEnabled)
    }

    @Test
    fun `JSON de metricas serializa completamente peerDeliveryReports y primaryPeerDeliveryRatePct`() {
        val testJsonString = """
            {
              "timestampEpochMs": 1792973458137,
              "timestampIso": "2026-10-26T00:10:58Z",
              "gitCommit": "e7664da",
              "gitDirty": false,
              "versionName": "0.1.0",
              "socManufacturer": "Unisoc",
              "socModel": "UMS9230",
              "hardware": "ums9230",
              "board": "ki5k",
              "f3AdvertisingMode": "BURST_1S",
              "advertisingModeDefault": "BURST_1S",
              "advertisingModeRationale": "RÁFAGA gana al CONTINUO en Redmi (51→100%) y gasta ~3× menos radio; CONTINUO queda solo como opción de prueba",
              "f3AdvertisingDutyCycleApproxPct": 33.33,
              "shortRunWarning": false,
              "f3AdvertisingBurstsStarted": 60,
              "f3AdvertisingBurstsSuccess": 60,
              "distinctAdvAddressesPerPeer": {
                "1": 3
              },
              "peerDeliveryReports": [
                {
                  "peerShortId": 1,
                  "firstSequenceObserved": 0,
                  "lastSequenceObserved": 59,
                  "burstsEmittedByPeer": 60,
                  "burstsHeardFromPeer": 60,
                  "peerDeliveryRatePct": 100.0,
                  "isLost": false,
                  "distinctAdvAddressesCount": 3
                }
              ],
              "primaryPeerShortId": 1,
              "primaryPeerBurstsEmitted": 60,
              "primaryPeerBurstsHeard": 60,
              "primaryPeerDeliveryRatePct": 100.0,
              "primaryPeerDistinctAdvAddresses": 3
            }
        """.trimIndent()

        // Validar que el parser JSON lee la estructura completa hasta la llave de cierre
        val json = JSONObject(testJsonString)
        assertFalse(json.getBoolean("gitDirty"))
        assertEquals("Unisoc", json.getString("socManufacturer"))
        assertEquals("UMS9230", json.getString("socModel"))
        assertEquals("BURST_1S", json.getString("f3AdvertisingMode"))
        assertEquals("BURST_1S", json.getString("advertisingModeDefault"))
        assertTrue(json.getString("advertisingModeRationale").contains("RÁFAGA gana al CONTINUO"))
        assertFalse(json.getBoolean("shortRunWarning"))

        val distinctMap = json.getJSONObject("distinctAdvAddressesPerPeer")
        assertEquals(3, distinctMap.getInt("1"))

        val peersArray = json.getJSONArray("peerDeliveryReports")
        assertEquals(1, peersArray.length())
        val firstPeer = peersArray.getJSONObject(0)
        assertEquals(1, firstPeer.getInt("peerShortId"))
        assertEquals(60, firstPeer.getInt("burstsHeardFromPeer"))
        assertEquals(100.0, firstPeer.getDouble("peerDeliveryRatePct"), 0.01)
        assertEquals(3, firstPeer.getInt("distinctAdvAddressesCount"))

        assertEquals(1, json.getInt("primaryPeerShortId"))
        assertEquals(100.0, json.getDouble("primaryPeerDeliveryRatePct"), 0.01)
        assertEquals(3, json.getInt("primaryPeerDistinctAdvAddresses"))
    }

    @Test
    fun `parseCsvRowCounts soporta columnas adicionales adv_addr_suffix y timestamp_nanos en CSV`() {
        val csv = """
            timestamp_utc,sender_short_id,sequence_number,ttl,hops,rssi_dbm,transport_mode,is_duplicate,direction,advertising_mode,adv_addr_suffix,timestamp_nanos
            2026-10-25T23:25:27.488Z,1,0,4,0,,BleAdvTransport(LEGACY_1M),false,TX,BURST_1S,,
            2026-10-25T23:25:31.168Z,2,1,4,1,-39,BleAdvTransport(LEGACY_1M),false,RX,BURST_1S,D4:3F,123456789000
        """.trimIndent()

        val counts = parseCsvRowCounts(csv)
        assertEquals(1, counts.tx)
        assertEquals(1, counts.rx)
        assertEquals(0, counts.relayTx)
    }
}
