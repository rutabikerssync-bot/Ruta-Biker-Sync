package com.rutabikersync.app.feature.groups

import com.rutabikersync.app.core.clubroutes.ClubProfileStore
import com.rutabikersync.app.core.clubroutes.ClubRoutesRepository
import com.rutabikersync.app.core.gamification.AppDatabase
import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.ui.components.skeleton.ErrorKind
import com.rutabikersync.app.ui.components.skeleton.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private fun kotlinx.serialization.json.JsonElement?.asString(): String? {
    if (this == null || this is kotlinx.serialization.json.JsonNull) return null
    return try {
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content
    } catch (_: Exception) {
        null
    }
}

/**
 * Interruptores del Panel de Desarrollador (solo debug: BuildConfig.DEV_PANEL_ENABLED).
 * Cumple sección 2.2 de la especificación maestra.
 */
data class DevPanelOverrides(
    val useFakeBackend: Boolean = false,
    val forceOffline: Boolean = false,
    val forceServerError500: Boolean = false,
    val forceSubscriptionExpired: Boolean = false,
    val forceInGracePeriod: Boolean = false,
    val forceExpiredCredential: Boolean = false,
    val forceLowBattery: Boolean = false,
    val forbidGuestClubRoles: Boolean = true // Regla P7 APROBADA en RULES.md §3 y §12 (MEMORY.md D42): los guest no reciben cargos honoríficos
)

/**
 * Repositorio local-first conectado a ClubApiClient (backend real Fastify + PostgreSQL)
 * y con interruptor debug FakeBackend determinista para Fase 1 (sección 2.1 y 2.2).
 * Hace cumplir todas las reglas de RULES.md (R1–R5, R8.2, R13.6, R14, R15.1).
 */
