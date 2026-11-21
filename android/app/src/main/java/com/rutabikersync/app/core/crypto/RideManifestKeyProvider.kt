package com.rutabikersync.app.core.crypto

import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * P0 auditoría 2026-11-21.
 *
 * Única fuente autorizada de la clave simétrica de la malla. La clave se obtiene
 * del manifiesto firmado con Ed25519 por el backend; nunca de un literal en el
 * código ni de un valor aleatorio local. Si el manifiesto no se puede verificar,
 * esta función falla en cerrado (`null`) y la sesión BLE no debe iniciarse.
 *
 * El manifiesto se conserva solo en memoria y se olvida al llamar a [clear].
 */
object RideManifestKeyProvider {

    data class AuthorizedRide(
        val rideId: String,
        val manifest: RideManifestPayload,
        val rideSecretKey: ByteArray,
        val participantUid: String,
        val groupIds: Set<String>,
        val authorizedShortIds: Set<UShort>,
        /** shortId real del participante local, derivado de su `pk` firmada. */
        val localShortId: UShort
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, AuthorizedRide>()

    /**
     * Deriva el shortId de un participante a partir de la clave pública Ed25519
     * incluida en el manifiesto firmado. Es una identidad real del roster, no un
     * peer inventado: dos equipos con el mismo `pk` calculan el mismo shortId.
     */
    fun shortIdFromParticipantPublicKey(publicKeyBase64: String): UShort {
        val raw = runCatching { java.util.Base64.getDecoder().decode(publicKeyBase64) }
            .getOrDefault(publicKeyBase64.toByteArray(Charsets.UTF_8))
        return ((raw.contentHashCode() and 0x7FFF) + 1).toUShort()
    }

    suspend fun resolve(
        rideId: String,
        currentUserId: String?,
        apiClient: ClubApiClient = ClubApiClient.getInstance(),
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
        forceRefresh: Boolean = false
    ): AuthorizedRide? {
        if (!forceRefresh) cache[rideId]?.let { return it }
        val uid = currentUserId?.trim().takeUnless { it.isNullOrBlank() } ?: return null

        return mutex.withLock {
            if (!forceRefresh) cache[rideId]?.let { return@withLock it }

            val response = apiClient.fetchRideManifest(rideId).getOrNull()
                ?.takeIf { it.isSuccess }
                ?: return@withLock null
            val body = response.body ?: return@withLock null
            val token = body["manifest"]?.jsonPrimitive?.contentOrNull
                ?.trim().takeUnless { it.isNullOrBlank() }
                ?: return@withLock null

            // Los publicKeys del backend son kid -> SPKI Base64. Se importan en un
            // llavero efímero: el manifiesto se verifica contra la firma del servidor,
            // no contra claves generadas en este móvil.
            val keyring = AndroidCredentialKeyring()
            body["publicKeys"]?.jsonObject?.entries?.forEach { (kid, value) ->
                val spki = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
                if (!spki.isNullOrBlank()) {
                    runCatching { keyring.importPublicKeySpkiBase64(kid, spki) }
                }
            }

            val outcome = OfflineCredentialVerifier.verifyRideManifest(
                manifestToken = token,
                keyring = keyring,
                nowEpochSec = nowEpochSec,
                requireParticipantUid = uid
            )
            val payload = outcome.payload?.takeIf { outcome.valid } ?: return@withLock null
            val key = payload.resolveRideSecretKey() ?: return@withLock null
            val participant = payload.participants.firstOrNull { it.uid == uid }
                ?: return@withLock null
            if (participant.gid !in payload.groupIds) return@withLock null
            val localPk = participant.pk?.trim().takeUnless { it.isNullOrBlank() } ?: return@withLock null

            val authorized = AuthorizedRide(
                rideId = payload.rideId,
                manifest = payload,
                rideSecretKey = key,
                participantUid = uid,
                groupIds = payload.groupIds.toSet(),
                authorizedShortIds = payload.participants.mapNotNull { p ->
                    p.pk?.trim()?.takeIf { it.isNotBlank() }?.let(::shortIdFromParticipantPublicKey)
                }.toSet(),
                localShortId = shortIdFromParticipantPublicKey(localPk)
            )
            cache[rideId] = authorized
            authorized
        }
    }

    fun cached(rideId: String): AuthorizedRide? = cache[rideId]

    fun clear() = cache.clear()
}
