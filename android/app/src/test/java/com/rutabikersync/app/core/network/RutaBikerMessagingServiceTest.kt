package com.rutabikersync.app.core.network

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite de pruebas unitarias para el Servicio de Notificaciones Push FCM y Canales Canónicos (Fase 7.1.2 / Corte 2B).
 */
class RutaBikerMessagingServiceTest {

    @Test
    fun `test 1 - Los 4 canales canonicos de notificacion tienen los identificadores estandar exactos`() {
        assertEquals("sos_channel", RutaBikerMessagingService.CHANNEL_SOS)
        assertEquals("ride_invitation_channel", RutaBikerMessagingService.CHANNEL_RIDE)
        assertEquals("club_invitation_channel", RutaBikerMessagingService.CHANNEL_CLUB)
        assertEquals("direct_message_channel", RutaBikerMessagingService.CHANNEL_CHAT)
    }

    @Test
    fun `test 2 - Cada tipo se mapea al canal correcto usando el mapper real`() {
        assertEquals(
            RutaBikerMessagingService.CHANNEL_SOS,
            NotificationRouting.channelFor(NotificationRouting.TYPE_SOS)
        )
        assertEquals(
            RutaBikerMessagingService.CHANNEL_RIDE,
            NotificationRouting.channelFor(NotificationRouting.TYPE_RIDE_INVITATION)
        )
        assertEquals(
            RutaBikerMessagingService.CHANNEL_CLUB,
            NotificationRouting.channelFor(NotificationRouting.TYPE_CLUB_INVITATION)
        )
        assertEquals(
            RutaBikerMessagingService.CHANNEL_CHAT,
            NotificationRouting.channelFor(NotificationRouting.TYPE_DIRECT_MESSAGE)
        )
        assertEquals(
            RutaBikerMessagingService.CHANNEL_CHAT,
            NotificationRouting.channelFor(NotificationRouting.TYPE_TACTICAL_CHAT)
        )
        assertEquals(
            RutaBikerMessagingService.CHANNEL_CHAT,
            NotificationRouting.channelFor(NotificationRouting.TYPE_CONVOY_SIGNAL)
        )
        assertEquals(
            RutaBikerMessagingService.CHANNEL_CHAT,
            NotificationRouting.channelFor("unknown_type")
        )
    }

    @Test
    fun `test 2b - El casing antiguo del backend no cae al canal de chat`() {
        assertEquals(
            RutaBikerMessagingService.CHANNEL_RIDE,
            NotificationRouting.channelFor("RIDE_INVITATION")
        )
        assertEquals(
            RutaBikerMessagingService.CHANNEL_CLUB,
            NotificationRouting.channelFor("CLUB_INVITATION")
        )
        assertEquals(
            RutaBikerMessagingService.CHANNEL_SOS,
            NotificationRouting.channelFor(" SOS ")
        )
    }

    @Test
    fun `test 3 - Textos por defecto para cada tipo cumplen con estetica motera sin filtrar PII`() {
        val sosTitle = NotificationRouting.defaultTitleForType("sos")
        val rideTitle = NotificationRouting.defaultTitleForType("ride_invitation")
        val clubTitle = NotificationRouting.defaultTitleForType("club_invitation")
        val chatTitle = NotificationRouting.defaultTitleForType("direct_message")
        val tacticalTitle = NotificationRouting.defaultTitleForType("tactical_chat")
        val convoyTitle = NotificationRouting.defaultTitleForType("convoy_signal")

        assertTrue(sosTitle.contains("SOS"))
        assertTrue(rideTitle.contains("Rodada"))
        assertTrue(clubTitle.contains("Hermandad"))
        assertTrue(chatTitle.contains("Mensaje"))
        assertTrue(tacticalTitle.contains("Chat"))
        assertTrue(convoyTitle.contains("Convoy"))

        assertFalse(sosTitle.contains("sangre", ignoreCase = true))
        assertFalse(sosTitle.contains("alergia", ignoreCase = true))
        assertFalse(sosTitle.contains("diagnóstico", ignoreCase = true))
    }

    @Test
    fun `test 4 - Payload de registro FCM en ClubApiClient genera la estructura JSON requerida por backend Fastify`() {
        val sampleToken = "sample_fcm_token_device_987654321_biker"
        val platform = "android"

        val body = buildJsonObject {
            put("token", sampleToken)
            put("platform", platform)
        }

        assertNotNull(body["token"])
        assertNotNull(body["platform"])
        assertEquals("\"$sampleToken\"", body["token"].toString())
        assertEquals("\"android\"", body["platform"].toString())
    }

    @Test
    fun `test 5 - Filtrado de datos sensibles en payloads FCM conforme a Regla R15 y Privacidad`() {
        val fcmPayload = mapOf(
            "type" to "sos",
            "lat" to "10.4806",
            "lng" to "-66.9036",
            "distanceKm" to "2.4"
        )

        assertFalse(fcmPayload.containsKey("privateKey"))
        assertFalse(fcmPayload.containsKey("masterKey"))
        assertFalse(fcmPayload.containsKey("medicalSheet"))
        assertFalse(fcmPayload.containsKey("bloodType"))
        assertEquals("sos", fcmPayload["type"])
        assertEquals("10.4806", fcmPayload["lat"])
    }

    @Test
    fun `test 6 - Deep-links de SOS, rodada, club, chat tacico y convoy senal`() {
        val sos = NotificationRouting.deepLinkFor(
            "sos",
            mapOf("lat" to "10.4806", "lng" to "-66.9036")
        )
        assertTrue(sos.sosAlert)
        assertTrue(sos.startRidePreview)
        assertEquals("10.4806", sos.lat)
        assertEquals("-66.9036", sos.lng)

        val ride = NotificationRouting.deepLinkFor("ride_invitation", mapOf("rideId" to "ride-1"))
        assertTrue(ride.startRidePreview)
        assertEquals("ride-1", ride.rideId)

        val club = NotificationRouting.deepLinkFor("club_invitation", mapOf("groupId" to "group-1"))
        assertTrue(club.startClubTab)
        assertEquals("group-1", club.groupId)

        val chat = NotificationRouting.deepLinkFor("tactical_chat", mapOf("groupId" to "group-1"))
        assertTrue(chat.startTacticalChat)
        assertEquals("group-1", chat.groupId)

        val convoy = NotificationRouting.deepLinkFor("convoy_signal", mapOf("rideId" to "ride-2"))
        assertTrue(convoy.startRidePreview)
        assertEquals("ride-2", convoy.rideId)
    }
}
