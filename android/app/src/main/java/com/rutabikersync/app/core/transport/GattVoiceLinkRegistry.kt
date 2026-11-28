package com.rutabikersync.app.core.transport

/**
 * Política pura (sin Android) de enlaces GATT de voz (P0.2).
 *
 * El flag `multipeerEnabled=false` conserva exactamente el comportamiento anterior de un
 * solo enlace para poder hacer rollback sin revertir el transporte completo. Con `true`,
 * cada shortId autorizado por el manifiesto tiene su propio enlace y el envío hace fan-out.
 */
enum class GattVoiceRole { SERVER, CLIENT }

class GattVoiceLinkRegistry(
    private val localShortId: UShort,
    private val multipeerEnabled: Boolean
) {
    private data class Peer(var address: String, val role: GattVoiceRole)

    private val allowedPeers = HashSet<UShort>()
    private val peers = LinkedHashMap<UShort, Peer>()

    /** El dueño del servidor lo decide el shortId, sin depender del orden de descubrimiento. */
    fun roleFor(peerShortId: UShort): GattVoiceRole =
        if (localShortId < peerShortId) GattVoiceRole.SERVER else GattVoiceRole.CLIENT

    @Synchronized
    fun setAllowedPeers(ids: Set<UShort>) {
        allowedPeers.clear()
        allowedPeers.addAll(ids.filter { it != localShortId })
        peers.keys.toList().filterNot { it in allowedPeers }.forEach { peers.remove(it) }
    }

    /**
     * Registra/actualiza la MAC de un peer autenticado. Devuelve false para el nodo local,
     * peers fuera del manifiesto y para un segundo peer cuando el flag de multipeer está
     * desactivado.
     */
    @Synchronized
    fun accept(peerShortId: UShort, address: String): Boolean {
        if (peerShortId == localShortId || peerShortId !in allowedPeers) return false
        val existing = peers[peerShortId]
        if (existing != null) {
            existing.address = address
            return true
        }
        if (!multipeerEnabled && peers.isNotEmpty()) return false
        peers[peerShortId] = Peer(address, roleFor(peerShortId))
        return true
    }

    @Synchronized
    fun addressFor(peerShortId: UShort): String? = peers[peerShortId]?.address

    @Synchronized
    fun roleOf(peerShortId: UShort): GattVoiceRole? = peers[peerShortId]?.role

    @Synchronized
    fun isAllowed(peerShortId: UShort): Boolean = peerShortId in allowedPeers

    /** Destinos de una ráfaga: todos los enlaces listos o solo el primero si hay rollback. */
    @Synchronized
    fun sendTargets(readyShortIds: Set<UShort>): List<UShort> {
        val ready = peers.keys.filter { it in readyShortIds }.sortedBy { it.toInt() }
        return if (multipeerEnabled) ready else ready.take(1)
    }

    @Synchronized
    fun remove(peerShortId: UShort) {
        peers.remove(peerShortId)
    }

    @Synchronized
    fun clear() {
        peers.clear()
    }

    @Synchronized
    fun snapshot(): Map<UShort, String> = peers.mapValues { (_, peer) -> peer.address }
}
