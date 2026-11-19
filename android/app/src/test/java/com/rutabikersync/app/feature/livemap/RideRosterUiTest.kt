package com.rutabikersync.app.feature.livemap

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideRosterUiTest {
    @Test
    fun `parsea roster real con nombre avatar y rol`() {
        val json = Json.parseToJsonElement(
            """
            {
              "rideId": "11111111-1111-4111-8111-111111111111",
              "participants": [
                {
                  "userId": "u-1",
                  "displayName": "Juan Martínez",
                  "avatarUrl": "https://example.test/juan.webp",
                  "roleInConvoy": "Punta",
                  "mapColor": "#FFB300"
                },
                {
                  "userId": "u-2",
                  "displayName": "Desconocido",
                  "avatarUrl": null,
                  "roleInConvoy": "Rider",
                  "mapColor": null
                }
              ]
            }
            """.trimIndent()
        )

        val entries = parseRideRosterUi(json)
        assertEquals(2, entries.size)
        assertEquals("Juan Martínez", entries[0].displayName)
        assertEquals("https://example.test/juan.webp", entries[0].avatarUrl)
        assertEquals("Punta", entries[0].roleInConvoy)
        assertEquals(0xFFFFB300L, entries[0].mapColorHex)
        assertEquals("Desconocido", entries[1].displayName)
        assertNull(entries[1].avatarUrl)
        assertEquals("Rider", entries[1].roleInConvoy)
    }

    @Test
    fun `color invalido no inventa valor`() {
        assertNull(parseHexColor("no-es-hex"))
        assertEquals(0xFF00E676L, parseHexColor("00E676"))
    }
}