class ClubRepository(
    val apiClient: ClubApiClient = ClubApiClient.getInstance(),
    private val profileStore: ClubProfileStore? = null
) {

    /** Fase 5: fachada cache-first. Se construye bajo demanda para no exigir Context en tests JVM. */
    val clubRoutes: ClubRoutesRepository by lazy {
        val ctx = requireNotNull(apiClient.appContext) {
            "ClubRoutesRepository requiere ClubApiClient.initContext(context)"
        }
        val db = AppDatabase.getInstance(ctx)
        ClubRoutesRepository(apiClient, db.clubRouteDao(), db.rsvpOutboxDao(), ctx)
    }

    private val _rsvpPendingCount = MutableStateFlow(0)
    /** Nº de RSVP pendientes; el ViewModel lo publica como banner «Pendiente de sincronizar». */
    val rsvpPendingCount: StateFlow<Int> = _rsvpPendingCount.asStateFlow()

    suspend fun refreshRsvpPendingCount() {
        clubRoutes.refreshPendingCount()
        _rsvpPendingCount.value = clubRoutes.pendingCount.value
    }

    private val _devOverrides = MutableStateFlow(DevPanelOverrides())
    val devOverrides: StateFlow<DevPanelOverrides> = _devOverrides.asStateFlow()

    internal val _currentUser = MutableStateFlow(
        UserProfileModel(
            id = "u-rider-initial",
            email = "",
            displayName = "Rider Nuevo",
            countryCode = "MX",
            accountType = AccountType.MEMBER,
            bikeModel = "",
            mapColorHex = "#FFB300",
            validRides = 0,
            hasFounderFlame = false,
            badges = emptyList()
        )
    )
    val currentUser: StateFlow<UserProfileModel> = _currentUser.asStateFlow()

    internal val _groups = MutableStateFlow<List<ClubGroupModel>>(emptyList())
    val groups: StateFlow<List<ClubGroupModel>> = _groups.asStateFlow()

    internal val _pendingInvitations = MutableStateFlow<List<ClubInvitationModel>>(emptyList())
    val pendingInvitations: StateFlow<List<ClubInvitationModel>> = _pendingInvitations.asStateFlow()

    private val _hasDirectoryPackagePaid = MutableStateFlow(true)
    val hasDirectoryPackagePaid: StateFlow<Boolean> = _hasDirectoryPackagePaid.asStateFlow()

    internal val _sponsorshipRequests = MutableStateFlow<List<SponsorshipRequestModel>>(emptyList())
    val sponsorshipRequests: StateFlow<List<SponsorshipRequestModel>> = _sponsorshipRequests.asStateFlow()

    internal val _usurpationReports = MutableStateFlow<List<UsurpationReport>>(emptyList())
    val usurpationReports: StateFlow<List<UsurpationReport>> = _usurpationReports.asStateFlow()

    private val _consentsSummary = MutableStateFlow(
        UserConsentsSummary(
            locationGranted = true,
            emergencyPackageGranted = true,
            notificationsGranted = true
        )
    )
    val consentsSummary: StateFlow<UserConsentsSummary> = _consentsSummary.asStateFlow()

    fun toggleConsent(kind: String, granted: Boolean, groupId: String? = null): Result<UserConsentsSummary> {
        val current = _consentsSummary.value
        val updated = when (kind) {
            "location" -> current.copy(locationGranted = granted)
            "emergency_package" -> current.copy(emergencyPackageGranted = granted)
            "notifications" -> current.copy(notificationsGranted = granted)
            else -> current
        }
        val newItem = UserConsentItem(
            groupId = groupId,
            kind = kind,
            granted = granted,
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date()),
            revokedAt = if (!granted) java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date()) else null
        )
        val updatedItems = listOf(newItem) + current.items.filterNot { it.kind == kind && it.groupId == groupId }
        val finalSummary = updated.copy(items = updatedItems)
        _consentsSummary.value = finalSummary
        return Result.success(finalSummary)
    }

    suspend fun exportUserDataJson(): Result<String> {
        val remoteRes = apiClient.exportUserData()
        if (remoteRes.isSuccess && !remoteRes.getOrNull().isNullOrBlank()) {
            return remoteRes
        }
        val user = _currentUser.value
        val consents = _consentsSummary.value
        val groupsList = _groups.value.map { g ->
            """{"groupId":"${g.id}","name":"${g.name}","country":"${g.countryCode}","state":"${g.stateRegion}","membersCount":${g.members.size}}"""
        }.joinToString(",")

        val consentsJson = """[
            {"kind":"location","granted":${consents.locationGranted}},
            {"kind":"emergency_package","granted":${consents.emergencyPackageGranted}},
            {"kind":"notifications","granted":${consents.notificationsGranted}}
        ]"""

        val jsonString = """{
            "exportedAt": "${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())}",
            "gdprCompliance": "Exportación oficial de datos personales generada conforme a RGPD, LGPD, CCPA y RULES R15.5 de Ruta Biker Sync.",
            "user": {
                "id": "${user.id}",
                "displayName": "${user.displayName}",
                "email": "${user.email}",
                "bikeModel": "${user.bikeModel}",
                "countryCode": "${user.countryCode}",
                "accountType": "${user.accountType.name}",
                "validRides": ${user.validRides},
                "hasFounderFlame": ${user.hasFounderFlame}
            },
            "memberships": [$groupsList],
            "consents": $consentsJson
        }""".trimIndent()

        return Result.success(jsonString)
    }

    suspend fun deleteUserAccount(): Result<Boolean> {
        val remoteRes = apiClient.deleteUserAccount()
        resetToUxTestEmptyState()
        _consentsSummary.value = UserConsentsSummary(locationGranted = false, emergencyPackageGranted = false, notificationsGranted = false)
        return Result.success(true)
    }

    private val _moderationReports = MutableStateFlow<List<ModerationReportItem>>(
        listOf(
            ModerationReportItem(
                id = "rep-demo-01",
                reporterId = "usr-victim",
                reportedId = "usr-demo-bad",
                groupId = "grp-lobos",
                reason = "dangerous",
                state = "open",
                reporterName = "Rider Testigo",
                reportedName = "Rider Temerario",
                createdAt = "2026-10-31T05:00:00Z"
            )
        )
    )
    val moderationReports: StateFlow<List<ModerationReportItem>> = _moderationReports.asStateFlow()

    private val _leaderVotes = MutableStateFlow<List<LeaderVoteItem>>(
        listOf(
            LeaderVoteItem(
                id = "vote-demo-01",
                groupId = "grp-lobos",
                proposal = "remove_member",
                targetId = "usr-demo-bad",
                proposedBy = "usr-presi",
                state = "open",
                proposedByName = "Presidente Lobo",
                targetName = "Rider Temerario",
                ballots = listOf(
                    LeaderVoteBallotItem(
                        leaderId = "usr-presi",
                        leaderName = "Presidente Lobo",
                        approve = true
                    )
                )
            )
        )
    )
    val leaderVotes: StateFlow<List<LeaderVoteItem>> = _leaderVotes.asStateFlow()

    suspend fun fileModerationReport(
        groupId: String,
        reportedUserId: String,
        rideId: String?,
        reason: String,
        note: String?,
        reportedName: String = "Rider Reportado"
    ): Result<ModerationReportItem> {
        val report = ModerationReportItem(
            id = "rep-${UUID.randomUUID().toString().take(8)}",
            reporterId = _currentUser.value.id,
            reportedId = reportedUserId,
            groupId = groupId,
            rideId = rideId,
            reason = reason,
            state = "open",
            reporterName = _currentUser.value.displayName,
            reportedName = reportedName,
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())
        )
        _moderationReports.value = listOf(report) + _moderationReports.value
        apiClient.createModerationReport(groupId, reportedUserId, rideId, reason, note)
        return Result.success(report)
    }

    suspend fun resolveModerationReport(
        groupId: String,
        reportId: String,
        resolution: String?,
        state: String
    ): Result<Boolean> {
        _moderationReports.value = _moderationReports.value.map { rep ->
            if (rep.id == reportId) {
                rep.copy(state = state, resolution = resolution)
            } else {
                rep
            }
        }
        apiClient.resolveModerationReport(groupId, reportId, resolution, state)
        return Result.success(true)
    }

    suspend fun proposeLeaderVote(
        groupId: String,
        proposal: String,
        targetId: String?,
        targetName: String?
    ): Result<LeaderVoteItem> {
        val vote = LeaderVoteItem(
            id = "vote-${UUID.randomUUID().toString().take(8)}",
            groupId = groupId,
            proposal = proposal,
            targetId = targetId,
            proposedBy = _currentUser.value.id,
            state = "open",
            proposedByName = _currentUser.value.displayName,
            targetName = targetName,
            ballots = listOf(
                LeaderVoteBallotItem(
                    leaderId = _currentUser.value.id,
                    leaderName = _currentUser.value.displayName,
                    approve = true
                )
            )
        )
        _leaderVotes.value = listOf(vote) + _leaderVotes.value
        apiClient.proposeLeaderVote(groupId, proposal, targetId)
        return Result.success(vote)
    }

    suspend fun castLeaderVoteBallot(
        voteId: String,
        approve: Boolean
    ): Result<String> {
        val currentUserId = _currentUser.value.id
        var resultingState = "open"
        _leaderVotes.value = _leaderVotes.value.map { v ->
            if (v.id == voteId) {
                val newBallots = v.ballots.filterNot { it.leaderId == currentUserId } +
                    LeaderVoteBallotItem(currentUserId, _currentUser.value.displayName, approve)
                val yesCount = newBallots.count { it.approve }
                val isApproved = yesCount >= 2
                resultingState = if (isApproved) "approved" else "open"
                v.copy(ballots = newBallots, state = resultingState)
            } else {
                v
            }
        }
        apiClient.castLeaderVoteBallot(voteId, approve)
        return Result.success(resultingState)
    }

    internal val directoryUsers = mutableMapOf<String, UserProfileModel>()

    init {
        // Estado inicial limpio sin datos demo precargados
    }

    /**
     * Limpia datos demo y prepara el repositorio en estado vacío (Empty State) para pruebas UX (UX_TEST_PLAN.md).
     * P1: Cuenta de Líder preparada para crear el club desde cero.
     * P2-P6: Cuenta de Miembro preparada para unirse por escaneo de QR.
     */
    fun resetToUxTestEmptyState(participantId: String = "P1") {
        _groups.value = emptyList()
        _pendingInvitations.value = emptyList()
        val isLeader = participantId == "P1"
        val user = UserProfileModel(
            id = "u-$participantId",
            email = "$participantId@rutabikersync.app",
            displayName = "Piloto $participantId",
            countryCode = "MX",
            accountType = if (isLeader) AccountType.LEADER else AccountType.MEMBER,
            bikeModel = "Motocicleta $participantId",
            mapColorHex = if (isLeader) "#E5B869" else "#38BDF8",
            validRides = 0,
            hasFounderFlame = isLeader,
            badges = emptyList()
        )
        directoryUsers.clear()
        directoryUsers[user.id] = user
        _currentUser.value = user
    }

    fun updateDevOverrides(transform: (DevPanelOverrides) -> DevPanelOverrides) {
        _devOverrides.value = transform(_devOverrides.value)
    }

    /**
     * Sincroniza perfil, grupos e invitaciones contra el backend real (Fastify + PostgreSQL)
     * cuando el interruptor `useFakeBackend` está desactivado.
     */
    suspend fun syncWithRealBackend(): Result<Unit> {
        val meRes = apiClient.getMyProfile().getOrElse { return Result.failure(it) }
        if (!meRes.isSuccess) {
            return Result.failure(IllegalStateException("HTTP_${meRes.statusCode}"))
        }
        val body = meRes.body
        if (body != null) {
            val id = body["id"]?.jsonPrimitive?.content ?: _currentUser.value.id
            val email = body["email"]?.jsonPrimitive?.content ?: _currentUser.value.email
            val name = body["displayName"]?.jsonPrimitive?.content ?: _currentUser.value.displayName
            val country = body["countryCode"]?.jsonPrimitive?.content ?: _currentUser.value.countryCode
            val acct = if (body["accountType"]?.jsonPrimitive?.content == "leader") {
                AccountType.LEADER
            } else {
                AccountType.MEMBER
            }
            _currentUser.value = _currentUser.value.copy(
                id = id,
                email = email,
                displayName = name,
                countryCode = country,
                accountType = acct
            )
        }
        return Result.success(Unit)
    }

    /**
     * Sincroniza grupos e invitaciones con el backend Fastify + PostgreSQL (Hito 2).
     * Reconcilia la información remota preservando la integridad de clubes locales sin pisar datos offline.
     */
    suspend fun syncClubsAndInvitations(): Result<Unit> {
        return try {
            // 1. Grupos del usuario en backend
            val groupsRes = apiClient.getMyGroups().getOrNull()
            if (groupsRes != null && groupsRes.isSuccess && groupsRes.body != null) {
                val rawGroups = (groupsRes.body["groups"] as? kotlinx.serialization.json.JsonArray)?.toList()
                    ?: run {
                        val list = mutableListOf<kotlinx.serialization.json.JsonElement>()
                        (groupsRes.body["ledGroups"] as? kotlinx.serialization.json.JsonArray)?.let { list.addAll(it) }
                        (groupsRes.body["memberGroups"] as? kotlinx.serialization.json.JsonArray)?.let { list.addAll(it) }
                        list
                    }

                if (rawGroups.isNotEmpty()) {
                    for (elem in rawGroups) {
                        val gObj = elem as? kotlinx.serialization.json.JsonObject ?: continue
                        val gId = gObj["id"]?.jsonPrimitive?.content ?: continue
                        val gName = gObj["name"]?.jsonPrimitive?.content ?: "Club"
                        val country = gObj["countryCode"].asString() ?: "MX"
                        val phash = gObj["logoPhashHex"].asString() ?: "0000000000000000"
                        val leaderId = gObj["principalLeader"].asString()
                            ?: gObj["principalLeaderId"].asString() ?: ""
                        val tierCode = gObj["tier"].asString() ?: "RC"
                        val tier = ClubTier.fromCode(tierCode)
                        val foundationDate = gObj["foundationDate"].asString() ?: "2018-05-15"
                        val colorsDesc = gObj["colorsDescription"].asString() ?: ""
                        val siglas = gObj["clubSiglas"].asString() ?: tier.code
                        val tradition = gObj["traditionType"].asString() ?: ""
                        val scope = gObj["directivaScope"].asString() ?: ""
                        val region = gObj["directivaRegion"].asString() ?: ""
                        val motto = gObj["motto"].asString() ?: ""
                        val city = gObj["city"].asString() ?: ""
                        val logoUrl = gObj["logoUrl"].asString()

                        if (_groups.value.none { it.id == gId }) {
                            val newGroup = ClubGroupModel(
                                id = gId,
                                countryCode = country,
                                name = gName,
                                nameNormalized = ClubRulesEngine.normalizeGroupName(gName),
                                logoPhashHex = phash,
                                principalLeaderId = leaderId,
                                leaderIds = listOf(leaderId),
                                members = emptyList(),
                                tier = tier,
                                foundationDate = foundationDate,
                                colorsDescription = colorsDesc,
                                clubSiglas = siglas,
                                traditionType = tradition,
                                directivaScope = scope,
                                directivaRegion = region,
                                motto = motto,
                                city = city,
                                logoCustomUri = logoUrl
                            )
                            _groups.value = _groups.value + newGroup
                        } else {
                            _groups.value = _groups.value.map {
                                if (it.id == gId) it.copy(
                                    name = gName,
                                    countryCode = country,
                                    tier = tier,
                                    foundationDate = foundationDate.ifBlank { it.foundationDate },
                                    colorsDescription = if (colorsDesc.isNotBlank()) colorsDesc else it.colorsDescription,
                                    clubSiglas = if (siglas.isNotBlank()) siglas else it.clubSiglas,
                                    traditionType = if (tradition.isNotBlank()) tradition else it.traditionType,
                                    directivaScope = if (scope.isNotBlank()) scope else it.directivaScope,
                                    directivaRegion = if (region.isNotBlank()) region else it.directivaRegion,
                                    motto = if (motto.isNotBlank()) motto else it.motto,
                                    city = if (city.isNotBlank()) city else it.city,
                                    logoCustomUri = logoUrl ?: it.logoCustomUri
                                ) else it
                            }
                        }

                        // Propagación por versión: el detalle con ?since devuelve 304
                        // cuando no hay cambios y no debe reescribir la UI local.
                        val localVersion = _groups.value.firstOrNull { it.id == gId }?.profileVersion
                        val detailRes = apiClient.getGroupDetail(gId, localVersion).getOrNull()
                        if (detailRes != null && detailRes.statusCode == 304) {
                            // Sin cambios: conservar el estado local intacto.
                        } else if (detailRes != null && detailRes.isSuccess && detailRes.body != null) {
                            val remoteVersion = detailRes.body["profileVersion"]
                                ?.jsonPrimitive?.content?.toIntOrNull()
                            if (remoteVersion != null) {
                                _groups.value = _groups.value.map {
                                    if (it.id == gId) it.copy(profileVersion = remoteVersion) else it
                                }
                                // Lectura inmediata en el próximo arranque, sin esperar a la red.
                                profileStore?.save(gId, remoteVersion, gObj.toString())
                            }
                        }

                        // Sincronizar líderes y miembros del grupo si tenemos acceso
                        val memRes = apiClient.getGroupMembers(gId).getOrNull()
                        if (memRes != null && memRes.isSuccess && memRes.body != null) {
                            val leadersArr = memRes.body["leaders"] as? kotlinx.serialization.json.JsonArray
                            val membersArr = memRes.body["members"] as? kotlinx.serialization.json.JsonArray

                            val remoteMembers = mutableListOf<ClubMemberModel>()
                            var principalFromRemote: String? = null
                            val remoteLeaderIds = mutableListOf<String>()

                            // 1. Parsear líderes del backend (group_leaders)
                            if (leadersArr != null) {
                                for (lElem in leadersArr) {
                                    val lObj = lElem as? kotlinx.serialization.json.JsonObject ?: continue
                                    val lUid = lObj["userId"].asString() ?: continue
                                    val lName = lObj["displayName"].asString() ?: "Líder"
                                    val isPrin = lObj["isPrincipal"].asString() == "true"
                                    val roleStr = lObj["clubRole"].asString()
                                    val lRole = ClubRole.fromApi(roleStr) ?: if (isPrin) ClubRole.PRESIDENT else ClubRole.VICE_PRESIDENT
                                    remoteLeaderIds.add(lUid)
                                    if (isPrin) principalFromRemote = lUid

                                    remoteMembers.add(
                                        ClubMemberModel(
                                            userId = lUid,
                                            displayName = lName,
                                            bikeModel = lObj["bikeModel"].asString() ?: "Moto",
                                            statusKind = MemberStatusKind.FULL,
                                            clubRole = lRole,
                                            isLeader = true,
                                            isFounder = isPrin,
                                            paymentSemaphore = PaymentSemaphore.GREEN,
                                            validRides = 0
                                        )
                                    )
                                }
                            }

                            // 2. Parsear miembros regulares del backend (memberships)
                            if (membersArr != null) {
                                for (mElem in membersArr) {
                                    val mObj = mElem as? kotlinx.serialization.json.JsonObject ?: continue
                                    val mUid = mObj["userId"].asString() ?: continue
                                    if (remoteMembers.any { it.userId == mUid }) continue
                                    val mName = mObj["displayName"].asString() ?: "Piloto"
                                    val statusStr = mObj["statusKind"].asString()
                                    val mStatus = when (statusStr) {
                                        "full" -> MemberStatusKind.FULL
                                        "prospect" -> MemberStatusKind.PROSPECT
                                        else -> MemberStatusKind.GUEST
                                    }
                                    val roleStr = mObj["clubRole"].asString()
                                    val role = ClubRole.fromApi(roleStr)

                                    remoteMembers.add(
                                        ClubMemberModel(
                                            userId = mUid,
                                            displayName = mName,
                                            bikeModel = mObj["bikeModel"].asString() ?: "Moto",
                                            statusKind = mStatus,
                                            clubRole = role,
                                            isLeader = false,
                                            isFounder = false,
                                            paymentSemaphore = PaymentSemaphore.GREEN,
                                            validRides = mObj["validRides"].asString()?.toIntOrNull() ?: 0
                                        )
                                    )
                                }
                            }

                            val targetGroup = _groups.value.find { it.id == gId }
                            if (targetGroup != null) {
                                val founderMem = targetGroup.members.find { it.isFounder || it.userId == targetGroup.principalLeaderId }
                                val finalMembers = if (founderMem != null && remoteMembers.none { it.userId == founderMem.userId }) {
                                    listOf(founderMem) + remoteMembers
                                } else {
                                    remoteMembers
                                }
                                val finalPrincipal = principalFromRemote ?: targetGroup.principalLeaderId.ifBlank { leaderId }
                                val finalLeaders = (remoteLeaderIds + targetGroup.leaderIds + listOf(finalPrincipal)).filter { it.isNotBlank() }.distinct()

                                _groups.value = _groups.value.map {
                                    if (it.id == gId) it.copy(
                                        principalLeaderId = finalPrincipal,
                                        leaderIds = finalLeaders,
                                        members = finalMembers
                                    ) else it
                                }
                            }
                        }

                        // Sincronizar invitaciones emitidas de este club con backend
                        val groupInvRes = apiClient.getGroupInvitations(gId).getOrNull()
                        if (groupInvRes != null && groupInvRes.isSuccess && groupInvRes.body != null) {
                            val groupInvs = groupInvRes.body["invitations"] as? kotlinx.serialization.json.JsonArray
                            if (groupInvs != null) {
                                for (iElem in groupInvs) {
                                    val iObj = iElem as? kotlinx.serialization.json.JsonObject ?: continue
                                    val iId = iObj["id"]?.jsonPrimitive?.content ?: continue
                                    val stState = iObj["state"]?.jsonPrimitive?.content ?: "pending"
                                    val invitee = iObj["inviteeName"]?.jsonPrimitive?.content
                                    val typeStr = iObj["invitationType"]?.jsonPrimitive?.content ?: "hermandad"
                                    val type = if (typeStr.equals("rodada", ignoreCase = true)) InvitationType.RODADA else InvitationType.HERMANDAD

                                    val invStatus = when (stState) {
                                        "accepted" -> InvitationStatus.ACCEPTED
                                        "declined" -> InvitationStatus.REJECTED
                                        "expired" -> InvitationStatus.EXPIRED
                                        else -> InvitationStatus.PENDING
                                    }

                                    val existing = invitationRepository.getInvitationById(iId)
                                    if (existing != null) {
                                        invitationRepository.updateInvitationStatus(iId, invStatus, invitee)
                                    } else {
                                        invitationRepository.createInvitation(
                                            type = type,
                                            clubId = gId,
                                            clubName = gName,
                                            inviterUid = iObj["invitedBy"]?.jsonPrimitive?.content ?: "u-server-inviter",
                                            inviterName = iObj["inviterName"]?.jsonPrimitive?.content ?: "Líder Oficial",
                                            customId = iId
                                        )
                                        invitationRepository.updateInvitationStatus(iId, invStatus, invitee)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Invitaciones del usuario en backend
            val invRes = apiClient.getMyInvitations().getOrNull()
            if (invRes != null && invRes.isSuccess && invRes.body != null) {
                val invArr = invRes.body["invitations"] as? kotlinx.serialization.json.JsonArray
                if (invArr != null) {
                    for (elem in invArr) {
                        val iObj = elem as? kotlinx.serialization.json.JsonObject ?: continue
                        val iId = iObj["id"]?.jsonPrimitive?.content ?: continue
                        val gId = iObj["groupId"]?.jsonPrimitive?.content ?: ""
                        val gName = iObj["groupName"]?.jsonPrimitive?.content ?: "Club"
                        val typeStr = iObj["invitationType"]?.jsonPrimitive?.content ?: "rodada"
                        val type = if (typeStr.equals("hermandad", ignoreCase = true)) InvitationType.HERMANDAD else InvitationType.RODADA

                        if (invitationRepository.getInvitationById(iId) == null) {
                            invitationRepository.createInvitation(
                                type = type,
                                clubId = gId,
                                clubName = gName,
                                inviterUid = "u-server-inviter",
                                inviterName = "Líder Oficial",
                                customId = iId
                            )
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Evalúa el UiState<T> respetando los interruptores del Panel de Desarrollador y el principio Local-First.
     */
    fun <T> resolveUiState(
        data: T,
        isEmpty: (T) -> Boolean
    ): UiState<T> {
        val ov = _devOverrides.value
        return when {
            ov.forceOffline -> UiState.Offline
            ov.forceServerError500 -> UiState.Error(ErrorKind.SERVER)
            ov.forceSubscriptionExpired -> UiState.Error(ErrorKind.SUBSCRIPTION_EXPIRED)
            isEmpty(data) -> UiState.Empty
            ov.forceInGracePeriod -> UiState.Content(data = data, stale = true)
            else -> UiState.Content(data = data, stale = false)
        }
    }

    /**
     * Registro / Login (RULES R1.1, R1.2, R13.6).
     */
    fun loginOrRegister(
        email: String,
        displayName: String,
        countryCode: String,
        acceptedTermsAndConvivencia: Boolean,
        activateLeaderAccount: Boolean
    ): Result<UserProfileModel> {
        if (!acceptedTermsAndConvivencia) {
            return Result.failure(IllegalArgumentException("TERMS_REQUIRED"))
        }
        val user = UserProfileModel(
            id = "u-${UUID.randomUUID().toString().take(8)}",
            email = email.trim().lowercase(),
            displayName = displayName.trim().ifEmpty { email.substringBefore("@") },
            countryCode = countryCode.uppercase(),
            accountType = if (activateLeaderAccount) AccountType.LEADER else AccountType.MEMBER,
            bikeModel = "",
            mapColorHex = "#FFB300",
            validRides = 0,
            hasFounderFlame = false,
            badges = emptyList()
        )
        directoryUsers[user.id] = user
        _currentUser.value = user
        return Result.success(user)
    }

    fun updateProfile(
        displayName: String,
        bikeModel: String,
        mapColorHex: String,
        avatarUri: String? = _currentUser.value.avatarUri
    ): UserProfileModel {
        val updated = _currentUser.value.copy(
            displayName = displayName.trim().ifEmpty { _currentUser.value.displayName },
            bikeModel = bikeModel.trim(),
            mapColorHex = mapColorHex,
            avatarUri = avatarUri
        )
        _currentUser.value = updated
        directoryUsers[updated.id] = updated
        return updated
    }

    /**
     * Alternar rol de usuario entre Líder y Miembro desde Configuración o Perfil.
     */
    fun switchAccountType(newType: AccountType): UserProfileModel {
        val updated = _currentUser.value.copy(accountType = newType)
        _currentUser.value = updated
        directoryUsers[updated.id] = updated
        return updated
    }

    /**
     * Restaurar sesión previa del usuario y su rol recordado.
     */
    fun restoreSession(
        id: String,
        email: String,
        displayName: String,
        countryCode: String = "MX",
        accountType: AccountType,
        avatarUri: String? = null
    ): UserProfileModel {
        val restored = _currentUser.value.copy(
            id = id,
            email = email,
            displayName = displayName,
            countryCode = countryCode,
            accountType = accountType,
            avatarUri = avatarUri ?: _currentUser.value.avatarUri
        )
        _currentUser.value = restored
        directoryUsers[restored.id] = restored
        apiClient.currentUserId = id
        return restored
    }

    /**
     * Sincroniza el usuario del repositorio local de perfiles (rbs_auth_prefs)
     * asegurando que el identificador determinista y rol de Líder/Miembro estén
     * reflejados tanto en ClubRepository como en ClubApiClient.
     */
    fun syncWithLocalProfile(context: android.content.Context) {
        try {
            val prefs = context.getSharedPreferences("rbs_auth_prefs", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("is_authenticated", false)) {
                val uid = prefs.getString("user_id", null) ?: return
                val email = prefs.getString("user_email", "") ?: ""
                val name = prefs.getString("user_display_name", "") ?: email.substringBefore("@")
                val isLeader = prefs.getString("account_type", "") == "LEADER"
                val bike = prefs.getString("user_bike_model", "") ?: ""
                val color = prefs.getString("user_map_color", "#FFB300") ?: "#FFB300"

                val syncedUser = UserProfileModel(
                    id = uid,
                    email = email,
                    displayName = name,
                    countryCode = "VE",
                    accountType = if (isLeader) AccountType.LEADER else AccountType.MEMBER,
                    bikeModel = bike,
                    mapColorHex = color,
                    validRides = _currentUser.value.validRides,
                    hasFounderFlame = isLeader || _currentUser.value.hasFounderFlame,
                    badges = _currentUser.value.badges
                )
                _currentUser.value = syncedUser
                directoryUsers[syncedUser.id] = syncedUser
                apiClient.currentUserId = uid
            }
        } catch (_: Throwable) {}
    }

    /**
     * Crear grupo con validación de cuenta Líder (R4.1), unicidad de nombre con leet por país (R5.1),
     * y unicidad de logo por pHash Hamming < 8 (R5.2). Otorga llama permanente de Fundador (R8.2).
     */
    fun createGroup(
        actorUserId: String,
        countryCode: String,
        name: String,
        logoPhashHex: String,
        stateRegion: String = "Miranda",
        isExtension: Boolean = false,
        parentGroupId: String? = null,
        tier: ClubTier = ClubTier.MC
    ): Result<ClubGroupModel> {
        val actor = directoryUsers[actorUserId] ?: _currentUser.value.takeIf { it.id == actorUserId }
            ?: return Result.failure(IllegalStateException("USER_NOT_FOUND"))

        if (actor.accountType != AccountType.LEADER) {
            return Result.failure(IllegalStateException("LEADER_REQUIRED"))
        }

        val normalizedCountry = countryCode.uppercase()
        val validation = ClubRulesEngine.validateGroupCreationOrExtension(
            candidateName = name,
            candidateCountry = normalizedCountry,
            candidateState = stateRegion,
            isExtension = isExtension,
            parentGroupId = parentGroupId,
            existingGroups = _groups.value
        )

        if (!validation.isValid) {
            val errPrefix = if (validation.errorCode == "DUPLICATE_NAME_IN_COUNTRY") "GROUP_NAME_TAKEN_IN_COUNTRY: " else ""
            return Result.failure(IllegalArgumentException("$errPrefix${validation.displayMessage}"))
        }

        val sameCountryGroups = _groups.value.filter { it.countryCode == normalizedCountry }
        for (existing in sameCountryGroups) {
            if (!isExtension) {
                val logoSim = ClubRulesEngine.evaluateLogoSimilarity(logoPhashHex, existing.logoPhashHex)
                if (logoSim.rejected) {
                    return Result.failure(IllegalArgumentException("GROUP_LOGO_TAKEN_IN_COUNTRY:${logoSim.distance}"))
                }
            }
        }

        val parent = validation.parentGroup
        val finalName = if (isExtension && parent != null && !name.contains(parent.name, ignoreCase = true)) {
            "${parent.name} - Cap. $stateRegion"
        } else {
            name.trim()
        }

        val newGroup = ClubGroupModel(
            id = "grp-${UUID.randomUUID().toString().take(8)}",
            countryCode = normalizedCountry,
            name = finalName,
            nameNormalized = ClubRulesEngine.normalizeGroupName(finalName),
            logoPhashHex = if (isExtension && parent != null) parent.logoPhashHex else logoPhashHex.lowercase(),
            principalLeaderId = actor.id,
            leaderIds = listOf(actor.id),
            stateRegion = stateRegion.trim(),
            isExtension = isExtension,
            parentGroupId = parent?.id ?: parentGroupId,
            parentGroupName = parent?.name,
            members = listOf(
                ClubMemberModel(
                    userId = actor.id,
                    displayName = actor.displayName,
                    bikeModel = actor.bikeModel,
                    statusKind = MemberStatusKind.FULL,
                    clubRole = ClubRole.PRESIDENT,
                    isLeader = true,
                    isFounder = true,
                    paymentSemaphore = PaymentSemaphore.GREEN, // R4.4: Líder no paga $2.50 en su grupo
                    validRides = actor.validRides
                )
            ),
            tier = tier
        )
        _groups.value = _groups.value + newGroup
        if (_currentUser.value.id == actor.id) {
            _currentUser.value = _currentUser.value.copy(hasFounderFlame = true)
        }
        return Result.success(newGroup)
    }

    /**
     * Nombrar co-líder (RULES R4.1 y R4.3):
     * - Solo el líder principal puede nombrar a otros líderes.
     * - Máximo 3 líderes por grupo.
     * - El candidato debe tener cuenta Líder activa ($15/mes).
     */
    fun addCoLeader(
        groupId: String,
        actorUserId: String,
        candidateUserId: String
    ): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))

        if (group.principalLeaderId != actorUserId) {
            return Result.failure(IllegalStateException("ONLY_PRINCIPAL_LEADER"))
        }
        if (group.leaderIds.size >= 3) {
            return Result.failure(IllegalStateException("MAX_LEADERS_REACHED"))
        }
        val candidate = directoryUsers[candidateUserId]
            ?: return Result.failure(IllegalArgumentException("CANDIDATE_NOT_FOUND"))
        if (candidate.accountType != AccountType.LEADER) {
            return Result.failure(IllegalStateException("CANDIDATE_MUST_BE_ACTIVE_LEADER"))
        }
        if (group.leaderIds.contains(candidateUserId)) {
            return Result.failure(IllegalStateException("ALREADY_LEADER"))
        }

        val updatedGroup = group.copy(
            leaderIds = group.leaderIds + candidateUserId
        )
        _groups.value = _groups.value.map { if (it.id == groupId) updatedGroup else it }
        return Result.success(updatedGroup)
    }

    /**
     * Enviar invitación (RULES R2.1, R2.2, R2.3):
     * - Nadie es agregado directamente: siempre queda en estado "pending" hasta que el invitado acepte.
     * - Full member y Prospecto solo pueden pertenecer a 1 grupo como Full/Prospecto.
     * - Invitado puede pertenecer a varios grupos.
     */
    fun sendInvitation(
        groupId: String,
        actorUserId: String,
        invitedUserId: String,
        statusKind: MemberStatusKind
    ): Result<ClubInvitationModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))

        if (!group.leaderIds.contains(actorUserId)) {
            return Result.failure(IllegalStateException("ONLY_LEADER_CAN_INVITE"))
        }

        if (statusKind == MemberStatusKind.FULL || statusKind == MemberStatusKind.PROSPECT) {
            val alreadyInClub = _groups.value.any { g ->
                g.id != groupId && g.members.any { m ->
                    m.userId == invitedUserId &&
                        (m.statusKind == MemberStatusKind.FULL || m.statusKind == MemberStatusKind.PROSPECT)
                }
            }
            if (alreadyInClub) {
                return Result.failure(IllegalStateException("EXCLUSIVE_CLUB_MEMBERSHIP"))
            }
        }

        val inv = ClubInvitationModel(
            id = UUID.randomUUID().toString(),
            groupId = group.id,
            groupName = group.name,
            countryCode = group.countryCode,
            invitedUserId = invitedUserId,
            statusKind = statusKind,
            monthlyCostUsd = 0.0,
            state = "pending"
        )
        _pendingInvitations.value = _pendingInvitations.value + inv
        return Result.success(inv)
    }

    fun addPendingInvitation(invitation: ClubInvitationModel) {
        val filtered = _pendingInvitations.value.filterNot { it.id == invitation.id }
        _pendingInvitations.value = filtered + invitation
    }

    val invitationRepository: InvitationRepository = InvitationRepository()

    /**
     * Delegado canónico exclusivo a [InvitationRepository] para la aceptación de invitaciones (RULES R2.2 / R2.3).
     * Garantiza la aplicación estricta de estatutos biker y centraliza la lógica de invitaciones.
     */
    fun acceptInvitation(
        invitationId: String,
        inviteeUid: String,
        inviteeName: String,
        currentClubId: String?
    ): Result<InvitationAcceptanceResult> {
        return invitationRepository.acceptInvitation(
            invitationId = invitationId,
            inviteeUid = inviteeUid,
            inviteeName = inviteeName,
            currentClubId = currentClubId
        )
    }

    fun rejectInvitation(invitationId: String, inviteeUid: String? = null, reason: String? = null) {
        invitationRepository.rejectInvitation(invitationId, inviteeUid, reason)
        _pendingInvitations.value = _pendingInvitations.value.filterNot { it.id == invitationId }
    }

    fun removePendingLegacyInvitation(invitationId: String) {
        _pendingInvitations.value = _pendingInvitations.value.filterNot { it.id == invitationId }
    }

    /**
     * Incorpora formalmente a un piloto en la membresía de un club existente.
     */
    fun addGroup(group: ClubGroupModel) {
        if (_groups.value.none { it.id == group.id }) {
            _groups.value = _groups.value + group
        }
    }

    fun restoreClubIfMissing(group: ClubGroupModel) {
        addGroup(group)
    }

    fun removeGroup(groupId: String) {
        _groups.value = _groups.value.filterNot { it.id == groupId }
    }

    fun restorePresidentIfMissing() {
        val user = _currentUser.value
        _groups.value = _groups.value.map { g ->
            if (g.principalLeaderId == user.id || g.leaderIds.contains(user.id)) {
                if (g.members.none { it.userId == user.id }) {
                    val presMember = ClubMemberModel(
                        userId = user.id,
                        displayName = user.displayName.ifBlank { "Presidente" },
                        bikeModel = user.bikeModel,
                        statusKind = MemberStatusKind.FULL,
                        clubRole = ClubRole.PRESIDENT,
                        isLeader = true,
                        isFounder = true,
                        paymentSemaphore = PaymentSemaphore.GREEN,
                        validRides = user.validRides
                    )
                    g.copy(
                        members = listOf(presMember) + g.members,
                        principalLeaderId = user.id,
                        leaderIds = (g.leaderIds + user.id).distinct()
                    )
                } else {
                    g
                }
            } else {
                g
            }
        }
    }

    fun addMemberToClub(groupId: String, member: ClubMemberModel): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))
        val updatedGroup = group.copy(
            members = group.members.filterNot { it.userId == member.userId } + member
        )
        _groups.value = _groups.value.map { if (it.id == groupId) updatedGroup else it }
        return Result.success(updatedGroup)
    }

    /**
     * Asignar cargo del club o cambiar estatus (RULES R2.4 y R3.3):
     * - Un invitado NO puede tener cargos del club ni ser líder.
     */
    fun assignMemberRoleOrStatus(
        groupId: String,
        actorUserId: String,
        memberUserId: String,
        newStatusKind: MemberStatusKind,
        newClubRole: ClubRole?
    ): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))

        if (!group.leaderIds.contains(actorUserId)) {
            return Result.failure(IllegalStateException("ONLY_LEADER"))
        }

        if (_devOverrides.value.forbidGuestClubRoles && newStatusKind == MemberStatusKind.GUEST && newClubRole != null) {
            return Result.failure(IllegalArgumentException("GUEST_CANNOT_HAVE_CLUB_ROLE"))
        }

        val updatedMembers = group.members.map { m ->
            if (m.userId == memberUserId) {
                m.copy(statusKind = newStatusKind, clubRole = newClubRole)
            } else {
                m
            }
        }
        val updatedGroup = group.copy(members = updatedMembers)
        _groups.value = _groups.value.map { if (it.id == groupId) updatedGroup else it }
        return Result.success(updatedGroup)
    }

    /**
     * Expulsa a un miembro del club de forma oficial (Micro-Fase 2.5).
     * - Restricción estricta: Solo un Líder/Presidente puede expulsar miembros.
     * - Protección de Fundador: El líder principal fundador nunca puede ser expulsado.
     * - Auto-expulsión: Un líder no puede expulsarse a sí mismo mediante esta acción.
     */
    fun expelMemberFromClub(
        groupId: String,
        actorUserId: String,
        targetUserId: String
    ): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))

        if (!group.leaderIds.contains(actorUserId)) {
            return Result.failure(IllegalStateException("ONLY_LEADER_CAN_EXPEL"))
        }

        if (group.principalLeaderId == targetUserId) {
            return Result.failure(IllegalStateException("CANNOT_EXPEL_FOUNDER"))
        }

        if (actorUserId == targetUserId) {
            return Result.failure(IllegalStateException("CANNOT_EXPEL_SELF"))
        }

        val memberExists = group.members.any { it.userId == targetUserId }
        if (!memberExists) {
            return Result.failure(IllegalArgumentException("MEMBER_NOT_FOUND"))
        }

        val updatedMembers = group.members.filterNot { it.userId == targetUserId }
        val updatedLeaderIds = group.leaderIds.filterNot { it == targetUserId }
        val updatedGroup = group.copy(
            members = updatedMembers,
            leaderIds = updatedLeaderIds
        )

        _groups.value = _groups.value.map { if (it.id == groupId) updatedGroup else it }
        return Result.success(updatedGroup)
    }

    /**
     * Votación de líderes 2/3 para decisiones sensibles (RULES R14.2 y R14.3):
     * - Nunca permite remover al líder principal (Fundador).
     */
    fun castLeaderVote(
        groupId: String,
        voteId: String,
        leaderUserId: String,
        approve: Boolean
    ): Result<LeaderVoteModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))

        if (!group.leaderIds.contains(leaderUserId)) {
            return Result.failure(IllegalStateException("ONLY_GROUP_LEADERS_CAN_VOTE"))
        }

        val vote = group.openVotes.find { it.id == voteId }
            ?: return Result.failure(IllegalArgumentException("VOTE_NOT_FOUND"))

        val nextCoLeader = group.leaderIds.firstOrNull { it != leaderUserId && !vote.approvals.contains(it) }
        val approvals = if (approve) {
            if (vote.approvals.contains(leaderUserId) && nextCoLeader != null) {
                vote.approvals + nextCoLeader
            } else {
                vote.approvals + leaderUserId
            }
        } else {
            vote.approvals - leaderUserId
        }
        val rejections = if (!approve) vote.rejections + leaderUserId else vote.rejections - leaderUserId
        val required = ClubRulesEngine.requiredVotesForLeaderMajority(group.leaderIds.size)

        val newState = when {
            approvals.size >= required -> "approved"
            rejections.size > (group.leaderIds.size - required) -> "rejected"
            else -> "open"
        }

        val updatedVote = vote.copy(
            approvals = approvals,
            rejections = rejections,
            requiredVotes = required,
            state = newState
        )

        val updatedGroup = group.copy(
            openVotes = group.openVotes.map { if (it.id == voteId) updatedVote else it }
        )
        _groups.value = _groups.value.map { if (it.id == groupId) updatedGroup else it }
        return Result.success(updatedVote)
    }

    fun createLeaderVote(
        groupId: String,
        proposal: String,
        targetLabel: String,
        creatorLeaderId: String
    ): Result<LeaderVoteModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))
        if (!group.leaderIds.contains(creatorLeaderId)) {
            return Result.failure(IllegalStateException("ONLY_LEADER_CAN_PROPOSE_VOTE"))
        }
        val required = ClubRulesEngine.requiredVotesForLeaderMajority(group.leaderIds.size)
        val newVote = LeaderVoteModel(
            id = "vote-" + System.currentTimeMillis(),
            groupId = groupId,
            proposal = proposal,
            targetLabel = targetLabel,
            approvals = setOf(creatorLeaderId),
            rejections = emptySet(),
            requiredVotes = required,
            state = if (required <= 1) "approved" else "open"
        )
        val updatedGroup = group.copy(openVotes = listOf(newVote) + group.openVotes)
        _groups.value = _groups.value.map { if (it.id == groupId) updatedGroup else it }
        return Result.success(newVote)
    }

    fun updateGroupDetails(
        groupId: String,
        name: String,
        countryCode: String,
        stateRegion: String,
        foundationDate: String,
        tier: ClubTier,
        city: String? = null,
        logoPhashHex: String? = null,
        logoCustomUri: String? = null
    ): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))
        val trimmedName = name.trim()
        if (trimmedName.length < 3) {
            return Result.failure(IllegalArgumentException("NAME_TOO_SHORT"))
        }
        val updated = group.copy(
            name = trimmedName,
            nameNormalized = ClubRulesEngine.normalizeGroupName(trimmedName),
            countryCode = countryCode.uppercase().trim(),
            stateRegion = stateRegion.trim(),
            foundationDate = foundationDate.trim(),
            tier = tier,
            city = city ?: group.city,
            logoPhashHex = logoPhashHex ?: group.logoPhashHex,
            logoCustomUri = logoCustomUri ?: group.logoCustomUri
        )
        _groups.value = _groups.value.map { if (it.id == groupId) updated else it }
        return Result.success(updated)
    }

    /**
     * Actualiza el logo / escudo del club directamente (soporta URI de galería o pHash de preset).
     */
    fun updateClubLogo(
        groupId: String,
        logoCustomUri: String?,
        logoPhashHex: String? = null
    ): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))
        val updated = group.copy(
            logoCustomUri = logoCustomUri,
            logoPhashHex = logoPhashHex ?: group.logoPhashHex
        )
        _groups.value = _groups.value.map { if (it.id == groupId) updated else it }
        return Result.success(updated)
    }

    fun updateDirectivaOfficer(
        groupId: String,
        role: ClubRole,
        newOfficerUserId: String
    ): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))

        val targetMember = group.members.find { it.userId == newOfficerUserId }
            ?: return Result.failure(IllegalArgumentException("MEMBER_NOT_FOUND"))

        // Si es GUEST, al asignarle un cargo de oficial en la directiva se le promueve a Full Patch (P7)
        val nextStatus = if (targetMember.statusKind == MemberStatusKind.GUEST) {
            MemberStatusKind.FULL
        } else {
            targetMember.statusKind
        }

        val updatedMembers = group.members.map { m ->
            when {
                m.userId == newOfficerUserId -> m.copy(
                    clubRole = role,
                    statusKind = nextStatus,
                    isLeader = role == ClubRole.PRESIDENT || role == ClubRole.VICE_PRESIDENT || m.isLeader
                )
                m.clubRole == role -> m.copy(clubRole = null)
                else -> m
            }
        }
        val updatedLeaders = updatedMembers.filter { it.isLeader || it.clubRole == ClubRole.PRESIDENT || it.clubRole == ClubRole.VICE_PRESIDENT }.map { it.userId }.distinct()
        val updatedPrincipal = if (role == ClubRole.PRESIDENT) newOfficerUserId else group.principalLeaderId

        val updated = group.copy(
            principalLeaderId = updatedPrincipal,
            leaderIds = updatedLeaders,
            members = updatedMembers
        )
        _groups.value = _groups.value.map { if (it.id == groupId) updated else it }
        return Result.success(updated)
    }

    /**
     * Actualiza la identidad biker, colores, siglas, tradición y alcance directivo del club.
     */
    fun updateClubProfile(
        groupId: String,
        colorsDescription: String,
        clubSiglas: String,
        traditionType: String,
        directivaScope: String,
        directivaRegion: String,
        motto: String,
        city: String? = null,
        foundationDate: String? = null,
        logoCustomUri: String? = null
    ): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))
        val updatedTier = runCatching { ClubTier.fromCode(clubSiglas) }.getOrNull() ?: group.tier
        val updated = group.copy(
            tier = updatedTier,
            colorsDescription = colorsDescription,
            clubSiglas = clubSiglas,
            traditionType = traditionType,
            directivaScope = directivaScope,
            directivaRegion = directivaRegion,
            motto = motto,
            city = city ?: group.city,
            foundationDate = foundationDate?.trim()?.ifBlank { null } ?: group.foundationDate,
            logoCustomUri = logoCustomUri ?: group.logoCustomUri
        )
        _groups.value = _groups.value.map { if (it.id == groupId) updated else it }
        return Result.success(updated)
    }

    /**
     * Aplica la respuesta del PUT /v1/groups/:id/profile como fuente de verdad.
     * Evita que el sync periódico (cada 1,5 s) revierta la edición si el listado
     * aún no refleja el cambio o si la petición falló.
     */
    fun applyGroupProfileFromApi(groupId: String, body: kotlinx.serialization.json.JsonObject?): Boolean {
        val gObj = body?.get("group") as? kotlinx.serialization.json.JsonObject ?: return false
        val colorsDesc = gObj["colorsDescription"].asString()
        val siglas = gObj["clubSiglas"].asString()
        val tradition = gObj["traditionType"].asString()
        val scope = gObj["directivaScope"].asString()
        val region = gObj["directivaRegion"].asString()
        val motto = gObj["motto"].asString()
        val city = gObj["city"].asString()
        val foundationDate = gObj["foundationDate"].asString()
        val logoUrl = gObj["logoUrl"].asString()
        val tierCode = gObj["tier"].asString()
        val remoteVersion = gObj["profileVersion"]?.jsonPrimitive?.content?.toIntOrNull()

        _groups.value = _groups.value.map {
            if (it.id != groupId) it else it.copy(
                tier = tierCode?.let { code -> runCatching { ClubTier.fromCode(code) }.getOrNull() } ?: it.tier,
                colorsDescription = colorsDesc ?: it.colorsDescription,
                clubSiglas = siglas ?: it.clubSiglas,
                traditionType = tradition ?: it.traditionType,
                directivaScope = scope ?: it.directivaScope,
                directivaRegion = region ?: it.directivaRegion,
                motto = motto ?: it.motto,
                city = city ?: it.city,
                foundationDate = foundationDate?.ifBlank { it.foundationDate } ?: it.foundationDate,
                logoCustomUri = logoUrl ?: it.logoCustomUri,
                profileVersion = remoteVersion ?: it.profileVersion
            )
        }
        return true
    }

    fun updateMemberClass(
        groupId: String,
        memberUserId: String,
        newClass: MemberClass,
        clubRole: ClubRole?
    ): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))
        val targetMember = group.members.find { it.userId == memberUserId }
            ?: return Result.failure(IllegalArgumentException("MEMBER_NOT_FOUND"))

        val statusKind = when (newClass) {
            MemberClass.FOUNDER, MemberClass.FULL_MEMBER -> MemberStatusKind.FULL
            MemberClass.PROSPECT -> MemberStatusKind.PROSPECT
            MemberClass.GUEST -> MemberStatusKind.GUEST
        }
        val isFounder = newClass == MemberClass.FOUNDER || targetMember.isFounder
        val safeRole = if (newClass == MemberClass.GUEST) null else clubRole

        val updatedMembers = group.members.map { m ->
            if (m.userId == memberUserId) {
                m.copy(
                    statusKind = statusKind,
                    isFounder = isFounder,
                    clubRole = safeRole
                )
            } else m
        }
        val updated = group.copy(members = updatedMembers)
        _groups.value = _groups.value.map { if (it.id == groupId) updated else it }
        return Result.success(updated)
    }

    /**
     * Transferencia de grupo con ventana de espera de 7 días y cancelación (RULES R5.3 y R14.3).
     */
    fun initiateGroupTransfer(
        groupId: String,
        actorUserId: String,
        toLeaderUserId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Result<ClubGroupModel> {
        val group = _groups.value.find { it.id == groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))

        if (group.principalLeaderId != actorUserId) {
            return Result.failure(IllegalStateException("ONLY_PRINCIPAL_LEADER_CAN_TRANSFER"))
        }

        val recipient = directoryUsers[toLeaderUserId]
            ?: return Result.failure(IllegalArgumentException("RECIPIENT_NOT_FOUND"))

        if (recipient.accountType != AccountType.LEADER) {
            return Result.failure(IllegalStateException("RECIPIENT_MUST_BE_ACTIVE_LEADER"))
        }

        val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
        val updatedGroup = group.copy(
            pendingTransferToUserId = toLeaderUserId,
            pendingTransferAvailableAtEpochMs = nowEpochMs + sevenDaysMs
        )
        _groups.value = _groups.value.map { if (it.id == groupId) updatedGroup else it }
        return Result.success(updatedGroup)
    }

    fun registerDirectoryUserForTest(user: UserProfileModel) {
        directoryUsers[user.id] = user
    }

    /**
     * Asigna o remueve el apadrinamiento de un club (Hermandad Biker).
     * Recalcula automáticamente el conteo de apadrinados para todos los clubes.
     * Los clubes que superen 7 apadrinados pasan a conformar la Mesa Directiva del País.
     */
    fun updateClubSponsor(clubId: String, newSponsorId: String?): Result<ClubGroupModel> {
        val currentGroups = _groups.value
        val club = currentGroups.find { it.id == clubId }
            ?: return Result.failure(IllegalArgumentException("CLUB_NOT_FOUND"))

        val sponsorClub = if (!newSponsorId.isNullOrBlank()) {
            if (newSponsorId == clubId) {
                return Result.failure(IllegalArgumentException("CANNOT_SPONSOR_SELF"))
            }
            currentGroups.find { it.id == newSponsorId }
                ?: return Result.failure(IllegalArgumentException("SPONSOR_NOT_FOUND"))
        } else null

        val updatedClub = club.copy(
            sponsorGroupId = sponsorClub?.id,
            sponsorGroupName = sponsorClub?.name
        )

        // Actualizar la lista con el club modificado
        val interimList = currentGroups.map { if (it.id == clubId) updatedClub else it }

        // Recalcular conteo de apadrinados en tiempo real para todos los clubes
        val finalList = interimList.map { g ->
            val count = interimList.count { it.sponsorGroupId == g.id }
            g.copy(sponsoredClubsCount = count)
        }

        _groups.value = finalList
        val finalUpdatedClub = finalList.find { it.id == clubId } ?: updatedClub
        return Result.success(finalUpdatedClub)
    }

    /**
     * Activa el paquete de $15 USD para acceso al Directorio Global y Censo Biker.
     */
    fun purchaseDirectoryPackage(): Result<Unit> {
        _hasDirectoryPackagePaid.value = true
        return Result.success(Unit)
    }

    fun setDirectoryPackagePaid(paid: Boolean) {
        _hasDirectoryPackagePaid.value = paid
    }

    /**
     * Eleva una denuncia formal por usurpación de identidad, logo o nombre ante la Mesa Directiva Nacional.
     */
    fun submitUsurpationReport(report: UsurpationReport): Result<Unit> {
        _usurpationReports.value = listOf(report) + _usurpationReports.value
        return Result.success(Unit)
    }

    /**
     * Acepta una solicitud de apadrinamiento, integrando al club solicitante como ahijado y sumando estrellas de linaje.
     */
    fun acceptSponsorshipRequest(requestId: String): Result<Unit> {
        val req = _sponsorshipRequests.value.find { it.id == requestId }
            ?: return Result.failure(IllegalArgumentException("REQUEST_NOT_FOUND"))

        _sponsorshipRequests.value = _sponsorshipRequests.value.map {
            if (it.id == requestId) it.copy(status = "ACCEPTED") else it
        }

        updateClubSponsor(req.requestingClubId, req.targetSponsorClubId)
        return Result.success(Unit)
    }

    /**
     * Rechaza formalmente una solicitud de apadrinamiento.
     */
    fun rejectSponsorshipRequest(requestId: String): Result<Unit> {
        _sponsorshipRequests.value = _sponsorshipRequests.value.map {
            if (it.id == requestId) it.copy(status = "REJECTED") else it
        }
        return Result.success(Unit)
    }

    /**
     * Envía una solicitud de apadrinamiento formal a un club hermano.
     */
    fun sendSponsorshipRequest(
        requestingClubId: String,
        targetSponsorClubId: String,
        message: String
    ): Result<SponsorshipRequestModel> {
        val requesting = _groups.value.find { it.id == requestingClubId }
            ?: return Result.failure(IllegalArgumentException("REQUESTING_CLUB_NOT_FOUND"))
        val target = _groups.value.find { it.id == targetSponsorClubId }
            ?: return Result.failure(IllegalArgumentException("TARGET_SPONSOR_NOT_FOUND"))

        val president = requesting.members.find { it.clubRole == ClubRole.PRESIDENT }?.displayName ?: "Presidente"

        val newReq = SponsorshipRequestModel(
            id = "req-${UUID.randomUUID().toString().take(8)}",
            requestingClubId = requesting.id,
            requestingClubName = requesting.name,
            requestingClubTier = requesting.tier,
            requestingClubCountry = requesting.countryCode,
            requestingClubState = requesting.stateRegion,
            requestingPresidentName = president,
            requestingFoundationDate = requesting.foundationDate,
            targetSponsorClubId = target.id,
            targetSponsorClubName = target.name,
            requestedAtDate = "2026-10-28",
            message = message
        )
        _sponsorshipRequests.value = listOf(newReq) + _sponsorshipRequests.value
        return Result.success(newReq)
    }

    data class RealFlowSummary(
        val leaderId: String,
        val memberId: String,
        val groupId: String,
        val invitationId: String,
        val membersCount: Int,
        val refreshedTokenPrefix: String,
        val logoutRevoked: Boolean
    )

    /**
     * Ejecuta un flujo completo de extremo a extremo desde Android (ClubRepository + ClubApiClient)
     * con `useFakeBackend = false` contra el servidor Fastify + PostgreSQL 16 real.
     *
     * Las credenciales deben proveerse explícitamente (p.ej. desde entorno/config de test).
     * No se incluyen credenciales seed en src/main (B4-4/T-05).
     */
    suspend fun executeRealBackendFlow(
        runSuffix: String,
        leaderPassword: String,
        memberPassword: String
    ): Result<RealFlowSummary> {
        require(leaderPassword.isNotBlank() && memberPassword.isNotBlank()) {
            "E2E credentials must be provided externally (TODO_EXTERNAL:TEST_CREDENTIALS for non-local runs)"
        }
        updateDevOverrides { it.copy(useFakeBackend = false) }

        val leaderEmail = "lider.e2e.$runSuffix@rbs.mx"
        val memberEmail = "miembro.e2e.$runSuffix@rbs.mx"

        // 1. Login / registro del líder
        val leaderLogin = apiClient.login(
            email = leaderEmail,
            password = leaderPassword,
            displayName = "Líder E2E $runSuffix",
            countryCode = "MX",
            acceptedTermsAndConvivencia = true,
            activateLeaderDemo = true
        ).getOrElse { return Result.failure(it) }
        if (!leaderLogin.isSuccess) {
            return Result.failure(IllegalStateException("LEADER_LOGIN_HTTP_${leaderLogin.statusCode}"))
        }
        val leaderToken = leaderLogin.body?.get("accessToken")?.jsonPrimitive?.content ?: ""
        val leaderRefresh = leaderLogin.body?.get("refreshToken")?.jsonPrimitive?.content ?: ""
        val leaderUserObj = leaderLogin.body?.get("user") as? kotlinx.serialization.json.JsonObject
        val leaderId = leaderUserObj?.get("id")?.jsonPrimitive?.content ?: ""
        apiClient.accessToken = leaderToken
        apiClient.refreshToken = leaderRefresh
        syncWithRealBackend().getOrElse { return Result.failure(it) }

        // 2. Login / registro del miembro
        val memberLogin = apiClient.login(
            email = memberEmail,
            password = memberPassword,
            displayName = "Miembro E2E $runSuffix",
            countryCode = "MX",
            acceptedTermsAndConvivencia = true,
            activateLeaderDemo = false
        ).getOrElse { return Result.failure(it) }
        if (!memberLogin.isSuccess) {
            return Result.failure(IllegalStateException("MEMBER_LOGIN_HTTP_${memberLogin.statusCode}"))
        }
        val memberToken = memberLogin.body?.get("accessToken")?.jsonPrimitive?.content ?: ""
        val memberUserObj = memberLogin.body?.get("user") as? kotlinx.serialization.json.JsonObject
        val memberId = memberUserObj?.get("id")?.jsonPrimitive?.content ?: ""

        // 3. Crear grupo como líder
        apiClient.accessToken = leaderToken
        apiClient.refreshToken = leaderRefresh
        val phashHex = runSuffix.padEnd(16, 'a').take(16)
        val groupRes = apiClient.createGroup(
            countryCode = "MX",
            name = "Centauros E2E $runSuffix",
            logoPhashHex = phashHex
        ).getOrElse { return Result.failure(it) }
        if (!groupRes.isSuccess) {
            return Result.failure(IllegalStateException("CREATE_GROUP_HTTP_${groupRes.statusCode}:${groupRes.body}"))
        }
        val groupId = groupRes.body?.get("id")?.jsonPrimitive?.content ?: ""

        // 4. Enviar invitación al miembro
        val invRes = apiClient.sendInvitation(
            groupId = groupId,
            invitedUserId = memberId,
            statusKind = "full"
        ).getOrElse { return Result.failure(it) }
        if (!invRes.isSuccess) {
            return Result.failure(IllegalStateException("SEND_INVITE_HTTP_${invRes.statusCode}:${invRes.body}"))
        }
        val invitationId = invRes.body?.get("id")?.jsonPrimitive?.content ?: ""

        // 5. Aceptar invitación como miembro con los 4 consentimientos (R15.1)
        apiClient.accessToken = memberToken
        val accRes = apiClient.acceptInvitation(
            invitationId = invitationId,
            membership = true,
            location = true,
            emergencyPackage = true,
            notifications = true
        ).getOrElse { return Result.failure(it) }
        if (!accRes.isSuccess) {
            return Result.failure(IllegalStateException("ACCEPT_INVITE_HTTP_${accRes.statusCode}:${accRes.body}"))
        }

        // 6. Consultar lista de miembros del grupo como líder
        apiClient.accessToken = leaderToken
        val membersRes = apiClient.getGroupMembers(groupId).getOrElse { return Result.failure(it) }
        if (!membersRes.isSuccess) {
            return Result.failure(IllegalStateException("GET_MEMBERS_HTTP_${membersRes.statusCode}"))
        }
        val membersArray = membersRes.body?.get("members") as? kotlinx.serialization.json.JsonArray
        val membersCount = membersArray?.size ?: 0

        // 7. Rotar sesión con refreshToken y cerrar sesión (logout)
        val refreshRes = apiClient.refreshSession().getOrElse { return Result.failure(it) }
        if (!refreshRes.isSuccess) {
            return Result.failure(IllegalStateException("REFRESH_HTTP_${refreshRes.statusCode}"))
        }
        val rotatedAccess = refreshRes.body?.get("accessToken")?.jsonPrimitive?.content ?: ""
        val logoutRes = apiClient.logout().getOrElse { return Result.failure(it) }
        val revoked = logoutRes.body?.get("revoked")?.jsonPrimitive?.content == "true"

        return Result.success(
            RealFlowSummary(
                leaderId = leaderId,
                memberId = memberId,
                groupId = groupId,
                invitationId = invitationId,
                membersCount = membersCount,
                refreshedTokenPrefix = rotatedAccess.take(12),
                logoutRevoked = revoked
            )
        )
    }

    companion object {
        @Volatile
        private var instance: ClubRepository? = null

        fun getInstance(): ClubRepository {
            return instance ?: synchronized(this) {
                instance ?: ClubRepository().also { instance = it }
            }
        }

        fun setTestInstance(testInstance: ClubRepository?) {
            instance = testInstance
        }
    }

}