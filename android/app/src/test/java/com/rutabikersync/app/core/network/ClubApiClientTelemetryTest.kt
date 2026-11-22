package com.rutabikersync.app.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ClubApiClientTelemetryTest {

    private val client = ClubApiClient(baseUrl = "https://example.invalid")

    @Test
    fun `parsea muestras de telemetria de rodada`() {
        val body = Json.parseToJsonElement(
            """
            {
              "rideId": "b5d0228d-8ab9-49fd-9cc7-efa452a484f2",
              "sampledAt": "2026-11-22T16:00:00.000Z",
              "riders": [
                {
                  "userId": "e54bb490-754a-4aa6-9b4c-8b7b043e9dff",
                  "latitude": 10.3538,
                  "longitude": -67.0425,
                  "heading": 90,
                  "speedKmh": 42,
                  "observedAt": "2026-11-22T15:59:58.000Z"
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val samples = client.parseRideTelemetry(body)

        assertEquals(1, samples.size)
        val sample = samples.single()
        assertEquals("e54bb490-754a-4aa6-9b4c-8b7b043e9dff", sample.userId)
        assertEquals(10.3538, sample.latitude, 0.000001)
        assertEquals(-67.0425, sample.longitude, 0.000001)
        assertEquals(90.0, sample.heading, 0.000001)
        assertEquals(42.0, sample.speedKmh, 0.000001)
        assertEquals(1795363198000L, sample.observedAtMillis)
    }

    @Test
    fun `cuerpo sin riders devuelve lista vacia`() {
        val body = Json.parseToJsonElement("""{"rideId":"x"}""").jsonObject
        assertEquals(emptyList<ClubApiClient.RemoteTelemetrySample>(), client.parseRideTelemetry(body))
    }
}
