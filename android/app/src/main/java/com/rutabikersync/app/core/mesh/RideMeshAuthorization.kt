package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.crypto.RideManifestKeyProvider
import com.rutabikersync.app.core.crypto.RideReplayStateStore

/**
 * P0 auditoría 2026-11-21 — aislamiento real del convoy.
 *
 * La pertenencia no se decide por un `clubNetworkId` plano ni por "tener la app":
 * se decide por el manifiesto firmado por el backend. Este objeto se construye
 * solo con el resultado verificado de [RideManifestKeyProvider.resolve].
 *
 * No modifica el wire format ni GATT: filtra tramas ya recibidas antes de que
 * alteren el mapa, la fase de ruta o el audio.
 *
 * Auditoría integral 2026-11-27: la frescura ahora puede persistirse en
 * [RideReplayStateStore] para sobrevivir a un reinicio del proceso. Sin store
 * (tests/simulación) el comportamiento sigue siendo exclusivamente en memoria.
 */
class RideMeshAuthorization(
    private val authorizedRide: RideManifestKeyProvider.AuthorizedRide,
    private val replayStateStore: RideReplayStateStore? = null,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000L }
) {

    private data class Member(
        val uid: String,
        val groupId: String,
        val rideRole: String?,
        val shortId: UShort
    )

    private data class SenderFreshness(
        var lastSequence: UShort = 0u,
        var hasSequence: Boolean = false,
        val nonces: LinkedHashSet<String> = LinkedHashSet(),
        var updatedAtEpochSec: Long = 0L
    )

    private val membersByShortId: Map<UShort, Member> =
        authorizedRide.manifest.participants.mapNotNull { participant ->
            val pk = participant.pk?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
            val shortId = RideManifestKeyProvider.shortIdFromParticipantPublicKey(pk)
            shortId to Member(participant.uid, participant.gid, participant.rideRole, shortId)
        }.toMap()

    private val freshnessBySender = mutableMapOf<UShort, SenderFreshness>()
    private val rideId: String = authorizedRide.rideId

    companion object {
        /**
         * Un emisor que reinicia la app vuelve a `sequenceCounter = 0`. Para no
         * silenciarlo durante el resto de la rodada se permite un rebase acotado:
         * secuencia inicial muy baja, nonce nuevo y al menos 5 min sin tramas suyas.
         * La ventana de nonces NO se limpia, así que las tramas capturadas siguen
         * rechazándose aunque la secuencia vuelva a empezar.
         */
        const val REBASE_MAX_SEQUENCE: Int = 8
        const val REBASE_AFTER_SEC: Long = 300L
    }

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
     *
     * Con store activo se persiste además la última secuencia y la ventana de
     * nonces por `(rideId, senderShortId)`. Un nonce repetido se rechaza aunque
     * la secuencia haya "reiniciado" por un relanzamiento del emisor.
     */
    @Synchronized
    fun acceptSequence(shortId: UShort, sequence: UShort, nonce: ByteArray? = null): Boolean {
        val state = freshnessFor(shortId)
        val nonceKey = nonce?.hexKey()
        if (nonceKey != null && nonceKey in state.nonces) return false

        val now = nowEpochSec()
        if (state.hasSequence) {
            val previous = state.lastSequence
            if (sequence == previous) return false
            val delta = (sequence.toInt() - previous.toInt()) and 0xFFFF
            if (delta > 0x8000) {
                val senderRestarted = nonce != null &&
                    sequence.toInt() <= REBASE_MAX_SEQUENCE &&
                    state.updatedAtEpochSec > 0L &&
                    now - state.updatedAtEpochSec >= REBASE_AFTER_SEC
                if (!senderRestarted) return false
            }
        }

        state.lastSequence = sequence
        state.hasSequence = true
        state.updatedAtEpochSec = now
        if (nonceKey != null) {
            state.nonces.add(nonceKey)
            while (state.nonces.size > RideReplayStateStore.MAX_NONCES_PER_SENDER) {
                val oldest = state.nonces.iterator()
                oldest.next()
                oldest.remove()
            }
        }

        val persisted = replayStateStore?.save(
            RideReplayStateStore.SenderState(
                rideId = rideId,
                senderShortId = shortId.toInt(),
                lastSequence = sequence.toInt(),
                hasSequence = true,
                recentNonces = state.nonces.toList(),
                updatedAtEpochSec = now
            )
        ) ?: true
        return persisted
    }

    private fun freshnessFor(shortId: UShort): SenderFreshness =
        freshnessBySender.getOrPut(shortId) {
            val persisted = replayStateStore?.load(rideId, shortId.toInt())
            if (persisted != null) {
                SenderFreshness(
                    lastSequence = persisted.lastSequence.toUShort(),
                    hasSequence = persisted.hasSequence,
                    nonces = LinkedHashSet(persisted.recentNonces),
                    updatedAtEpochSec = persisted.updatedAtEpochSec
                )
            } else {
                SenderFreshness()
            }
        }

    private fun ByteArray.hexKey(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Se invoca al cerrar la rodada: se pierde la frescura en RAM y en disco. */
    fun resetFreshness() = synchronized(this) {
        freshnessBySender.clear()
        if (rideId.isNotBlank()) replayStateStore?.clear(rideId)
    }
}
