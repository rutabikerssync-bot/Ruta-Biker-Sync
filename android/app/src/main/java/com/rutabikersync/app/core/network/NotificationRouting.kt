package com.rutabikersync.app.core.network

import java.util.Locale

/**
 * mapper puro entre `data.type` de FCM, canal Android y deep-link.
 *
 * El backend envía los tipos en minúsculas (Corte 2B). Se normaliza el casing
 * para seguir tolerando payloads antiguos en UPPERCASE sin caer al canal de chat.
 */
object NotificationRouting {
    const val TYPE_SOS = "sos"
    const val TYPE_RIDE_INVITATION = "ride_invitation"
    const val TYPE_CLUB_INVITATION = "club_invitation"
    const val TYPE_DIRECT_MESSAGE = "direct_message"
    const val TYPE_TACTICAL_CHAT = "tactical_chat"
    const val TYPE_CONVOY_SIGNAL = "convoy_signal"

    const val CHANNEL_SOS = "sos_channel"
    const val CHANNEL_RIDE = "ride_invitation_channel"
    const val CHANNEL_CLUB = "club_invitation_channel"
    const val CHANNEL_CHAT = "direct_message_channel"

    data class DeepLink(
        val startRidePreview: Boolean = false,
        val startTacticalChat: Boolean = false,
        val startClubTab: Boolean = false,
        val sosAlert: Boolean = false,
        val rideId: String? = null,
        val groupId: String? = null,
        val lat: String? = null,
        val lng: String? = null
    )

    private fun normalize(type: String): String = type.trim().lowercase(Locale.ROOT)

    fun channelFor(type: String): String = when (normalize(type)) {
        TYPE_SOS -> CHANNEL_SOS
        TYPE_RIDE_INVITATION -> CHANNEL_RIDE
        TYPE_CLUB_INVITATION -> CHANNEL_CLUB
        else -> CHANNEL_CHAT
    }

    fun deepLinkFor(type: String, data: Map<String, String>): DeepLink = when (normalize(type)) {
        TYPE_SOS -> DeepLink(
            startRidePreview = true,
            sosAlert = true,
            lat = data["lat"],
            lng = data["lng"]
        )
        TYPE_RIDE_INVITATION -> DeepLink(
            startRidePreview = true,
            rideId = data["rideId"]
        )
        TYPE_CLUB_INVITATION -> DeepLink(
            startClubTab = true,
            groupId = data["groupId"]
        )
        TYPE_DIRECT_MESSAGE -> DeepLink(startTacticalChat = true)
        TYPE_TACTICAL_CHAT -> DeepLink(
            startTacticalChat = true,
            groupId = data["groupId"]
        )
        TYPE_CONVOY_SIGNAL -> DeepLink(
            startRidePreview = true,
            rideId = data["rideId"]
        )
        else -> DeepLink()
    }

    fun defaultTitleForType(type: String): String = when (normalize(type)) {
        TYPE_SOS -> "🚨 ALERTA SOS CERCANA"
        TYPE_RIDE_INVITATION -> "🏍️ Convocatoria a Rodada"
        TYPE_CLUB_INVITATION -> "🛡️ Invitación de Hermandad"
        TYPE_DIRECT_MESSAGE -> "💬 Mensaje de Ruta"
        TYPE_TACTICAL_CHAT -> "💬 Chat Táctico"
        TYPE_CONVOY_SIGNAL -> "🏍️ Señal de Convoy"
        else -> "Ruta Biker Sync"
    }

    fun defaultBodyForType(type: String): String = when (normalize(type)) {
        TYPE_SOS -> "Emergencia en ruta reportada a menos de 5 km"
        TYPE_RIDE_INVITATION -> "Tienes una nueva invitación a rodar con tu club"
        TYPE_CLUB_INVITATION -> "Has recibido una solicitud o invitación de membresía"
        TYPE_DIRECT_MESSAGE -> "Nuevo mensaje de radio / chat en tu grupo"
        TYPE_TACTICAL_CHAT -> "Nuevo mensaje táctico de tu grupo"
        TYPE_CONVOY_SIGNAL -> "Tu convoy envió una nueva señal de ruta"
        else -> "Nueva notificación disponible"
    }
}
