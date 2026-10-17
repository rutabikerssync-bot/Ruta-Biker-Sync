package com.rutabikersync.app.feature.groups

import java.text.Normalizer
import kotlin.math.min

enum class AccountType {
    LEADER,
    MEMBER
}

enum class MemberStatusKind(val apiValue: String) {
    FULL("full"),
    PROSPECT("prospect"),
    GUEST("guest")
}

enum class ClubRole(val apiValue: String) {
    PRESIDENT("president"),
    VICE_PRESIDENT("vice_president"),
    ROAD_CAPTAIN("road_captain"),
    SERGEANT_AT_ARMS("sergeant_at_arms"),
    SECRETARY("secretary"),
    TREASURER("treasurer"),
    ROAD_MARSHAL("road_marshal")
}

enum class PaymentSemaphore {
    GREEN,
    YELLOW,
    RED
}

enum class RiderRank(val minValidRides: Int) {
    NOVICE(0),
    RIDER(3),
    VETERAN(10),
    ELITE(25),
    LEGEND(50);

    companion object {
        fun fromValidRides(validRides: Int): RiderRank = when {
            validRides >= 50 -> LEGEND
            validRides >= 25 -> ELITE
            validRides >= 10 -> VETERAN
            validRides >= 3 -> RIDER
            else -> NOVICE
        }
    }
}

data class RolePermissionSet(
    val canCreateRide: Boolean = false,
    val canEditRoutes: Boolean = false,
    val canViewRideEmergencySheet: Boolean = false,
    val canModerateInRide: Boolean = false,
    val canSendConvocations: Boolean = false,
    val canViewPaymentSemaphore: Boolean = false,
    val canConfigureDeviationAlert: Boolean = false
)

object ClubRulesEngine {
    /** Permisos por defecto por cargo del club según RULES.md R3.2 */
    val defaultRolePermissions: Map<ClubRole, RolePermissionSet> = mapOf(
        ClubRole.PRESIDENT to RolePermissionSet(
            canCreateRide = true,
            canEditRoutes = true,
            canViewRideEmergencySheet = true,
            canModerateInRide = true,
            canSendConvocations = true,
            canViewPaymentSemaphore = true,
            canConfigureDeviationAlert = true
        ),
        ClubRole.VICE_PRESIDENT to RolePermissionSet(
            canCreateRide = true,
            canEditRoutes = true,
            canViewRideEmergencySheet = true,
            canModerateInRide = true,
            canSendConvocations = true,
            canViewPaymentSemaphore = true,
            canConfigureDeviationAlert = true
        ),
        ClubRole.ROAD_CAPTAIN to RolePermissionSet(
            canCreateRide = true,
            canEditRoutes = true,
            canConfigureDeviationAlert = true
        ),
        ClubRole.SERGEANT_AT_ARMS to RolePermissionSet(
            canViewRideEmergencySheet = true,
            canModerateInRide = true
        ),
        ClubRole.SECRETARY to RolePermissionSet(
            canSendConvocations = true
        ),
        ClubRole.TREASURER to RolePermissionSet(
            canViewPaymentSemaphore = true
        ),
        ClubRole.ROAD_MARSHAL to RolePermissionSet(
            canConfigureDeviationAlert = true
        )
    )

    private val leetMap = mapOf(
        '0' to 'o',
        '1' to 'i',
        '3' to 'e',
        '4' to 'a',
        '5' to 's',
        '7' to 't',
        '8' to 'b'
    )

