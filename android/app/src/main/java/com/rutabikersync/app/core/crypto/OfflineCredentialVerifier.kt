package com.rutabikersync.app.core.crypto

import com.rutabikersync.app.core.util.BillingPolicyConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.math.min

/**
 * Emisor y verificador offline de credenciales firmadas con Ed25519 (BACKEND_LICENSING.md §7):
 * - Soporta rotación de clave de firma mediante `kid` y llavero local (`AndroidCredentialKeyring`).
 * - Duración máxima de 7 días (`CREDENTIAL_TTL_DAYS = 7`), acotada por `effectiveAccessUntilEpochSec`
 *   (`current_period_end + gracia`).
 * - Comprueba firma Ed25519, `exp`, `gid`, `pk` del dispositivo y lista de revocación sincronizada.
 */
@Serializable
data class OfflineCredentialPayload(
    val v: Int = 1,
    val jti: String,
    val uid: String,
    val gid: String,
    val kind: String,
    val pk: String,
    val iat: Long,
    val exp: Long,
    val kid: String
)

enum class CredentialRejectionReason {
    MALFORMED_TOKEN,
    UNSUPPORTED_VERSION,
    UNKNOWN_KID,
    INVALID_SIGNATURE,
    CREDENTIAL_EXPIRED,
    GROUP_MISMATCH,
    DEVICE_PK_MISMATCH,
    REVOKED_USER_OR_CREDENTIAL
}

data class CredentialVerificationOutcome(
    val valid: Boolean,
    val rejectionReason: CredentialRejectionReason? = null,
    val payload: OfflineCredentialPayload? = null
)

class AndroidCredentialKeyring(initialKid: String = "ed25519-k1") {
    private val publicKeys = mutableMapOf<String, PublicKey>()
    private val privateKeys = mutableMapOf<String, PrivateKey>()
    var activeKid: String = initialKid
        private set

    init {
        generateAndRegisterKeyPair(initialKid)
    }

    fun generateAndRegisterKeyPair(kid: String): String {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val pair = kpg.generateKeyPair()
        publicKeys[kid] = pair.public
        privateKeys[kid] = pair.private
        activeKid = kid
        return Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    fun importPublicKeySpkiBase64(kid: String, spkiBase64: String) {
        val der = Base64.getDecoder().decode(spkiBase64)
        val pub = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
        publicKeys[kid] = pub
    }

    fun getPublicKey(kid: String): PublicKey? = publicKeys[kid]
    fun getActivePrivateKey(): PrivateKey? = privateKeys[activeKid]
}

object OfflineCredentialVerifier {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val TTL_SECONDS: Long = BillingPolicyConfig.CREDENTIAL_TTL_DAYS * 24L * 60L * 60L

    fun computeExpirySeconds(nowEpochSec: Long, effectiveAccessUntilEpochSec: Long): Long {
        require(effectiveAccessUntilEpochSec > nowEpochSec) {
            "SUBSCRIPTION_ACCESS_EXPIRED"
        }
        return min(nowEpochSec + TTL_SECONDS, effectiveAccessUntilEpochSec)
    }

    fun issueCredential(
        uid: String,
        gid: String,
        kind: String,
        devicePkBase64: String,
        effectiveAccessUntilEpochSec: Long,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
        keyring: AndroidCredentialKeyring
    ): String {
        val exp = computeExpirySeconds(nowEpochSec, effectiveAccessUntilEpochSec)
        val privKey = keyring.getActivePrivateKey()
            ?: error("MISSING_ACTIVE_ED25519_PRIVATE_KEY:${keyring.activeKid}")
        val payload = OfflineCredentialPayload(
            v = 1,
            jti = UUID.randomUUID().toString(),
            uid = uid,
            gid = gid,
            kind = kind,
            pk = devicePkBase64,
            iat = nowEpochSec,
            exp = exp,
            kid = keyring.activeKid
        )
        val payloadB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privKey)
        signer.update(payloadB64.toByteArray(Charsets.UTF_8))
        val sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
        return "$payloadB64.$sigB64"
    }

    fun verifyCredential(
        token: String,
        keyring: AndroidCredentialKeyring,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
        expectedGroupIds: Set<String>? = null,
        expectedDevicePkBase64: String? = null,
        revokedUserIds: Set<String> = emptySet(),
        revokedJtis: Set<String> = emptySet()
    ): CredentialVerificationOutcome {
        val parts = token.split(".")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return CredentialVerificationOutcome(false, CredentialRejectionReason.MALFORMED_TOKEN)
        }
        val payloadB64 = parts[0]
        val sigB64 = parts[1]

