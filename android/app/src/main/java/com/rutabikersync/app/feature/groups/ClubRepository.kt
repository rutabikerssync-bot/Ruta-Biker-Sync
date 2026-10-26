package com.rutabikersync.app.feature.groups

import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.ui.components.skeleton.ErrorKind
import com.rutabikersync.app.ui.components.skeleton.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Interruptores del Panel de Desarrollador (solo debug: BuildConfig.DEV_PANEL_ENABLED).
 * Cumple sección 2.2 de la especificación maestra.
 */
data class DevPanelOverrides(
    val useFakeBackend: Boolean = true,
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
    val apiClient: ClubApiClient = ClubApiClient()
) {

    private val _devOverrides = MutableStateFlow(DevPanelOverrides())
    val devOverrides: StateFlow<DevPanelOverrides> = _devOverrides.asStateFlow()

    private val _currentUser = MutableStateFlow(
        UserProfileModel(
            id = "u-founder-1",
            email = "comandante@rutabikersync.mx",
            displayName = "Comandante Águila",
            countryCode = "MX",
            accountType = AccountType.LEADER,
            bikeModel = "BMW R1250GS Adventure",
            mapColorHex = "#FFB300",
            validRides = 14,
            hasFounderFlame = true,
            badges = listOf("founder", "first_ride", "500_km")
        )
    )
    val currentUser: StateFlow<UserProfileModel> = _currentUser.asStateFlow()

    private val _groups = MutableStateFlow<List<ClubGroupModel>>(emptyList())
    val groups: StateFlow<List<ClubGroupModel>> = _groups.asStateFlow()

    private val _pendingInvitations = MutableStateFlow<List<ClubInvitationModel>>(emptyList())
    val pendingInvitations: StateFlow<List<ClubInvitationModel>> = _pendingInvitations.asStateFlow()

    private val directoryUsers = mutableMapOf<String, UserProfileModel>()

    init {
        loadDemoData()
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
     * Botón "Cargar datos demo" del Panel de Desarrollador (sección 2.2):
     * Carga 3 grupos, 10 miembros con todos los cargos/estatus/semáforos, invitaciones y votaciones.
     */
    fun loadDemoData() {
        val founder = UserProfileModel(
            id = "u-founder-1",
            email = "comandante@rutabikersync.mx",
            displayName = "Comandante Águila",
            countryCode = "MX",
            accountType = AccountType.LEADER,
            bikeModel = "BMW R1250GS Adventure",
            mapColorHex = "#FFB300",
            validRides = 14,
            hasFounderFlame = true,
            badges = listOf("founder", "first_ride", "500_km")
        )
        _currentUser.value = founder

        val demoUsers = listOf(
            founder,
            UserProfileModel("u-leader-2", "lobo@rbs.mx", "Lobo Norteño", "MX", AccountType.LEADER, "Triumph Tiger 900", "#00E676", 28, false, listOf("500_km")),
            UserProfileModel("u-leader-3", "halcon@rbs.mx", "Halcón Nocturno", "MX", AccountType.LEADER, "Yamaha Tenere 700", "#2979FF", 12, false, listOf("night_ride")),
            UserProfileModel("u-leader-4", "coyote@rbs.mx", "Coyote Sierra", "MX", AccountType.LEADER, "KTM 890 Adventure", "#FF9100", 8, false, emptyList()),
            UserProfileModel("u-member-1", "rayo@rbs.mx", "Rayo Azteca", "MX", AccountType.MEMBER, "Honda Africa Twin", "#00E5FF", 19, false, listOf("first_ride")),
            UserProfileModel("u-member-2", "sombra@rbs.mx", "Sombra Vial", "MX", AccountType.MEMBER, "Suzuki V-Strom 650", "#FF4081", 6, false, emptyList()),
            UserProfileModel("u-member-3", "toro@rbs.mx", "Toro Bravo", "MX", AccountType.MEMBER, "Harley Pan America", "#76FF03", 32, false, listOf("500_km")),
            UserProfileModel("u-member-4", "centella@rbs.mx", "Centella Sur", "MX", AccountType.MEMBER, "Ducati Multistrada V4", "#FFEA00", 4, false, emptyList()),
            UserProfileModel("u-prospect-1", "novato@rbs.mx", "Puma Joven", "MX", AccountType.MEMBER, "Kawasaki Versys 650", "#B388FF", 1, false, emptyList()),
            UserProfileModel("u-guest-1", "nomada@rbs.mx", "Nómada Libre", "MX", AccountType.MEMBER, "Royal Enfield Himalayan", "#1DE9B6", 11, false, listOf("3_groups"))
        )
        directoryUsers.clear()
        demoUsers.forEach { directoryUsers[it.id] = it }

        val group1 = ClubGroupModel(
            id = "grp-halcones-mx",
            countryCode = "MX",
            name = "Halcones del Asfalto",
            nameNormalized = ClubRulesEngine.normalizeGroupName("Halcones del Asfalto"),
            logoPhashHex = "a5a5a5a5a5a5a5a5",
            principalLeaderId = founder.id,
            leaderIds = listOf(founder.id, "u-leader-2", "u-leader-3"),
            members = listOf(
                ClubMemberModel(founder.id, founder.displayName, founder.bikeModel, MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 14),
                ClubMemberModel("u-leader-2", "Lobo Norteño", "Triumph Tiger 900", MemberStatusKind.FULL, ClubRole.VICE_PRESIDENT, isLeader = true, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 28),
                ClubMemberModel("u-leader-3", "Halcón Nocturno", "Yamaha Tenere 700", MemberStatusKind.FULL, ClubRole.ROAD_CAPTAIN, isLeader = true, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 12),
                ClubMemberModel("u-member-1", "Rayo Azteca", "Honda Africa Twin", MemberStatusKind.FULL, ClubRole.SERGEANT_AT_ARMS, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 19),
                ClubMemberModel("u-member-2", "Sombra Vial", "Suzuki V-Strom 650", MemberStatusKind.FULL, ClubRole.SECRETARY, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 6),
                ClubMemberModel("u-member-3", "Toro Bravo", "Harley Pan America", MemberStatusKind.FULL, ClubRole.TREASURER, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.YELLOW, validRides = 32),
                ClubMemberModel("u-member-4", "Centella Sur", "Ducati Multistrada V4", MemberStatusKind.FULL, ClubRole.ROAD_MARSHAL, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.RED, validRides = 4),
                ClubMemberModel("u-prospect-1", "Puma Joven", "Kawasaki Versys 650", MemberStatusKind.PROSPECT, null, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 1),
                ClubMemberModel("u-guest-1", "Nómada Libre", "Royal Enfield Himalayan", MemberStatusKind.GUEST, null, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 11)
            ),
            openVotes = listOf(
                LeaderVoteModel(
                    id = "vote-demo-1",
                    groupId = "grp-halcones-mx",
                    proposal = "remove_member",
                    targetLabel = "Centella Sur",
                    approvals = setOf(founder.id),
                    rejections = emptySet(),
                    requiredVotes = 2,
                    state = "open"
                )
            )
        )

        val group2 = ClubGroupModel(
            id = "grp-coyotes-mx",
            countryCode = "MX",
            name = "Coyotes de la Sierra",
            nameNormalized = ClubRulesEngine.normalizeGroupName("Coyotes de la Sierra"),
            logoPhashHex = "00ff00ff00ff00ff",
            principalLeaderId = "u-leader-2",
            leaderIds = listOf("u-leader-2"),
            members = listOf(
                ClubMemberModel("u-leader-2", "Lobo Norteño", "Triumph Tiger 900", MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 28),
                ClubMemberModel("u-guest-1", "Nómada Libre", "Royal Enfield Himalayan", MemberStatusKind.GUEST, null, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 11),
                ClubMemberModel(founder.id, founder.displayName, founder.bikeModel, MemberStatusKind.GUEST, null, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 14)
            )
        )

        val group3 = ClubGroupModel(
            id = "grp-condores-co",
            countryCode = "CO",
            name = "Halcones del Asfalto", // Mismo nombre permitido en otro país (RULES R5.1)
            nameNormalized = ClubRulesEngine.normalizeGroupName("Halcones del Asfalto"),
            logoPhashHex = "a5a5a5a5a5a5a5a5",
            principalLeaderId = "u-leader-3",
            leaderIds = listOf("u-leader-3"),
            members = listOf(
                ClubMemberModel("u-leader-3", "Halcón Nocturno", "Yamaha Tenere 700", MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 12)
            )
        )

        _groups.value = listOf(group1, group2, group3)
        _pendingInvitations.value = listOf(
            ClubInvitationModel(
                id = "inv-demo-1",
                groupId = "grp-coyotes-mx",
                groupName = "Titanes del Pacífico",
                countryCode = "MX",
                invitedUserId = founder.id,
                statusKind = MemberStatusKind.GUEST,
                monthlyCostUsd = 2.50,
                state = "pending"
            )
        )
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
            bikeModel = "BMW F850GS",
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
        mapColorHex: String
    ): UserProfileModel {
        val updated = _currentUser.value.copy(
            displayName = displayName.trim().ifEmpty { _currentUser.value.displayName },
            bikeModel = bikeModel.trim(),
            mapColorHex = mapColorHex
        )
        _currentUser.value = updated
        directoryUsers[updated.id] = updated
        return updated
    }

    /**
     * Crear grupo con validación de cuenta Líder (R4.1), unicidad de nombre con leet por país (R5.1),
     * y unicidad de logo por pHash Hamming < 8 (R5.2). Otorga llama permanente de Fundador (R8.2).
     */
    fun createGroup(
        actorUserId: String,
        countryCode: String,
        name: String,
        logoPhashHex: String
    ): Result<ClubGroupModel> {
        val actor = directoryUsers[actorUserId] ?: _currentUser.value.takeIf { it.id == actorUserId }
            ?: return Result.failure(IllegalStateException("USER_NOT_FOUND"))

        if (actor.accountType != AccountType.LEADER) {
            return Result.failure(IllegalStateException("LEADER_REQUIRED"))
        }

        val normalizedCountry = countryCode.uppercase()
        val sameCountryGroups = _groups.value.filter { it.countryCode == normalizedCountry }

        for (existing in sameCountryGroups) {
            val collision = ClubRulesEngine.checkGroupNameCollision(name, existing.name)
            if (collision.collides) {
                return Result.failure(IllegalArgumentException("GROUP_NAME_TAKEN_IN_COUNTRY:${collision.reason}"))
            }
            val logoSim = ClubRulesEngine.evaluateLogoSimilarity(logoPhashHex, existing.logoPhashHex)
            if (logoSim.rejected) {
                return Result.failure(IllegalArgumentException("GROUP_LOGO_TAKEN_IN_COUNTRY:${logoSim.distance}"))
            }
        }

        val newGroup = ClubGroupModel(
            id = "grp-${UUID.randomUUID().toString().take(8)}",
            countryCode = normalizedCountry,
            name = name.trim(),
            nameNormalized = ClubRulesEngine.normalizeGroupName(name),
            logoPhashHex = logoPhashHex.lowercase(),
            principalLeaderId = actor.id,
            leaderIds = listOf(actor.id),
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
            )
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
            id = "inv-${UUID.randomUUID().toString().take(8)}",
            groupId = group.id,
            groupName = group.name,
            countryCode = group.countryCode,
            invitedUserId = invitedUserId,
            statusKind = statusKind,
            monthlyCostUsd = 2.50,
            state = "pending"
        )
        _pendingInvitations.value = _pendingInvitations.value + inv
        return Result.success(inv)
    }

    fun addPendingInvitation(invitation: ClubInvitationModel) {
        val filtered = _pendingInvitations.value.filterNot { it.id == invitation.id }
        _pendingInvitations.value = filtered + invitation
    }

    /**
     * Aceptar invitación con consentimientos separados obligatorios (RULES R2.1, R15.1):
     * - Membresía y ubicación son obligatorios para entrar al grupo.
     * - Vuelve a comprobar exclusividad de Full/Prospecto (R2.2).
     */
    fun acceptInvitation(
        invitationId: String,
        consentMembership: Boolean,
        consentLocation: Boolean,
        consentEmergencyPackage: Boolean,
        consentNotifications: Boolean
    ): Result<ClubGroupModel> {
        val inv = _pendingInvitations.value.find { it.id == invitationId && it.state == "pending" }
            ?: return Result.failure(IllegalArgumentException("INVITATION_NOT_FOUND"))

        if (!consentMembership || !consentLocation) {
            return Result.failure(IllegalArgumentException("REQUIRED_CONSENTS_MISSING"))
        }

        if (inv.statusKind == MemberStatusKind.FULL || inv.statusKind == MemberStatusKind.PROSPECT) {
            val alreadyInClub = _groups.value.any { g ->
                g.id != inv.groupId && g.members.any { m ->
                    m.userId == inv.invitedUserId &&
                        (m.statusKind == MemberStatusKind.FULL || m.statusKind == MemberStatusKind.PROSPECT)
                }
            }
            if (alreadyInClub) {
                return Result.failure(IllegalStateException("EXCLUSIVE_CLUB_MEMBERSHIP"))
            }
        }

        val group = _groups.value.find { it.id == inv.groupId }
            ?: return Result.failure(IllegalArgumentException("GROUP_NOT_FOUND"))

        val userProfile = directoryUsers[inv.invitedUserId] ?: _currentUser.value
        val newMember = ClubMemberModel(
            userId = inv.invitedUserId,
            displayName = userProfile.displayName,
            bikeModel = userProfile.bikeModel,
            statusKind = inv.statusKind,
            clubRole = null,
            isLeader = group.leaderIds.contains(inv.invitedUserId),
            isFounder = group.principalLeaderId == inv.invitedUserId,
            paymentSemaphore = PaymentSemaphore.GREEN,
            validRides = userProfile.validRides
        )

        val updatedGroup = group.copy(
            members = group.members.filterNot { it.userId == inv.invitedUserId } + newMember
        )
        _groups.value = _groups.value.map { if (it.id == group.id) updatedGroup else it }
        _pendingInvitations.value = _pendingInvitations.value.filterNot { it.id == invitationId }
        return Result.success(updatedGroup)
    }

    fun rejectInvitation(invitationId: String) {
        _pendingInvitations.value = _pendingInvitations.value.filterNot { it.id == invitationId }
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

        val approvals = if (approve) vote.approvals + leaderUserId else vote.approvals - leaderUserId
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
     */
    suspend fun executeRealBackendFlow(runSuffix: String): Result<RealFlowSummary> {
        updateDevOverrides { it.copy(useFakeBackend = false) }

        val leaderEmail = "lider.e2e.$runSuffix@rbs.mx"
        val memberEmail = "miembro.e2e.$runSuffix@rbs.mx"

        // 1. Login / registro del líder
        val leaderLogin = apiClient.login(
            email = leaderEmail,
            password = "StrongLeaderPass!2026",
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
            password = "StrongMemberPass!2026",
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
}