    /**
     * Normaliza el nombre de grupo según RULES.md R5.1 y BACKEND_LICENSING.md §8:
     * minúsculas, sin tildes, solo alfanuméricos.
     */
    fun normalizeGroupName(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val withoutDiacritics = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return withoutDiacritics.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    /**
     * Convierte números de sustitución visual (leet speak) y colapsa letras repetidas.
     */
    fun toLeetSkeleton(raw: String): String {
        val norm = normalizeGroupName(raw)
        val sb = StringBuilder()
        var prev = '\u0000'
        for (ch in norm) {
            val mapped = leetMap[ch] ?: ch
            if (mapped != prev) {
                sb.append(mapped)
                prev = mapped
            }
        }
        return sb.toString()
    }

    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    data class NameCollisionResult(
        val collides: Boolean,
        val reason: String? = null
    )

    /**
     * Verifica colisión de nombre en el mismo país (RULES.md R5.1).
     */
    fun checkGroupNameCollision(candidate: String, existing: String): NameCollisionResult {
        val normA = normalizeGroupName(candidate)
        val normB = normalizeGroupName(existing)
        if (normA == normB) {
            return NameCollisionResult(true, "EXACT_NORMALIZED")
        }
        val skelA = toLeetSkeleton(candidate)
        val skelB = toLeetSkeleton(existing)
        if (skelA.isNotEmpty() && skelA == skelB) {
            return NameCollisionResult(true, "LEET_EQUIVALENT")
        }
        if (min(skelA.length, skelB.length) >= 5 && levenshteinDistance(skelA, skelB) <= 1) {
            return NameCollisionResult(true, "NEAR_DUPLICATE")
        }
        return NameCollisionResult(false, null)
    }

    /**
     * Distancia Hamming entre dos hashes perceptuales de 64 bits (16 caracteres hex) según RULES.md R5.2.
     */
    fun computePhashHammingDistance(hexA: String, hexB: String): Int {
        require(hexA.length == 16 && hexB.length == 16) {
            "Los pHash deben tener 16 caracteres hexadecimales (64 bits)"
        }
        val a = hexA.toULong(16)
        val b = hexB.toULong(16)
        return (a xor b).countOneBits()
    }

    data class LogoSimilarityDecision(
        val rejected: Boolean,
        val needsManualReview: Boolean,
        val distance: Int
    )

    fun evaluateLogoSimilarity(candidateHex: String, existingHex: String): LogoSimilarityDecision {
        val distance = computePhashHammingDistance(candidateHex, existingHex)
        return LogoSimilarityDecision(
            rejected = distance < 8,
            needsManualReview = distance in 8..12,
            distance = distance
        )
    }

    /**
     * Mayoría de líderes para decisiones sensibles (RULES.md R14.2):
     * 1 líder -> 1 voto; 2 líderes -> 2 votos; 3 líderes -> 2 de 3 votos.
     */
    fun requiredVotesForLeaderMajority(leaderCount: Int): Int = when {
        leaderCount <= 1 -> 1
        leaderCount == 2 -> 2
        else -> 2
    }

    /**
     * Semáforo de pago por miembro (RULES.md R7.3):
     * Verde (>5 días), Amarillo (1..5 días o en gracia), Rojo (vencido).
     */
    fun computePaymentSemaphore(
        paidUntilEpochMs: Long?,
        graceUntilEpochMs: Long?,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PaymentSemaphore {
        if (paidUntilEpochMs == null) return PaymentSemaphore.RED
        val remainingMs = paidUntilEpochMs - nowEpochMs
        val fiveDaysMs = 5L * 24L * 60L * 60L * 1000L
        return when {
            remainingMs > fiveDaysMs -> PaymentSemaphore.GREEN
            remainingMs > 0L -> PaymentSemaphore.YELLOW
            graceUntilEpochMs != null && graceUntilEpochMs > nowEpochMs -> PaymentSemaphore.YELLOW
            else -> PaymentSemaphore.RED
        }
    }
}

data class UserProfileModel(
    val id: String,
    val email: String,
    val displayName: String,
    val countryCode: String,
    val accountType: AccountType,
    val bikeModel: String,
    val mapColorHex: String,
    val validRides: Int,
    val hasFounderFlame: Boolean,
    val badges: List<String>
) {
    val rank: RiderRank get() = RiderRank.fromValidRides(validRides)
    val initials: String
        get() = displayName
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "RB" }
}

data class ClubMemberModel(
    val userId: String,
    val displayName: String,
    val bikeModel: String,
    val statusKind: MemberStatusKind,
    val clubRole: ClubRole?,
    val isLeader: Boolean,
    val isFounder: Boolean,
    val paymentSemaphore: PaymentSemaphore,
    val validRides: Int
)

data class ClubInvitationModel(
    val id: String,
    val groupId: String,
    val groupName: String,
    val countryCode: String,
    val invitedUserId: String,
    val statusKind: MemberStatusKind,
    val monthlyCostUsd: Double = 2.50,
    val state: String = "pending"
)

data class LeaderVoteModel(
    val id: String,
    val groupId: String,
    val proposal: String,
    val targetLabel: String,
    val approvals: Set<String>,
    val rejections: Set<String>,
    val requiredVotes: Int,
    val state: String
)

data class ClubGroupModel(
    val id: String,
    val countryCode: String,
    val name: String,
    val nameNormalized: String,
    val logoPhashHex: String,
    val principalLeaderId: String,
    val leaderIds: List<String>,
    val members: List<ClubMemberModel>,
    val rolePermissions: Map<ClubRole, RolePermissionSet> = ClubRulesEngine.defaultRolePermissions,
    val pendingTransferToUserId: String? = null,
    val pendingTransferAvailableAtEpochMs: Long? = null,
    val openVotes: List<LeaderVoteModel> = emptyList()
)
