package com.rutabikersync.app.feature.groups

import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Coordinador de Cargos Personalizados y Decisiones Secretas de Mi Club (Fase 2).
 */
class ClubDecisionsCoordinator(
    private val apiClient: ClubApiClient = ClubApiClient.getInstance()
) {
    private val _customRoles = MutableStateFlow<List<CustomClubRole>>(emptyList())
    val customRoles: StateFlow<List<CustomClubRole>> = _customRoles.asStateFlow()

    private val _assignedCustomRoleByUserId = MutableStateFlow<Map<String, String>>(emptyMap())
    val assignedCustomRoleByUserId: StateFlow<Map<String, String>> = _assignedCustomRoleByUserId.asStateFlow()

    private val _decisions = MutableStateFlow<List<ClubDecision>>(emptyList())
    val decisions: StateFlow<List<ClubDecision>> = _decisions.asStateFlow()

    fun isUserEligibleForDecision(
        userId: String,
        group: ClubGroupModel,
        allowedAudiences: Set<DecisionAudience>
    ): Boolean {
        val member = group.members.firstOrNull { it.userId == userId }
        val isLeader = group.principalLeaderId == userId ||
            group.leaderIds.contains(userId) ||
            member?.isLeader == true
        val isFounder = group.principalLeaderId == userId || member?.isFounder == true
        val status = member?.statusKind

        return (isLeader && DecisionAudience.DIRECTIVA in allowedAudiences) ||
            (isFounder && DecisionAudience.FOUNDERS in allowedAudiences) ||
            (status == MemberStatusKind.FULL && DecisionAudience.FULL_MEMBERS in allowedAudiences) ||
            (status == MemberStatusKind.PROSPECT && DecisionAudience.PROSPECTS in allowedAudiences)
    }

    private fun apiError(res: ClubApiClient.ApiResponse): IllegalStateException {
        val msg = res.body?.get("message")?.jsonPrimitive?.contentOrNull
            ?: res.body?.get("error")?.jsonPrimitive?.contentOrNull
            ?: "HTTP ${res.statusCode}"
        return IllegalStateException(msg)
    }

    suspend fun refreshCustomRoles(groupId: String): Result<List<CustomClubRole>> {
        val res = apiClient.getCustomRoles(groupId).getOrElse { return Result.failure(it) }
        if (!res.isSuccess || res.body == null) return Result.failure(apiError(res))
        val rawRoles = res.body["roles"] as? JsonArray ?: JsonArray(emptyList())
        val parsed = rawRoles.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            parseCustomRole(obj)
        }
        _customRoles.value = parsed
        return Result.success(parsed)
    }

    suspend fun createCustomRole(
        groupId: String,
        title: String,
        badgeIcon: String = "shield",
        canCreateRide: Boolean = false,
        canModerate: Boolean = false
    ): Result<CustomClubRole> {
        val cleanTitle = title.trim()
        if (cleanTitle.length < 2) {
            return Result.failure(IllegalArgumentException("El nombre del cargo debe tener al menos 2 caracteres"))
        }
        val res = apiClient.createCustomRole(groupId, cleanTitle, badgeIcon, canCreateRide, canModerate)
            .getOrElse { return Result.failure(it) }
        if (!res.isSuccess || res.body == null) return Result.failure(apiError(res))
        val role = parseCustomRole(res.body)
            ?: return Result.failure(IllegalStateException("Respuesta inválida del servidor"))
        _customRoles.value = _customRoles.value.filterNot { it.id == role.id } + role
        return Result.success(role)
    }

    suspend fun deleteCustomRole(groupId: String, roleId: String): Result<Unit> {
        val res = apiClient.deleteCustomRole(groupId, roleId).getOrElse { return Result.failure(it) }
        if (!res.isSuccess) return Result.failure(apiError(res))
        _customRoles.value = _customRoles.value.filterNot { it.id == roleId }
        _assignedCustomRoleByUserId.value = _assignedCustomRoleByUserId.value.filterValues { it != roleId }
        return Result.success(Unit)
    }

    suspend fun assignCustomRole(
        groupId: String,
        member: ClubMemberModel,
        customRoleId: String?
    ): Result<Unit> {
        // Regla P7: invitados (guest) nunca reciben cargos del club
        if (customRoleId != null && member.statusKind == MemberStatusKind.GUEST) {
            return Result.failure(IllegalStateException("GUEST_CANNOT_HOLD_ROLE"))
        }
        val res = apiClient.assignCustomRole(groupId, member.userId, customRoleId)
            .getOrElse { return Result.failure(it) }
        if (!res.isSuccess) return Result.failure(apiError(res))
        _assignedCustomRoleByUserId.value = if (customRoleId == null) {
            _assignedCustomRoleByUserId.value - member.userId
        } else {
            _assignedCustomRoleByUserId.value + (member.userId to customRoleId)
        }
        return Result.success(Unit)
    }

    suspend fun refreshDecisions(groupId: String): Result<List<ClubDecision>> {
        val res = apiClient.getDecisions(groupId).getOrElse { return Result.failure(it) }
        if (!res.isSuccess || res.body == null) return Result.failure(apiError(res))
        val raw = res.body["decisions"] as? JsonArray ?: JsonArray(emptyList())
        val parsed = raw.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            parseDecision(obj)
        }
        _decisions.value = parsed
        return Result.success(parsed)
    }

    suspend fun createDecision(
        groupId: String,
        title: String,
        description: String,
        category: DecisionCategory,
        options: List<String>,
        allowedAudiences: Set<DecisionAudience>,
        minQuorumPercent: Int = 0,
        durationHours: Int = 48
    ): Result<ClubDecision> {
        if (title.trim().length < 3) {
            return Result.failure(IllegalArgumentException("El título debe tener al menos 3 caracteres"))
        }
        if (allowedAudiences.isEmpty()) {
            return Result.failure(IllegalArgumentException("Selecciona al menos una audiencia para votar"))
        }
        val cleanOptions = options.map { it.trim() }.filter { it.isNotEmpty() }.take(4)
        if (cleanOptions.size < 2) {
            return Result.failure(IllegalArgumentException("Define al menos 2 opciones de votación"))
        }

        val res = apiClient.createDecision(
            groupId = groupId,
            title = title.trim(),
            description = description.trim(),
            category = category.apiValue,
            options = cleanOptions,
            allowedAudiences = allowedAudiences.map { it.apiValue },
            minQuorumPercent = minQuorumPercent.coerceIn(0, 100),
            durationHours = durationHours.coerceIn(1, 168)
        ).getOrElse { return Result.failure(it) }

        if (!res.isSuccess || res.body == null) return Result.failure(apiError(res))
        val created = parseDecision(res.body)
            ?: return Result.failure(IllegalStateException("Respuesta inválida al crear decisión"))
        _decisions.value = listOf(created) + _decisions.value.filterNot { it.id == created.id }
        return Result.success(created)
    }

    suspend fun castSecretVote(
        groupId: String,
        decisionId: String,
        optionIndex: Int
    ): Result<Unit> {
        val res = apiClient.castSecretDecisionVote(decisionId, optionIndex)
            .getOrElse { return Result.failure(it) }
        if (!res.isSuccess) return Result.failure(apiError(res))
        refreshDecisions(groupId)
        return Result.success(Unit)
    }

    suspend fun closeDecision(groupId: String, decisionId: String): Result<Unit> {
        val res = apiClient.closeDecision(decisionId).getOrElse { return Result.failure(it) }
        if (!res.isSuccess) return Result.failure(apiError(res))
        refreshDecisions(groupId)
        return Result.success(Unit)
    }

    private fun parseCustomRole(obj: JsonObject): CustomClubRole? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val groupId = obj["groupId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        return CustomClubRole(
            id = id,
            groupId = groupId,
            title = title,
            badgeIcon = obj["badgeIcon"]?.jsonPrimitive?.contentOrNull ?: "shield",
            canCreateRide = obj["canCreateRide"]?.jsonPrimitive?.booleanOrNull ?: false,
            canModerate = obj["canModerate"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    private fun parseDecision(obj: JsonObject): ClubDecision? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val groupId = obj["groupId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val options = (obj["options"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        val audiences = (obj["allowedAudiences"] as? JsonArray)
            ?.mapNotNull { DecisionAudience.fromApi(it.jsonPrimitive.contentOrNull) }
            ?.toSet()
            ?: emptySet()
        val tallies = (obj["tallies"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.intOrNull }

        return ClubDecision(
            id = id,
            groupId = groupId,
            title = title,
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            category = DecisionCategory.fromApi(obj["category"]?.jsonPrimitive?.contentOrNull),
            options = options,
            allowedAudiences = audiences,
            minQuorumPercent = obj["minQuorumPercent"]?.jsonPrimitive?.intOrNull ?: 0,
            state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "open",
            hasVoted = obj["hasVoted"]?.jsonPrimitive?.booleanOrNull ?: false,
            totalVotes = obj["totalVotes"]?.jsonPrimitive?.intOrNull ?: 0,
            eligibleCount = obj["eligibleCount"]?.jsonPrimitive?.intOrNull ?: 0,
            quorumMet = obj["quorumMet"]?.jsonPrimitive?.booleanOrNull ?: true,
            tallies = tallies,
            winningOptionIndex = obj["winningOptionIndex"]?.jsonPrimitive?.intOrNull,
            closesAt = obj["closesAt"]?.jsonPrimitive?.contentOrNull ?: "",
            closedAt = obj["closedAt"]?.jsonPrimitive?.contentOrNull
        )
    }
}
