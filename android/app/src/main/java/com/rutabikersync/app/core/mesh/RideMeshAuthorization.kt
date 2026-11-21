package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.crypto.RideManifestKeyProvider

/**
 * P0 auditoría 2026-11-21 — aislamiento real del convoy.
 *
 * La pertenencia no se decide por un `clubNetworkId` plano ni por "tener la app":
 * se decide por el manifiesto firmado por el backend. Este objeto se construye
 * solo con el resultado verificado de [RideManifestKeyProvider.resolve].
 *
 * No modifica el wire format ni GATT: filtra tramas ya recibidas antes de que
 * alteren el mapa, la fase de ruta o el audio.
 */
class RideMeshAuthorization(private val authorizedRide: RideManifestKeyProvider.AuthorizedRide) {

    private data class Member(
        val uid: String,
        val groupId: String,
        val rideRole: String?,
        val shortId: UShort
    )

    private val membersByShortId: Map<UShort, Member> =
        authorizedRide.manifest.participants.mapNotNull { participant ->
            val pk = participant.pk?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
            val shortId = RideManifestKeyProvider.shortIdFromParticipantPublicKey(pk)
            shortId to Member(participant.uid, participant.gid, participant.rideRole, shortId)
        }.toMap()

    private val lastSequenceBySender = mutableMapOf<UShort, UShort>()

    val allowedShortIds: Set<UShort> get() = membersByShortId.keys

    /** Todo tráfico de malla debe venir de un participante del manifiesto. */
    fun isClubMember(shortId: UShort): Boolean = shortId in membersByShortId

    /**
     * Solo el líder anfitrión/coanfitrión o un capitán/road marshal del roster
     * puede invertir la ruta o cerrar la rodada de todo el convoy.
     */
    fun canAuthorLifecycle(shortId: UShort, event: MeshRelayEngine.RideLifecycleEvent): Boolean {
        val member = membersByShortId[shortId] ?: return false
        return when (event) {
            MeshRelayEngine.RideLifecycleEvent.START_OUTBOUND,
            MeshRelayEngine.RideLifecycleEvent.ARRIVE_DESTINATION -> true
            MeshRelayEngine.RideLifecycleEvent.SAFE_HOME,
            MeshRelayEngine.RideLifecycleEvent.START_RETURN,
            MeshRelayEngine.RideLifecycleEvent.COMPLETE_RIDE -> {
                val isLeader = member.uid == authorizedRide.manifest.hostLeaderId ||
                    member.uid == authorizedRide.manifest.cohostLeaderId
                val isMarshal = member.rideRole?.lowercase() in setOf("capitan_rodada", "road_marshal")
                isLeader || isMarshal
            }
        }
    }

    /**
     * Frescura sin tocar el wire format: se rechaza una secuencia ya vista o
     * claramente anterior a la última del mismo emisor (con tolerancia al
     * wrap-around de UShort). El dedup global de MeshRelayEngine sigue activo.
     */
    @Synchronized
    fun acceptSequence(shortId: UShort, sequence: UShort): Boolean {
        val previous = lastSequenceBySender[shortId] ?: run {
            lastSequenceBySender[shortId] = sequence
            return true
        }
        if (sequence == previous) return false
        val delta = (sequence.toInt() - previous.toInt()) and 0xFFFF
        if (delta > 0x8000) {
            // Retroceso de más de media vuelta: paquete viejo o repetido.
            return false
        }
        lastSequenceBySender[shortId] = sequence
        return true
    }

    fun resetFreshness() = synchronized(this) { lastSequenceBySender.clear() }
}