        val payload = runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(payloadB64), Charsets.UTF_8)
            json.decodeFromString<OfflineCredentialPayload>(decoded)
        }.getOrElse {
            return CredentialVerificationOutcome(false, CredentialRejectionReason.MALFORMED_TOKEN)
        }

        if (payload.v != 1) {
            return CredentialVerificationOutcome(false, CredentialRejectionReason.UNSUPPORTED_VERSION, payload)
        }

        val pubKey = keyring.getPublicKey(payload.kid)
            ?: return CredentialVerificationOutcome(false, CredentialRejectionReason.UNKNOWN_KID, payload)

        val sigBytes = runCatching { Base64.getUrlDecoder().decode(sigB64) }.getOrElse {
            return CredentialVerificationOutcome(false, CredentialRejectionReason.MALFORMED_TOKEN, payload)
        }

        val sigValid = runCatching {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(pubKey)
            verifier.update(payloadB64.toByteArray(Charsets.UTF_8))
            verifier.verify(sigBytes)
        }.getOrDefault(false)

        if (!sigValid) {
            return CredentialVerificationOutcome(false, CredentialRejectionReason.INVALID_SIGNATURE, payload)
        }

        if (payload.exp <= nowEpochSec) {
            return CredentialVerificationOutcome(false, CredentialRejectionReason.CREDENTIAL_EXPIRED, payload)
        }

        if (expectedGroupIds != null && payload.gid !in expectedGroupIds) {
            return CredentialVerificationOutcome(false, CredentialRejectionReason.GROUP_MISMATCH, payload)
        }

        if (expectedDevicePkBase64 != null && payload.pk != expectedDevicePkBase64) {
            return CredentialVerificationOutcome(false, CredentialRejectionReason.DEVICE_PK_MISMATCH, payload)
        }

        if (payload.uid in revokedUserIds || payload.jti in revokedJtis) {
            return CredentialVerificationOutcome(false, CredentialRejectionReason.REVOKED_USER_OR_CREDENTIAL, payload)
        }

        return CredentialVerificationOutcome(true, null, payload)
    }

    fun issueRideManifest(
        rideId: String,
        hostGroupId: String,
        cohostGroupId: String? = null,
        hostLeaderId: String,
        cohostLeaderId: String? = null,
        routeHash: String? = null,
        participants: List<RideManifestParticipantPayload>,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
        ttlSeconds: Long = 24L * 60L * 60L,
        keyring: AndroidCredentialKeyring
    ): String {
        val groupIds = if (cohostGroupId != null) {
            listOf(hostGroupId, cohostGroupId).distinct()
        } else {
            listOf(hostGroupId)
        }
        require(groupIds.size in 1..2) {
            "R6.3: Un manifiesto de rodada solo admite 1 o 2 grupos"
        }
        val privKey = keyring.getActivePrivateKey()
            ?: error("MISSING_ACTIVE_ED25519_PRIVATE_KEY:${keyring.activeKid}")
        val payload = RideManifestPayload(
            v = 1,
            type = "ride_manifest",
            rideId = rideId,
            hostGroupId = hostGroupId,
            cohostGroupId = cohostGroupId,
            groupIds = groupIds,
            hostLeaderId = hostLeaderId,
            cohostLeaderId = cohostLeaderId,
            routeHash = routeHash,
            participants = participants,
            iat = nowEpochSec,
            exp = nowEpochSec + ttlSeconds,
            kid = keyring.activeKid
        )
        val payloadB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privKey)
        signer.update(payloadB64.toByteArray(Charsets.UTF_8))
        val sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
        return "$payloadB64.$sigB64"
    }

    fun verifyRideManifest(
        manifestToken: String,
        keyring: AndroidCredentialKeyring,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
        requireParticipantUid: String? = null
    ): RideManifestVerificationOutcome {
        val parts = manifestToken.split(".")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return RideManifestVerificationOutcome(false, "MALFORMED_MANIFEST")
        }
        val payloadB64 = parts[0]
        val sigB64 = parts[1]

        val payload = runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(payloadB64), Charsets.UTF_8)
            json.decodeFromString<RideManifestPayload>(decoded)
        }.getOrElse {
            return RideManifestVerificationOutcome(false, "MALFORMED_MANIFEST")
        }

        if (payload.v != 1 || payload.type != "ride_manifest") {
            return RideManifestVerificationOutcome(false, "UNSUPPORTED_VERSION", payload)
        }
        if (payload.groupIds.size !in 1..2) {
            return RideManifestVerificationOutcome(false, "INVALID_GROUP_COUNT", payload)
        }

        val pubKey = keyring.getPublicKey(payload.kid)
            ?: return RideManifestVerificationOutcome(false, "UNKNOWN_KID", payload)

        val sigBytes = runCatching { Base64.getUrlDecoder().decode(sigB64) }.getOrElse {
            return RideManifestVerificationOutcome(false, "MALFORMED_MANIFEST", payload)
        }

        val sigValid = runCatching {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(pubKey)
            verifier.update(payloadB64.toByteArray(Charsets.UTF_8))
            verifier.verify(sigBytes)
        }.getOrDefault(false)

        if (!sigValid) {
            return RideManifestVerificationOutcome(false, "INVALID_SIGNATURE", payload)
        }
        if (payload.exp <= nowEpochSec) {
            return RideManifestVerificationOutcome(false, "MANIFEST_EXPIRED", payload)
        }
        if (requireParticipantUid != null && payload.participants.none { it.uid == requireParticipantUid }) {
            return RideManifestVerificationOutcome(false, "PARTICIPANT_NOT_AUTHORIZED", payload)
        }
        return RideManifestVerificationOutcome(true, null, payload)
    }
}

@Serializable
data class RideManifestParticipantPayload(
    val uid: String,
    val gid: String,
    val statusKind: String,
    val rideRole: String? = null,
    val pk: String? = null
)

@Serializable
data class RideManifestPayload(
    val v: Int = 1,
    val type: String = "ride_manifest",
    val rideId: String,
    val hostGroupId: String,
    val cohostGroupId: String? = null,
    val groupIds: List<String>,
    val hostLeaderId: String,
    val cohostLeaderId: String? = null,
    val routeHash: String? = null,
    val participants: List<RideManifestParticipantPayload>,
    val rideSecretKeyHex: String? = null,
    val iat: Long,
    val exp: Long,
    val kid: String
) {
    fun resolveRideSecretKey(): ByteArray? {
        val hex = rideSecretKeyHex?.trim()?.takeIf { it.length == 64 } ?: return null
        return try {
            ByteArray(32) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }
}

data class RideManifestVerificationOutcome(
    val valid: Boolean,
    val rejectionReason: String? = null,
    val payload: RideManifestPayload? = null
)
