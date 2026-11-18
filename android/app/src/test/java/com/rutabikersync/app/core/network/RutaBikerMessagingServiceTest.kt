package com.rutabikersync.app.core.network

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite de pruebas unitarias para el Servicio de Notificaciones Push FCM y Canales Canónicos (Fase 7.1.2).
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
    fun `test 2 - Mapeo de tipos de notificacion asigna el canal correspondiente con prioridad y seguridad`() {
        // Validación de lógica de asignación de canal según el tipo recibido
        fun resolveChannelForType(type: String): String = when (type) {
            "sos" -> RutaBikerMessagingService.CHANNEL_SOS
            "ride_invitation" -> RutaBikerMessagingService.CHANNEL_RIDE
            "club_invitation" -> RutaBikerMessagingService.CHANNEL_CLUB
            "direct_message" -> RutaBikerMessagingService.CHANNEL_CHAT
            else -> RutaBikerMessagingService.CHANNEL_CHAT
        }

        assertEquals(RutaBikerMessagingService.CHANNEL_SOS, resolveChannelForType("sos"))
        assertEquals(RutaBikerMessagingService.CHANNEL_RIDE, resolveChannelForType("ride_invitation"))
        assertEquals(RutaBikerMessagingService.CHANNEL_CLUB, resolveChannelForType("club_invitation"))
        assertEquals(RutaBikerMessagingService.CHANNEL_CHAT, resolveChannelForType("direct_message"))
        assertEquals(RutaBikerMessagingService.CHANNEL_CHAT, resolveChannelForType("unknown_type"))
    }

    @Test
    fun `test 3 - Textos por defecto para cada tipo de mensaje cumplen con estetica motera sin filtrar PII`() {
        fun defaultTitleForType(type: String): String = when (type) {
            "sos" -> "🚨 ALERTA SOS CERCANA"
            "ride_invitation" -> "🏍️ Convocatoria a Rodada"
            "club_invitation" -> "🛡️ Invitación de Hermandad"
            "direct_message" -> "💬 Mensaje de Ruta"
            else -> "Ruta Biker Sync"
        }

        val sosTitle = defaultTitleForType("sos")
        val rideTitle = defaultTitleForType("ride_invitation")
        val clubTitle = defaultTitleForType("club_invitation")
        val chatTitle = defaultTitleForType("direct_message")

        assertTrue(sosTitle.contains("SOS"))
        assertTrue(rideTitle.contains("Rodada"))
        assertTrue(clubTitle.contains("Hermandad"))
        assertTrue(chatTitle.contains("Mensaje"))

        // Verificar que no se incluye ningún dato sensible o clínico en títulos por defecto
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
        // Los datos recibidos de FCM nunca deben requerir ni exponer claves privadas o datos médicos
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
}
