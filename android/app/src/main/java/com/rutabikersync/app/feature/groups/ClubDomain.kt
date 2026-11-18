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

enum class MemberClass(val displayName: String) {
    FOUNDER("Fundador"),
    FULL_MEMBER("Full Member"),
    PROSPECT("Prospecto"),
    GUEST("Invitado");

    companion object {
        fun from(status: MemberStatusKind, isFounder: Boolean): MemberClass = when {
            isFounder -> FOUNDER
            status == MemberStatusKind.FULL -> FULL_MEMBER
            status == MemberStatusKind.PROSPECT -> PROSPECT
            else -> GUEST
        }
    }
}

enum class ClubTier(
    val code: String,
    val displayName: String,
    val patchDescription: String,
    val displacementRequirement: String,
    val prospectPeriod: String,
    val minMembers: Int,
    val structureDescription: String
) {
    RC(
        code = "RC",
        displayName = "Racing Club / Riding Club",
        patchDescription = "1 a 2 piezas (emblema central). Sin rockers ni siglas MC.",
        displacementRequirement = "Libre o enfocada en pista/velocidad",
        prospectPeriod = "No requerido (ingreso directo o por invitación)",
        minMembers = 2,
        structureDescription = "Coordinador/Líder y Capitán de Ruta"
    ),
    MF(
        code = "MF",
        displayName = "Moto Familia",
        patchDescription = "1 o 2 piezas (nombre y emblema) con siglas 'MF', sin territorio inferior.",
        displacementRequirement = "Libre (prioriza convivencia y unión familiar)",
        prospectPeriod = "Opcional o breve (periodo de integración)",
        minMembers = 3,
        structureDescription = "Presidente/Representante y Tesorero"
    ),
    MG(
        code = "MG",
        displayName = "Moto Grupo",
        patchDescription = "2 piezas (Top rocker + emblema) con siglas 'MG'. Sin Bottom Rocker territorial.",
        displacementRequirement = "Sugerido ≥ 150cc o por estatuto",
        prospectPeriod = "Recomendado (8 a 13 meses de convivencia y rodadas)",
        minMembers = 3,
        structureDescription = "Presidente, Vicepresidente, Tesorero y Capitán de Ruta"
    ),
    MC(
        code = "MC",
        displayName = "Moto Club",
        patchDescription = "3 piezas completas (Top Rocker, Centro, Bottom Rocker territorial) y cuadro oficial 'MC'.",
        displacementRequirement = "Media/alta cilindrada rutera (sugerido ≥ 250cc)",
        prospectPeriod = "Obligatorio y estricto (12 a 18 meses: Hangaround y Prospect)",
        minMembers = 8,
        structureDescription = "Mesa completa (Presidente, VP, Secretario, Tesorero, Sargento de Armas, Capitán de Ruta)"
    );

    companion object {
        @JvmField
        val GANG: ClubTier = MF

        fun fromCode(code: String?): ClubTier = entries.find {
            it.code.equals(code, ignoreCase = true) || (it == MF && code.equals("GANG", ignoreCase = true))
        } ?: MC
    }
}

enum class ClubRole(val apiValue: String, val legacyApiValue: String = apiValue) {
    PRESIDENT("presidente", "president"),
    VICE_PRESIDENT("vice", "vice_president"),
    ROAD_CAPTAIN("capitan_ruta", "road_captain"),
    SERGEANT_AT_ARMS("sargento_armas", "sergeant_at_arms"),
    SECRETARY("secretario", "secretary"),
    TREASURER("tesorero", "treasurer"),
    ROAD_MARSHAL("road_marshal", "road_marshal");

    companion object {
        fun fromApi(value: String?): ClubRole? {
            if (value == null) return null
            return entries.find {
                it.apiValue.equals(value, ignoreCase = true) ||
                it.legacyApiValue.equals(value, ignoreCase = true) ||
                it.name.equals(value, ignoreCase = true)
            }
        }
    }
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

