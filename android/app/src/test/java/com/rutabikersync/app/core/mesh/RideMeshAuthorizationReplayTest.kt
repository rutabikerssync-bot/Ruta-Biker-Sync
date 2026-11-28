package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.crypto.RideManifestKeyProvider
import com.rutabikersync.app.core.crypto.RideManifestParticipantPayload
import com.rutabikersync.app.core.crypto.RideManifestPayload
import com.rutabikersync.app.core.crypto.RideReplayStateStore
import com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

/** Auditoría integral 2026-11-27 · anti-replay persistente por rodada y emisor. */
class RideMeshAuthorizationReplayTest {

    @Before
    fun setUp() {
        RideReplayStateStore().clearAll()
    }

    private fun pkOf(seed: String): String =
        Base64.getEncoder().encodeToString(seed.toByteArray(Charsets.UTF_8))

    private fun nonce(seed: Int): ByteArray = ByteArray(12) { (seed + it).toByte() }

    private fun authorizedRide(rideId: String): RideManifestKeyProvider.AuthorizedRide {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val leaderKeys = kpg.generateKeyPair()
        val memberKeys = kpg.generateKeyPair()
        val leaderPk = pkOf("pk-leader-replay")
        val memberPk = pkOf("pk-member-replay")
        val leaderSid = RideManifestKeyProvider.shortIdFromParticipantPublicKey(leaderPk)
        val rideKey = "0123456789abcdef0123456789abcdef".toByteArray()
        val sosPublicKeys = mapOf(
            "u-leader" to Base64.getEncoder().encodeToString(
                Ed25519SosCrypto.exportRawPublicKey(leaderKeys.public)
            ),
            "u-member" to Base64.getEncoder().encodeToString(
                Ed25519SosCrypto.exportRawPublicKey(memberKeys.public)
            )
        )
        val manifest = RideManifestPayload(
            rideId = rideId,
            hostGroupId = "club-a",
            groupIds = listOf("club-a"),
            hostLeaderId = "u-leader",
            participants = listOf(
                RideManifestParticipantPayload(
                    uid = "u-leader", gid = "club-a", statusKind = "confirmed",
                    rideRole = "road_marshal", pk = leaderPk
                ),
                RideManifestParticipantPayload(
                    uid = "u-member", gid = "club-a", statusKind = "confirmed",
                    rideRole = null, pk = memberPk
                )
            ),
            rideSecretKeyHex = rideKey.joinToString("") { "%02x".format(it) },
            sosPublicKeys = sosPublicKeys,
            rideState = "active",
            iat = 0L,
            exp = Long.MAX_VALUE,
            kid = "replay-kid"
        )
        val resolver = RideManifestKeyProvider.buildSosPublicKeyResolver(manifest)
            ?: error("El fixture del manifiesto debe incluir claves SOS válidas")
        return RideManifestKeyProvider.AuthorizedRide(
            rideId = manifest.rideId,
            manifest = manifest,
            rideSecretKey = rideKey,
            participantUid = "u-leader",
            groupIds = manifest.groupIds.toSet(),
            authorizedShortIds = manifest.participants.mapNotNull {
                it.pk?.let(RideManifestKeyProvider::shortIdFromParticipantPublicKey)
            }.toSet(),
            localShortId = leaderSid,
            sosPublicKeyResolver = resolver
        )
    }

    @Test
    fun `rechaza secuencia y nonce repetidos tras reiniciar el proceso`() {
        val store = RideReplayStateStore()
        val ride = authorizedRide("ride-replay-auth")
        val sid = ride.localShortId
        val first = RideMeshAuthorization(ride, store, nowEpochSec = { 100L })

        assertTrue(first.acceptSequence(sid, 10u, nonce(1)))

        val afterRestart = RideMeshAuthorization(ride, store, nowEpochSec = { 101L })
        assertFalse("Mismo nonce y secuencia", afterRestart.acceptSequence(sid, 10u, nonce(1)))
        assertFalse("Secuencia anterior", afterRestart.acceptSequence(sid, 9u, nonce(2)))
        assertFalse("Secuencia anterior con nonce capturado", afterRestart.acceptSequence(sid, 9u, nonce(1)))
        assertTrue("Secuencia nueva", afterRestart.acceptSequence(sid, 11u, nonce(2)))
    }

    @Test
    fun `el estado queda aislado por rodada y por emisor`() {
        val store = RideReplayStateStore()
        val rideA = authorizedRide("ride-replay-A")
        val rideB = authorizedRide("ride-replay-B")
        val sid = rideA.localShortId
        val memberSid = rideA.authorizedShortIds.first { it != sid }

        assertTrue(RideMeshAuthorization(rideA, store, { 100L }).acceptSequence(sid, 20u, nonce(3)))

        assertFalse(
            RideMeshAuthorization(rideA, store, { 101L }).acceptSequence(sid, 20u, nonce(4))
        )
        assertTrue(
            "Otra rodada no hereda la frescura",
            RideMeshAuthorization(rideB, store, { 101L }).acceptSequence(sid, 20u, nonce(4))
        )
        assertTrue(
            "Otro emisor no hereda la frescura",
            RideMeshAuthorization(rideA, store, { 101L }).acceptSequence(memberSid, 5u, nonce(4))
        )
    }

    @Test
    fun `rebase acotado permite reinicio del emisor sin aceptar nonces repetidos`() {
        val store = RideReplayStateStore()
        val ride = authorizedRide("ride-replay-rebase")
        val sid = ride.localShortId
        assertTrue(
            RideMeshAuthorization(ride, store, { 100L }).acceptSequence(sid, 100u, nonce(10))
        )

        val tooSoon = RideMeshAuthorization(ride, store, { 100L + RideMeshAuthorization.REBASE_AFTER_SEC - 1 })
        assertFalse("Antes de 300 s no hay rebase", tooSoon.acceptSequence(sid, 0u, nonce(11)))

        val afterWait = RideMeshAuthorization(ride, store, { 100L + RideMeshAuthorization.REBASE_AFTER_SEC })
        assertFalse("Nonce repetido bloquea el rebase", afterWait.acceptSequence(sid, 0u, nonce(10)))
        assertFalse("Rebase solo para secuencias iniciales", afterWait.acceptSequence(sid, 9u, nonce(11)))
        assertTrue("Reinicio legítimo del emisor", afterWait.acceptSequence(sid, 0u, nonce(11)))
    }

    @Test
    fun `cerrar la rodada limpia la frescura persistida`() {
        val store = RideReplayStateStore()
        val ride = authorizedRide("ride-replay-close")
        val sid = ride.localShortId
        val authorization = RideMeshAuthorization(ride, store, { 100L })

        assertTrue(authorization.acceptSequence(sid, 5u, nonce(20)))
        authorization.resetFreshness()

        assertTrue(
            "Tras cerrar la rodada debe poder arrancar una nueva",
            RideMeshAuthorization(ride, store, { 101L }).acceptSequence(sid, 5u, nonce(20))
        )
    }
}
