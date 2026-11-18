package com.rutabikersync.app.core.mesh.sos

import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Resuelve la clave pública Ed25519 de un piloto a partir de su shortId de malla (Fase 6 · Hito 4 · Opción B).
 */
fun interface SosPublicKeyResolver {
    fun resolvePublicKey(shortId: UShort): PublicKey?
}

/**
 * Modelo de participante en el manifiesto firmado de rodada (Fase 6 · Hito 4).
 */
data class SosParticipant(
    val shortId: UShort,
    val publicKeyEd25519Base64: String,
    val alias: String
)

/**
 * Manifiesto de rodada para autenticación de alertas SOS en malla off-grid (Fase 6 · Hito 4).
 */
data class SosRideManifest(
    val rideId: String,
    val clubId: String,
    val participants: List<SosParticipant>,
    val signedByLeader: String,
    val medicalRescueKeyHex: String? = null
)

/**
 * Implementación en memoria de [SosPublicKeyResolver] para registrar y consultar claves públicas de la rodada.
 */
class InMemorySosPublicKeyResolver : SosPublicKeyResolver {
    private val keyMap = ConcurrentHashMap<UShort, PublicKey>()

    fun registerPeer(shortId: UShort, publicKey: PublicKey) {
        keyMap[shortId] = publicKey
    }

    fun removePeer(shortId: UShort) {
        keyMap.remove(shortId)
    }

    fun clear() {
        keyMap.clear()
    }

    override fun resolvePublicKey(shortId: UShort): PublicKey? = keyMap[shortId]
}