    data class GroupCreationValidationResult(
        val isValid: Boolean,
        val errorCode: String? = null,
        val displayMessage: String,
        val parentGroup: ClubGroupModel? = null
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
     * Valida creación de grupo o extensión (capítulo):
     * - En un país NO pueden coexistir dos grupos con el mismo nombre a menos que sea una EXTENSIÓN.
     * - La extensión DEBE estar obligatoriamente en OTRO estado diferente al club matriz y sus otros capítulos.
     * - Debe especificar y vincular formalmente al grupo principal matriz.
     */
    fun validateGroupCreationOrExtension(
        candidateName: String,
        candidateCountry: String,
        candidateState: String,
        isExtension: Boolean,
        parentGroupId: String?,
        existingGroups: List<ClubGroupModel>
    ): GroupCreationValidationResult {
        if (candidateName.trim().length < 3) {
            return GroupCreationValidationResult(false, "NAME_TOO_SHORT", "El nombre debe tener al menos 3 caracteres")
        }
        val sameCountryGroups = existingGroups.filter {
            it.countryCode.equals(candidateCountry.trim(), ignoreCase = true)
        }

        val collidingGroup = sameCountryGroups.firstOrNull { existing ->
            checkGroupNameCollision(candidateName, existing.name).collides
        }

        if (collidingGroup != null) {
            if (!isExtension) {
                return GroupCreationValidationResult(
                    isValid = false,
                    errorCode = "DUPLICATE_NAME_IN_COUNTRY",
                    displayMessage = "En ${candidateCountry.uppercase()} ya existe un club llamado '${collidingGroup.name}' en ${collidingGroup.stateRegion}. No pueden convivir dos grupos con el mismo nombre a menos que sea una Extensión Oficial.",
                    parentGroup = collidingGroup
                )
            }

            // Es extensión: verificar que el parentGroupId corresponda al club matriz
            val parent = collidingGroup.parentGroupId?.let { pid -> sameCountryGroups.find { it.id == pid } } ?: collidingGroup
            if (parentGroupId != null && parentGroupId != parent.id && parentGroupId != collidingGroup.id) {
                return GroupCreationValidationResult(
                    isValid = false,
                    errorCode = "INVALID_PARENT",
                    displayMessage = "La extensión debe vincularse directamente al Club Matriz '${parent.name}'."
                )
            }

            // Debe estar en OTRO estado: no puede repetir el estado del club matriz ni de sus capítulos
            val sameStateExists = sameCountryGroups.any { g ->
                (g.id == parent.id || g.parentGroupId == parent.id) &&
                g.stateRegion.trim().equals(candidateState.trim(), ignoreCase = true)
            }

            if (sameStateExists) {
                return GroupCreationValidationResult(
                    isValid = false,
                    errorCode = "SAME_STATE_PROHIBITED",
                    displayMessage = "Ya existe una sede de '${parent.name}' en el estado ${candidateState}. Las extensiones deben ubicarse en otro estado de ${candidateCountry.uppercase()}."
                )
            }

            return GroupCreationValidationResult(
                isValid = true,
                displayMessage = "✓ Extensión válida: '${parent.name} - Capítulo ${candidateState}' (Vinculada al Club Matriz)",
                parentGroup = parent
            )
        }

        // Si no hay colisión previa de nombre en el país:
        if (isExtension) {
            val parent = sameCountryGroups.find { it.id == parentGroupId }
            if (parent == null) {
                return GroupCreationValidationResult(
                    isValid = false,
                    errorCode = "PARENT_NOT_FOUND",
                    displayMessage = "Debes seleccionar un Club Matriz registrado en ${candidateCountry.uppercase()} para crear una extensión oficial."
                )
            }
            if (parent.stateRegion.trim().equals(candidateState.trim(), ignoreCase = true)) {
                return GroupCreationValidationResult(
                    isValid = false,
                    errorCode = "SAME_STATE_PROHIBITED",
                    displayMessage = "El Club Matriz ya opera en ${parent.stateRegion}. La extensión debe estar en otro estado."
                )
            }
            return GroupCreationValidationResult(
                isValid = true,
                displayMessage = "✓ Extensión oficial de '${parent.name}' para el estado ${candidateState}.",
                parentGroup = parent
            )
        }

        return GroupCreationValidationResult(
            isValid = true,
            displayMessage = "✓ Nombre disponible para nuevo club único en ${candidateCountry.uppercase()}."
        )
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
    val badges: List<String>,
    val avatarUri: String? = null
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
    val validRides: Int,
    val memberClass: MemberClass = MemberClass.from(statusKind, isFounder)
)

data class ClubInvitationModel(
    val id: String,
    val groupId: String,
    val groupName: String,
    val countryCode: String,
    val invitedUserId: String,
    val statusKind: MemberStatusKind,
    val monthlyCostUsd: Double = 0.0,
    val state: String = "pending",
    val presidentName: String = "",
    val founderId: String = ""
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
    val openVotes: List<LeaderVoteModel> = emptyList(),
    val tier: ClubTier = ClubTier.MC,
    val stateRegion: String = "Miranda",
    val foundationDate: String = "2018-05-15",
    val city: String? = null,
    val logoCustomUri: String? = null,
    val sponsorGroupId: String? = null,
    val sponsorGroupName: String? = null,
    val sponsoredClubsCount: Int = 0,
    val isExtension: Boolean = false,
    val parentGroupId: String? = null,
    val parentGroupName: String? = null,
    val colorsDescription: String = "Negro y Oro",
    val clubSiglas: String = "",
    val traditionType: String = "Tradición MC Tradicional",
    val directivaScope: String = "Mesa Directiva Nacional",
    val directivaRegion: String = "Venezuela",
    val motto: String = "Hermandad, Respeto y Carretera"
) {
    /**
     * Sistema de 8 Estrellas Biker:
     * - Las primeras 7 estrellas representan pasos de apadrinamiento para ser miembro.
     * - La estrella 8 la otorga el sistema cuando alcanza 8 o más apadrinamientos,
     *   otorgándole a su Presidente un asiento en la Mesa Directiva Nacional del País.
     */
    val godparentStars: Int get() = when {
        sponsoredClubsCount >= 8 -> 8
        else -> sponsoredClubsCount.coerceIn(0, 7)
    }

    val hasSystemEighthStar: Boolean get() = sponsoredClubsCount >= 8

    /**
     * Regla Biker Nacional: Los clubes con 8 estrellas conforman
     * la Mesa Directiva del País (Consejo de Presidentes) para respaldar a sus apadrinados.
     */
    val isNationalCouncil: Boolean get() = sponsoredClubsCount >= 8

    val councilMemberStep: String
        get() = if (hasSystemEighthStar) {
            "🌟 8va Estrella Otorgada por el Sistema · Miembro del Consejo Nacional"
        } else {
            "⭐ $godparentStars/7 pasos hacia el Consejo (Faltan ${7 - godparentStars} para la 8va estrella)"
        }

    /**
     * Indica si el club cuenta con respaldo de un club padrino.
     */
    val hasSponsor: Boolean get() = !sponsorGroupId.isNullOrBlank()

    /**
     * Indica si es un club autónomo e independiente (sin padrino, sin extensión y sin apadrinados).
     * El sistema le da 100% de soporte, libertad para rodar y crear eventos.
     */
    val isIndependent: Boolean get() = !hasSponsor && !isExtension && sponsoredClubsCount == 0

    /**
     * Antigüedad calculada en años desde foundationDate.
     */
    val foundationYear: Int
        get() = foundationDate.split("-").firstOrNull()?.toIntOrNull() ?: 2020

    val yearsOfHeritage: Int
        get() = (2026 - foundationYear).coerceAtLeast(0)
}

/**
 * Solicitud formal de apadrinamiento enviada por un club a otro dentro del país.
 */
data class SponsorshipRequestModel(
    val id: String,
    val requestingClubId: String,
    val requestingClubName: String,
    val requestingClubTier: ClubTier,
    val requestingClubCountry: String,
    val requestingClubState: String,
    val requestingPresidentName: String,
    val requestingFoundationDate: String,
    val targetSponsorClubId: String,
    val targetSponsorClubName: String,
    val requestedAtDate: String,
    val message: String = "Solicitamos formalmente el respaldo y padrinazgo oficial de su club para integrarnos al linaje biker de respeto.",
    val status: String = "PENDING" // PENDING, ACCEPTED, REJECTED
)

/**
 * Tipos de infracciones por usurpación de identidad, logo o nombre.
 */
enum class UsurpationInfringement(val title: String, val badgeColorHex: Long) {
    NAME_DUPLICATION("Nombre Duplicado / Confusión Intencional", 0xFFE53935),
    UNAUTHORIZED_LOGO("Uso No Autorizado de Logo / Parche / Escudo", 0xFFFB8C00),
    UNAUTHORIZED_EXTENSION("Extensión Ilegítima / Capítulo No Autorizado", 0xFFD32F2F),
    DIRECTIVA_IMPERSONATION("Suplantación de Directiva o Miembros", 0xFFC62828)
}

/**
 * Reporte formal de usurpación elevado ante la Mesa Directiva Nacional (Consejo de Presidentes).
 */
data class UsurpationReport(
    val id: String,
    val reportingClubId: String,
    val reportingClubName: String,
    val reportingPresidentName: String,
    val reportedTargetName: String,
    val reportedClubId: String? = null,
    val countryCode: String,
    val stateRegion: String,
    val infringement: UsurpationInfringement,
    val description: String,
    val evidenceNotes: String,
    val reportedAtDate: String,
    val status: String = "EN_REVISION" // EN_REVISION, AUDIENCIA_CONVOCADA, SANCIONADO, DESESTIMADO
) {
    val statusLabel: String
        get() = when (status) {
            "EN_REVISION" -> "⚖️ En Revisión por Mesa Nacional"
            "AUDIENCIA_CONVOCADA" -> "🏛️ Audiencia Biker Convocada"
            "SANCIONADO" -> "🚫 Sancionado / Bloqueado"
            else -> "Desestimado"
        }
}

/**
 * Presidente integrante de la Mesa Directiva Nacional (Consejo Colegiado del País).
 */
data class NationalCouncilPresident(
    val presidentName: String,
    val presidentUserId: String,
    val clubId: String,
    val clubName: String,
    val clubTier: ClubTier,
    val stateRegion: String,
    val countryCode: String,
    val heritageYears: Int,
    val sponsoredClubsCount: Int,
    val godchildrenNames: List<String>
)

data class CountryClubStats(
    val countryCode: String,
    val countryName: String,
    val flagEmoji: String,
    val totalClubs: Int,
    val councilClubsCount: Int,
    val sponsorClubsCount: Int,
    val sponsoredClubsCount: Int,
    val independentClubsCount: Int,
    val oldestFoundationYear: Int?
)

object BikerCountryDirectory {
    val countryNames = mapOf(
        "VE" to "Venezuela",
        "MX" to "México",
        "CO" to "Colombia",
        "AR" to "Argentina",
        "ES" to "España",
        "US" to "Estados Unidos",
        "CL" to "Chile",
        "PE" to "Perú",
        "EC" to "Ecuador"
    )

    val countryEmojis = mapOf(
        "VE" to "🇻🇪",
        "MX" to "🇲🇽",
        "CO" to "🇨🇴",
        "AR" to "🇦🇷",
        "ES" to "🇪🇸",
        "US" to "🇺🇸",
        "CL" to "🇨🇱",
        "PE" to "🇵🇪",
        "EC" to "🇪🇨"
    )

    fun getCountryName(code: String): String = countryNames[code.uppercase()] ?: code.uppercase()
    fun getCountryEmoji(code: String): String = countryEmojis[code.uppercase()] ?: "🌐"

    fun computeCountryStats(groups: List<ClubGroupModel>): List<CountryClubStats> {
        val grouped = groups.groupBy { it.countryCode.uppercase() }
        return grouped.map { (countryCode, clubList) ->
            val councilCount = clubList.count { it.isNationalCouncil }
            val sponsorCount = clubList.count { it.sponsoredClubsCount > 0 }
            val sponsoredCount = clubList.count { it.hasSponsor }
            val independentCount = clubList.count { it.isIndependent }
            val oldest = clubList.map { it.foundationYear }.minOrNull()

            CountryClubStats(
                countryCode = countryCode,
                countryName = getCountryName(countryCode),
                flagEmoji = getCountryEmoji(countryCode),
                totalClubs = clubList.size,
                councilClubsCount = councilCount,
                sponsorClubsCount = sponsorCount,
                sponsoredClubsCount = sponsoredCount,
                independentClubsCount = independentCount,
                oldestFoundationYear = oldest
            )
        }.sortedByDescending { it.totalClubs }
    }
}

/**
 * Modelo de Consentimiento Granular conforme a RULES R15.
 */
data class UserConsentItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val groupId: String? = null,
    val kind: String, // 'membership', 'location', 'emergency_package', 'notifications', 'terms'
    val granted: Boolean,
    val policyVersion: String = "1.0",
    val createdAt: String = "",
    val revokedAt: String? = null
)

/**
 * Resumen de Consentimientos activos para UI interactiva (Fase 7).
 */
data class UserConsentsSummary(
    val locationGranted: Boolean = true,
    val emergencyPackageGranted: Boolean = true,
    val notificationsGranted: Boolean = true,
    val items: List<UserConsentItem> = emptyList()
)

/**
 * Modelo de Exportación de Datos RGPD (RULES R15.5).
 */
data class UserExportData(
    val exportedAt: String,
    val gdprCompliance: String,
    val userProfile: Map<String, Any?>,
    val memberships: List<Map<String, Any?>>,
    val consents: List<Map<String, Any?>>,
    val subscriptions: List<Map<String, Any?>>,
    val rides: List<Map<String, Any?>>,
    val badges: List<Map<String, Any?>>,
    val rawJson: String = ""
)

/**
 * Modelo de Reporte de Moderación (RULES R13).
 */
data class ModerationReportItem(
    val id: String,
    val reporterId: String,
    val reportedId: String,
    val groupId: String,
    val rideId: String? = null,
    val reason: String, // 'abuse', 'harassment', 'dangerous', 'other'
    val state: String = "open", // 'open', 'reviewing', 'actioned', 'dismissed', 'escalated'
    val resolution: String? = null, // 'warning', 'muted', 'ride_removed', 'group_removed', 'platform_suspended'
    val decidedBy: List<String> = emptyList(),
    val reporterName: String = "Rider",
    val reportedName: String = "Rider Reportado",
    val createdAt: String = ""
)

/**
 * Modelo de Votación Sensible de Mesa Directiva (RULES R14.2).
 */
data class LeaderVoteItem(
    val id: String,
    val groupId: String,
    val proposal: String, // 'remove_member', 'remove_leader', 'declare_emergency', 'change_identity'
    val targetId: String? = null,
    val proposedBy: String,
    val state: String = "open", // 'open', 'approved', 'rejected', 'expired'
    val expiresAt: String = "",
    val proposedByName: String = "Líder",
    val targetName: String? = null,
    val ballots: List<LeaderVoteBallotItem> = emptyList()
)

data class LeaderVoteBallotItem(
    val leaderId: String,
    val leaderName: String,
    val approve: Boolean
)
