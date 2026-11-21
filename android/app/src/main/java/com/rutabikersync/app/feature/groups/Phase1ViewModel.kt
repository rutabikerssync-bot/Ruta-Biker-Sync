package com.rutabikersync.app.feature.groups

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rutabikersync.app.core.clubroutes.ClubRouteEntity
import com.rutabikersync.app.core.clubroutes.RideRoster
import com.rutabikersync.app.feature.routes.RideScheduleRepository
import com.rutabikersync.app.ui.components.skeleton.UiState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class GroupsScreenData(
    val groups: List<ClubGroupModel>,
    val pendingInvitations: List<ClubInvitationModel>,
    val currentUser: UserProfileModel,
    val hasDirectoryPackagePaid: Boolean = true,
    val usurpationReports: List<UsurpationReport> = emptyList(),
    val sponsorshipRequests: List<SponsorshipRequestModel> = emptyList(),
    val invitations: List<Invitation> = emptyList()
)

class Phase1ViewModel(
    val repository: ClubRepository = ClubRepository.getInstance(),
    val invitationRepository: InvitationRepository = repository.invitationRepository
) : ViewModel() {

    val invitations: StateFlow<List<Invitation>> = invitationRepository.invitations

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _targetTabIndex = MutableStateFlow<Int?>(null)
    val targetTabIndex: StateFlow<Int?> = _targetTabIndex.asStateFlow()
    fun setTargetTab(tab: Int?) { _targetTabIndex.value = tab }

    private val _selectedInvitationId = MutableStateFlow<String?>(null)
    val selectedInvitationId: StateFlow<String?> = _selectedInvitationId.asStateFlow()

    private val _selectedMemberUserId = MutableStateFlow<String?>(null)
    val selectedMemberUserId: StateFlow<String?> = _selectedMemberUserId.asStateFlow()

    private val _forceSkeletonPreview = MutableStateFlow(false)
    val forceSkeletonPreview: StateFlow<Boolean> = _forceSkeletonPreview.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    /** Fase 5: >0 significa que hay RSVPs esperando conectividad (banner de la UI). */
    val rsvpPendingCount: StateFlow<Int> get() = repository.rsvpPendingCount

    // Fase 5 · Hito 3: UI mínima de rutas/RSVP (cache-first, sin tocar malla BLE).
    private val _showClubRoutes = MutableStateFlow(false)
    val showClubRoutes: StateFlow<Boolean> = _showClubRoutes.asStateFlow()

    private val _clubRoutes = MutableStateFlow<List<ClubRouteEntity>>(emptyList())
    val clubRoutes: StateFlow<List<ClubRouteEntity>> = _clubRoutes.asStateFlow()

    private val _nextClubRoute = MutableStateFlow<ClubRouteEntity?>(null)
    val nextClubRoute: StateFlow<ClubRouteEntity?> = _nextClubRoute.asStateFlow()

    /** Última intención local por ruta; el outbox es la fuente de verdad. */
    private val _clubRsvpByRoute = MutableStateFlow<Map<String, String>>(emptyMap())
    val clubRsvpByRoute: StateFlow<Map<String, String>> = _clubRsvpByRoute.asStateFlow()

    private val _rideRoster = MutableStateFlow<RideRoster?>(null)
    val rideRoster: StateFlow<RideRoster?> = _rideRoster.asStateFlow()

    private val _rideRosterError = MutableStateFlow<String?>(null)
    val rideRosterError: StateFlow<String?> = _rideRosterError.asStateFlow()

    private val _isStartingRide = MutableStateFlow(false)
    val isStartingRide: StateFlow<Boolean> = _isStartingRide.asStateFlow()

    fun openClubRoutes(groupId: String) {
        _showClubRoutes.value = true
        loadClubRoutes(groupId)
    }

    fun closeClubRoutes() {
        _showClubRoutes.value = false
    }

    fun loadClubRoutes(groupId: String) {
        viewModelScope.launch {
            try {
                val routes = repository.clubRoutes.loadRoutes(groupId)
                _clubRoutes.value = routes
                _nextClubRoute.value = ClubRsvpUi.nextRoute(routes)
                val userId = repository.currentUser.value.id
                _clubRsvpByRoute.value = routes.mapNotNull { route ->
                    val mine = repository.clubRoutes.loadRsvps(route.id)
                        .rsvps.firstOrNull { it.userId == userId }
                    mine?.status?.let { route.id to it }
                }.toMap()
            } catch (_: Exception) {
                // Sin Context inicializado (tests JVM) o sin red: se conserva la caché ya pintada.
            }
        }
    }

    fun requestRsvp(routeId: String, status: String) {
        val userId = repository.currentUser.value.id
        viewModelScope.launch {
            try {
                repository.clubRoutes.requestRsvp(routeId, userId, status)
                _clubRsvpByRoute.value = _clubRsvpByRoute.value + (routeId to status)
                updateRosterParticipant(userId) { participant ->
                    participant.copy(
                        status = status,
                        onSiteAt = if (status == "confirmed") participant.onSiteAt else null,
                        homeAt = if (status == "home") (participant.homeAt ?: Instant.now().toString()) else participant.homeAt
                    )
                }
                repository.refreshRsvpPendingCount()
                // P0: SAFE_HOME no se emite por BLE desde el outbox local. Sin
                // respuesta autorizada del backend no existe prueba de permiso
                // para invertir/terminar la rodada de todo el convoy.
            } catch (_: Exception) {
                // El outbox ya registró la intención antes de cualquier error de red.
            }
        }
    }

    fun loadUpcomingRideRoster(groupId: String) {
        viewModelScope.launch {
            try {
                val routes = repository.clubRoutes.loadRoutes(groupId)
                val next = ClubRsvpUi.nextRoute(routes) ?: return@launch
                loadRideRoster(next.id)
            } catch (_: Exception) {
                // Conservar el roster ya cargado/caché durante una caída de red.
            }
        }
    }

    fun loadRideRoster(routeId: String) {
        viewModelScope.launch {
            try {
                val fresh = repository.clubRoutes.loadRideRoster(routeId)
                if (fresh != null) {
                    _rideRoster.value = fresh
                    _rideRosterError.value = null
                    RideScheduleRepository.setReturnPhase(fresh.returnStartedAt != null)
                } else if (_rideRoster.value?.routeId != routeId) {
                    _rideRosterError.value = "No se pudo cargar la lista real de participantes."
                }
            } catch (_: Exception) {
                if (_rideRoster.value?.routeId != routeId) {
                    _rideRosterError.value = "Sin conexión: reintenta cargar los participantes."
                }
            }
        }
    }

    fun requestOnSite(routeId: String, userId: String) {
        viewModelScope.launch {
            try {
                repository.clubRoutes.requestOnSite(routeId, userId)
                updateRosterParticipant(userId) { participant ->
                    participant.copy(
                        status = "confirmed",
                        onSiteAt = participant.onSiteAt ?: Instant.now().toString()
                    )
                }
                _rideRosterError.value = null
                repository.refreshRsvpPendingCount()
            } catch (_: Exception) {
                _rideRosterError.value = "La llegada quedó pendiente de sincronizar."
            }
        }
    }

    fun startRide(routeId: String, onStarted: () -> Unit) {
        if (_isStartingRide.value) return
        _isStartingRide.value = true
        _rideRosterError.value = null
        RideScheduleRepository.setReturnPhase(false)
        viewModelScope.launch {
            try {
                // P0/H4: primero autoriza el backend; solo después se propaga por BLE.
                com.rutabikersync.app.core.mesh.RestFirstLifecycleGate.runAfterRest(
                    restCall = { repository.clubRoutes.startRoute(routeId) },
                    authorized = { it.isSuccess || it.statusCode == 409 },
                    onAuthorized = {
                        loadRideRoster(routeId)
                        onStarted()
                    },
                    onDenied = { response, _ ->
                        _rideRosterError.value = response?.body?.get("error")?.jsonPrimitive?.contentOrNull
                            ?: if (response != null) "No se pudo iniciar la ruta (${response.statusCode})."
                            else "Sin conexión: inicio no autorizado por el servidor. La malla no propagó el evento."
                    },
                    lifecycleEvent = com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.START_OUTBOUND
                )
            } finally {
                _isStartingRide.value = false
            }
        }
    }

    fun arriveDestination(routeId: String) {
        viewModelScope.launch {
            try {
                // P0/H4: REST decide; el estado local y la malla solo cambian tras éxito.
                com.rutabikersync.app.core.mesh.RestFirstLifecycleGate.runAfterRest(
                    restCall = { repository.clubRoutes.arriveDestination(routeId) },
                    authorized = { it.isSuccess },
                    onAuthorized = {
                        val nowIso = Instant.now().toString()
                        _rideRoster.value = _rideRoster.value?.copy(destinationReachedAt = nowIso)
                        loadRideRoster(routeId)
                    },
                    onDenied = { response, _ ->
                        _rideRosterError.value = response?.body?.get("error")?.jsonPrimitive?.contentOrNull
                            ?: if (response != null) "No se pudo marcar llegada a destino (${response.statusCode})."
                            else "Sin conexión: llegada no autorizada por el servidor. La malla no propagó el evento."
                    },
                    lifecycleEvent = com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.ARRIVE_DESTINATION
                )
            } catch (_: Exception) {
                _rideRosterError.value = "Error al marcar llegada a destino."
            }
        }
    }

    fun startReturn(routeId: String, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                // P0/H4: la inversión de ruta solo se aplica si el servidor autoriza.
                com.rutabikersync.app.core.mesh.RestFirstLifecycleGate.runAfterRest(
                    restCall = { repository.clubRoutes.startReturnRoute(routeId) },
                    authorized = { it.isSuccess || it.statusCode == 409 },
                    onAuthorized = {
                        RideScheduleRepository.setReturnPhase(true)
                        val nowIso = Instant.now().toString()
                        _rideRoster.value = _rideRoster.value?.copy(returnStartedAt = nowIso)
                        loadRideRoster(routeId)
                        onStarted()
                    },
                    onDenied = { response, _ ->
                        _rideRosterError.value = response?.body?.get("error")?.jsonPrimitive?.contentOrNull
                            ?: if (response != null) "No se pudo iniciar el retorno (${response.statusCode})."
                            else "Sin conexión: retorno no autorizado por el servidor. La malla no propagó el evento."
                    },
                    lifecycleEvent = com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.START_RETURN
                )
            } catch (_: Exception) {
                _rideRosterError.value = "Error al iniciar el retorno."
            }
        }
    }

    /** Vincula el mapa guardado con la ruta real publicada por el líder. */
    fun publishClubRoute(
        groupId: String,
        title: String,
        startsAt: String,
        description: String?
    ) {
        viewModelScope.launch {
            try {
                val result = repository.apiClient.createClubRoute(groupId, title, startsAt, description)
                result.fold(
                    onSuccess = { response ->
                        val route = response.body?.get("route") as? JsonObject
                        val serverRouteId = route?.get("id")?.jsonPrimitive?.contentOrNull
                        if (response.isSuccess && !serverRouteId.isNullOrBlank()) {
                            RideScheduleRepository.attachClubRoute(serverRouteId)
                            loadRideRoster(serverRouteId)
                        } else {
                            _rideRosterError.value = response.body?.get("error")?.jsonPrimitive?.contentOrNull
                                ?: "El mapa quedó guardado, pero la rodada no se publicó (${response.statusCode})."
                        }
                    },
                    onFailure = {
                        _rideRosterError.value = "Mapa guardado localmente; publicación del club pendiente por conexión."
                    }
                )
            } catch (_: Exception) {
                _rideRosterError.value = "Mapa guardado localmente; no se pudo publicar la rodada."
            }
        }
    }

    private fun updateRosterParticipant(
        userId: String,
        transform: (com.rutabikersync.app.core.clubroutes.RideRosterParticipant) ->
            com.rutabikersync.app.core.clubroutes.RideRosterParticipant
    ) {
        val current = _rideRoster.value ?: return
        _rideRoster.value = current.copy(
            participants = current.participants.map { participant ->
                if (participant.userId == userId) transform(participant) else participant
            }
        )
    }

    private val _isAcceptingInvite = MutableStateFlow(false)
    val isAcceptingInvite: StateFlow<Boolean> = _isAcceptingInvite.asStateFlow()

    init {
        repository.restorePresidentIfMissing()
        if (com.rutabikersync.app.BuildConfig.UX_TEST_MODE) {
            repository.resetToUxTestEmptyState(com.rutabikersync.app.core.uxtest.UxTestTracker.participantId.value)
        }
        pingBackendServer()
        // Ciclo reactivo de sincronización periódica con el backend
        // Red en IO: evita reanudar sobre Dispatchers.Main después de que el test
        // haya hecho resetMain (fatal "Main dispatcher failed to initialize").
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    repository.syncClubsAndInvitations()
                } catch (_: Exception) {}
                try {
                    repository.refreshRsvpPendingCount()
                } catch (_: Exception) {} // sin Context/init aún: no rompe el ciclo de sincronización
                kotlinx.coroutines.delay(1500L)
            }
        }
    }

    fun pingBackendServer() {
        if (repository.devOverrides.value.useFakeBackend) {
            _statusBanner.value = "🟢 Fastify online (status: ok) · PostgreSQL 16"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = repository.apiClient.checkHealth()
                res.onSuccess { apiRes ->
                    val status = apiRes.body?.get("status")?.toString()?.trim('"') ?: "ok"
                    _statusBanner.value = "🟢 Fastify online (status: $status) · PostgreSQL 16"
                }.onFailure { err ->
                    _statusBanner.value = "🔴 Fastify offline (${err.message})"
                }
            } catch (e: Exception) {
                _statusBanner.value = "🔴 Fastify offline (${e.message})"
            }
        }
    }

    fun syncWithBackend() {
        viewModelScope.launch {
            try {
                repository.syncClubsAndInvitations()
            } catch (_: Exception) {}
        }
    }

    fun setForceSkeletonPreview(enabled: Boolean) {
        _forceSkeletonPreview.value = enabled
    }

    fun selectGroup(groupId: String?) {
        _selectedGroupId.value = groupId
        _selectedMemberUserId.value = null
    }

    fun selectInvitation(invitationId: String?) {
        _selectedInvitationId.value = invitationId
    }

    fun selectMember(userId: String?) {
        _selectedMemberUserId.value = userId
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun buildGroupsUiState(
        groups: List<ClubGroupModel>,
        invitations: List<ClubInvitationModel>,
        user: UserProfileModel,
        forceLoading: Boolean,
        hasPaidDirectory: Boolean = repository.hasDirectoryPackagePaid.value,
        reports: List<UsurpationReport> = repository.usurpationReports.value,
        requests: List<SponsorshipRequestModel> = repository.sponsorshipRequests.value,
        invitationList: List<Invitation> = invitationRepository.invitations.value
    ): UiState<GroupsScreenData> {
        if (forceLoading) return UiState.Loading
        val data = GroupsScreenData(
            groups = groups,
            pendingInvitations = invitations,
            currentUser = user,
            hasDirectoryPackagePaid = hasPaidDirectory,
            usurpationReports = reports,
            sponsorshipRequests = requests,
            invitations = invitationList
        )
        return repository.resolveUiState(data) { false }
    }

    fun createNewGroup(
        countryCode: String,
        name: String,
        logoPhashHex: String,
        stateRegion: String = "Miranda",
        isExtension: Boolean = false,
        parentGroupId: String? = null,
        tier: ClubTier = ClubTier.MC
    ) {
        val user = repository.currentUser.value
        val res = repository.createGroup(user.id, countryCode, name, logoPhashHex, stateRegion, isExtension, parentGroupId, tier)
        res.fold(
            onSuccess = { grp ->
                val typeLabel = if (grp.isExtension) "Extensión oficial de '${grp.parentGroupName}'" else "Club único"
                _statusBanner.value = "$typeLabel '${grp.name}' creado en ${grp.countryCode} (${grp.stateRegion}) con marca Fundador 🔥"
                _selectedGroupId.value = grp.id

                // Despacho optimista en segundo plano al backend Fastify
                viewModelScope.launch {
                    val remoteRes = repository.apiClient.createGroup(countryCode, name, logoPhashHex)
                    remoteRes.fold(
                        onSuccess = { apiRes ->
                            if (apiRes.isSuccess) {
                                val serverGroupId = apiRes.body
                                    ?.get("id")
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                if (!serverGroupId.isNullOrBlank() && serverGroupId != grp.id) {
                                    repository.reconcileGroupId(grp.id, serverGroupId)
                                    if (_selectedGroupId.value == grp.id) {
                                        _selectedGroupId.value = serverGroupId
                                    }
                                }
                                try {
                                    repository.syncClubsAndInvitations()
                                } catch (_: Exception) {
                                    // Best-effort: el ID real ya quedó reconciliado.
                                }
                            } else if (!repository.devOverrides.value.useFakeBackend) {
                                repository.removeGroup(grp.id)
                                _statusBanner.value = "⚠️ Error del servidor (${apiRes.statusCode}). Creación de club revertida."
                            }
                        },
                        onFailure = { err ->
                            if (!repository.devOverrides.value.useFakeBackend) {
                                repository.removeGroup(grp.id)
                                _statusBanner.value = "⚠️ Fallo de conexión: ${err.message}. Creación revertida."
                            }
                        }
                    )
                }
            },
            onFailure = { err ->
                _statusBanner.value = "${err.message}"
            }
        )
    }

    fun sendInvite(groupId: String, invitedUserId: String, statusKind: MemberStatusKind) {
        val user = repository.currentUser.value
        val res = repository.sendInvitation(groupId, user.id, invitedUserId, statusKind)
        res.fold(
            onSuccess = { inv ->
                _statusBanner.value = "Invitación enviada a ${inv.invitedUserId} (${inv.statusKind.apiValue}, pendiente de aceptación)"
            },
            onFailure = { err ->
                _statusBanner.value = "No se pudo invitar: ${err.message}"
            }
        )
    }

    fun acceptInvite(
        invitationId: String,
        consentMembership: Boolean,
        consentLocation: Boolean,
        consentEmergency: Boolean,
        consentNotifications: Boolean
    ) {
        if (!consentMembership || !consentLocation) {
            _statusBanner.value = "Debes otorgar los consentimientos obligatorios de membresía y ubicación."
            return
        }
        val user = repository.currentUser.value
        val myClubs = repository.groups.value.filter { grp ->
            grp.members.any { it.userId == user.id || it.displayName.equals(user.displayName, ignoreCase = true) } ||
            grp.leaderIds.contains(user.id) ||
            grp.principalLeaderId == user.id
        }
        val primaryClub = myClubs.firstOrNull()

        // Fallback por robustez: si no existía aún en invitationRepository (ej. QR entrante directo)
        var domainInv = invitationRepository.getInvitationById(invitationId)
        if (domainInv == null) {
            val pendingLegacy = repository.pendingInvitations.value.find { it.id == invitationId }
            val defaultType = if (pendingLegacy?.statusKind == MemberStatusKind.GUEST) InvitationType.RODADA else InvitationType.HERMANDAD
            domainInv = invitationRepository.createInvitation(
                type = defaultType,
                clubId = pendingLegacy?.groupId ?: "grp-lobos",
                clubName = pendingLegacy?.groupName ?: "Lobos del Asfalto",
                inviterUid = pendingLegacy?.founderId ?: "u-inviter",
                inviterName = pendingLegacy?.presidentName ?: "Líder Rodada",
                customId = invitationId
            )
        }

        val isHermandad = domainInv.type == InvitationType.HERMANDAD

        if (isHermandad) {
            // FLUJO SÍNCRONO CON SPINNER PARA HERMANDAD (Estatuto Biker R2.2)
            // 1. Verificación local inmediata (0 ms): Si ya pertenece a un club diferente
            if (primaryClub != null && primaryClub.id != domainInv.clubId) {
                _statusBanner.value = "🛡️ Estatuto Biker: Ya perteneces a otro club. Por estatutos no puedes ser miembro de dos clubes. Solicita una Invitación a Rodar."
                return
            }

            // 2. Si no tiene club local, verificar con el servidor síncronamente con spinner
            _isAcceptingInvite.value = true
            viewModelScope.launch {
                try {
                    if (repository.apiClient.currentUserId.isNullOrBlank() || repository.apiClient.currentUserId?.startsWith("u-") == true) {
                        repository.apiClient.currentUserId = user.id
                    }
                    val remoteRes = repository.apiClient.acceptInvitation(
                        invitationId = invitationId,
                        membership = consentMembership,
                        location = consentLocation,
                        emergencyPackage = consentEmergency,
                        notifications = consentNotifications
                    )
                    remoteRes.fold(
                        onSuccess = { apiRes ->
                            if (apiRes.statusCode == 409) {
                                // R2.2: Conflicto de doble membresía detectado por el backend (sin rollback silencioso)
                                _statusBanner.value = "🛡️ Estatuto Biker (Servidor): Conflicto de doble membresía detectado (409 Conflict). Unión rechazada."
                            } else if (apiRes.isSuccess) {
                                // Aprobado por el backend: aplicar mutación local y sincronizar con estado canónico del servidor
                                val res = invitationRepository.acceptInvitation(
                                    invitationId = invitationId,
                                    inviteeUid = user.id,
                                    inviteeName = user.displayName,
                                    currentClubId = null
                                )
                                res.fold(
                                    onSuccess = { acc ->
                                        _statusBanner.value = acc.message
                                        if (acc.isSuccess) {
                                            _selectedInvitationId.value = null
                                            repository.removePendingLegacyInvitation(invitationId)
                                            if (acc.appliedClubId != null) {
                                                _selectedGroupId.value = acc.appliedClubId
                                                val targetGroup = repository.groups.value.find { it.id == acc.appliedClubId }
                                                if (targetGroup != null) {
                                                    if (targetGroup.members.none { it.userId == user.id }) {
                                                        val newMember = ClubMemberModel(
                                                            userId = user.id,
                                                            displayName = user.displayName,
                                                            bikeModel = user.bikeModel,
                                                            statusKind = MemberStatusKind.FULL,
                                                            clubRole = null,
                                                            isLeader = false,
                                                            isFounder = false,
                                                            paymentSemaphore = PaymentSemaphore.GREEN,
                                                            validRides = user.validRides
                                                        )
                                                        repository.addMemberToClub(acc.appliedClubId, newMember)
                                                    }
                                                } else {
                                                    val clubName = domainInv?.clubName ?: "Club Biker"
                                                    val newClub = ClubGroupModel(
                                                        id = acc.appliedClubId,
                                                        countryCode = user.countryCode.ifBlank { "VE" },
                                                        name = clubName,
                                                        nameNormalized = ClubRulesEngine.normalizeGroupName(clubName),
                                                        logoPhashHex = "123456789abcdef0",
                                                        principalLeaderId = domainInv?.inviterUid ?: "u-leader",
                                                        leaderIds = listOf(domainInv?.inviterUid ?: "u-leader"),
                                                        members = listOf(
                                                            ClubMemberModel(
                                                                userId = user.id,
                                                                displayName = user.displayName,
                                                                bikeModel = user.bikeModel,
                                                                statusKind = MemberStatusKind.FULL,
                                                                clubRole = null,
                                                                isLeader = false,
                                                                isFounder = false,
                                                                paymentSemaphore = PaymentSemaphore.GREEN,
                                                                validRides = user.validRides
                                                            ),
                                                            ClubMemberModel(
                                                                userId = domainInv?.inviterUid ?: "u-leader",
                                                                displayName = domainInv?.inviterName ?: "Presidente",
                                                                bikeModel = "Motorcycle",
                                                                statusKind = MemberStatusKind.FULL,
                                                                clubRole = ClubRole.PRESIDENT,
                                                                isLeader = true,
                                                                isFounder = true,
                                                                paymentSemaphore = PaymentSemaphore.GREEN,
                                                                validRides = 10
                                                            )
                                                        ),
                                                        tier = ClubTier.MC
                                                    )
                                                    repository.addGroup(newClub)
                                                }
                                            }
                                            repository.syncClubsAndInvitations()
                                        }
                                    },
                                    onFailure = { err ->
                                        _statusBanner.value = "Error local: ${err.message}"
                                    }
                                )
                            } else {
                                _statusBanner.value = "Error del servidor (${apiRes.statusCode}): ${apiRes.body}"
                            }
                        },
                        onFailure = { err ->
                            // En caso de fallo de red/offline, procesar conforme al repositorio local-first
                            val res = invitationRepository.acceptInvitation(
                                invitationId = invitationId,
                                inviteeUid = user.id,
                                inviteeName = user.displayName,
                                currentClubId = primaryClub?.id
                            )
                            res.fold(
                                onSuccess = { acc ->
                                    _statusBanner.value = acc.message
                                    if (acc.isSuccess) {
                                        _selectedInvitationId.value = null
                                        repository.removePendingLegacyInvitation(invitationId)
                                        if (acc.appliedClubId != null) {
                                            _selectedGroupId.value = acc.appliedClubId
                                            val targetGroup = repository.groups.value.find { it.id == acc.appliedClubId }
                                            if (targetGroup != null) {
                                                if (targetGroup.members.none { it.userId == user.id }) {
                                                    val newMember = ClubMemberModel(
                                                        userId = user.id,
                                                        displayName = user.displayName,
                                                        bikeModel = user.bikeModel,
                                                        statusKind = MemberStatusKind.FULL,
                                                        clubRole = null,
                                                        isLeader = false,
                                                        isFounder = false,
                                                        paymentSemaphore = PaymentSemaphore.GREEN,
                                                        validRides = user.validRides
                                                    )
                                                    repository.addMemberToClub(acc.appliedClubId, newMember)
                                                    }
                                                } else {
                                                val clubName = domainInv?.clubName ?: "Club Biker"
                                                val newClub = ClubGroupModel(
                                                    id = acc.appliedClubId,
                                                    countryCode = user.countryCode.ifBlank { "VE" },
                                                    name = clubName,
                                                    nameNormalized = ClubRulesEngine.normalizeGroupName(clubName),
                                                    logoPhashHex = "123456789abcdef0",
                                                    principalLeaderId = domainInv?.inviterUid ?: "u-leader",
                                                    leaderIds = listOf(domainInv?.inviterUid ?: "u-leader"),
                                                    members = listOf(
                                                        ClubMemberModel(
                                                            userId = user.id,
                                                            displayName = user.displayName,
                                                            bikeModel = user.bikeModel,
                                                            statusKind = MemberStatusKind.FULL,
                                                            clubRole = null,
                                                            isLeader = false,
                                                            isFounder = false,
                                                            paymentSemaphore = PaymentSemaphore.GREEN,
                                                            validRides = user.validRides
                                                        ),
                                                        ClubMemberModel(
                                                            userId = domainInv?.inviterUid ?: "u-leader",
                                                            displayName = domainInv?.inviterName ?: "Presidente",
                                                            bikeModel = "Motorcycle",
                                                            statusKind = MemberStatusKind.FULL,
                                                            clubRole = ClubRole.PRESIDENT,
                                                            isLeader = true,
                                                            isFounder = true,
                                                            paymentSemaphore = PaymentSemaphore.GREEN,
                                                            validRides = 10
                                                        )
                                                    ),
                                                    tier = ClubTier.MC
                                                )
                                                repository.addGroup(newClub)
                                                }
                                            }
                                            repository.syncClubsAndInvitations()
                                        }
                                    },
                                onFailure = { localErr ->
                                    _statusBanner.value = "Error al procesar: ${localErr.message}"
                                }
                            )
                        }
                    )
                } finally {
                    _isAcceptingInvite.value = false
                }
            }
        } else {
            // FLUJO OPTIMISTA (0 ms) PARA RODADA LIBRE (R2.3)
            val res = invitationRepository.acceptInvitation(
                invitationId = invitationId,
                inviteeUid = user.id,
                inviteeName = user.displayName,
                currentClubId = primaryClub?.id
            )
            res.fold(
                onSuccess = { acc ->
                    _statusBanner.value = acc.message
                    if (acc.isSuccess) {
                        _selectedInvitationId.value = null
                        repository.removePendingLegacyInvitation(invitationId)
                    }
                },
                onFailure = { err ->
                    _statusBanner.value = "Error al procesar invitación: ${err.message}"
                }
            )

            // Despacho asíncrono en segundo plano al backend (sin bloquear UI)
            viewModelScope.launch {
                repository.apiClient.acceptInvitation(
                    invitationId = invitationId,
                    membership = consentMembership,
                    location = consentLocation,
                    emergencyPackage = consentEmergency,
                    notifications = consentNotifications
                )
            }
        }
    }

    fun createInvitation(
        type: InvitationType,
        clubId: String,
        clubName: String
    ): Invitation {
        val user = repository.currentUser.value
        val inv = invitationRepository.createInvitation(
            type = type,
            clubId = clubId,
            clubName = clubName,
            inviterUid = user.id,
            inviterName = user.displayName
        )
        _statusBanner.value = "✓ Invitación (${type.title}) generada: ${inv.id}"

        // Despacho optimista en segundo plano
        viewModelScope.launch {
            val remoteRes = repository.apiClient.sendInvitation(
                groupId = clubId,
                invitedUserId = null,
                statusKind = if (type == InvitationType.RODADA) "guest" else "full",
                invitationType = type.code,
                customId = inv.id
            )
            remoteRes.fold(
                onSuccess = { apiRes ->
                    if (!apiRes.isSuccess && !repository.devOverrides.value.useFakeBackend) {
                        invitationRepository.rejectInvitation(inv.id, user.id, "Servidor rechazó invitación")
                        _statusBanner.value = "⚠️ Servidor rechazó la emisión (${apiRes.statusCode}). Invitación cancelada."
                    } else {
                        repository.syncClubsAndInvitations()
                    }
                },
                onFailure = { err ->
                    if (!repository.devOverrides.value.useFakeBackend) {
                        invitationRepository.rejectInvitation(inv.id, user.id, "Fallo de conexión")
                        _statusBanner.value = "⚠️ Fallo al sincronizar invitación: ${err.message}"
                    }
                }
            )
        }
        return inv
    }

    fun rejectInvite(invitationId: String) {
        repository.rejectInvitation(invitationId)
        _selectedInvitationId.value = null
        _statusBanner.value = "Invitación rechazada"
    }

    fun joinByQrCode(rawPayload: String) {
        val payload = GroupInviteQrHelper.parsePayload(rawPayload)
        if (payload == null) {
            _statusBanner.value = "Código QR o texto de invitación inválido"
            return
        }
        val user = repository.currentUser.value
        val myClubs = repository.groups.value.filter { grp ->
            grp.members.any { it.userId == user.id || it.displayName.equals(user.displayName, ignoreCase = true) } ||
            grp.leaderIds.contains(user.id) ||
            grp.principalLeaderId == user.id
        }
        val primaryClub = myClubs.firstOrNull()

        val targetGroup = repository.groups.value.find { it.id == payload.groupId }
        val targetName = targetGroup?.name ?: payload.groupName.ifEmpty { "Club Biker" }

        when (payload.intentType) {
            GroupInviteQrHelper.QrIntentType.BROTHERHOOD -> {
                if (primaryClub == null) {
                    _statusBanner.value = "🛡️ Para formalizar un pacto de Hermandad con '$targetName' debes presidir o pertenecer a un club."
                    return
                }
                if (primaryClub.id == payload.groupId) {
                    _statusBanner.value = "🛡️ Este es el código de Hermandad de tu propio club (${primaryClub.name})."
                    return
                }
                val isAlreadyBrotherhood = primaryClub.sponsorGroupId == payload.groupId ||
                        targetGroup?.sponsorGroupId == primaryClub.id
                if (isAlreadyBrotherhood) {
                    _statusBanner.value = "🤝 Tu club '${primaryClub.name}' ya tiene una alianza de Hermandad activa con '$targetName'"
                    return
                }
                repository.updateClubSponsor(primaryClub.id, payload.groupId)
                _statusBanner.value = "🤝 ¡Pacto de Hermandad Biker formalizado entre ${primaryClub.name} y $targetName!"
                return
            }

            GroupInviteQrHelper.QrIntentType.SPONSORSHIP -> {
                if (primaryClub == null) {
                    _statusBanner.value = "🛡️ Para apadrinar a '$targetName' debes pertenecer a un club formal."
                    return
                }
                if (primaryClub.id == payload.groupId) {
                    _statusBanner.value = "🛡️ Este es el código de Apadrinamiento de tu propio club (${primaryClub.name})."
                    return
                }
                repository.updateClubSponsor(primaryClub.id, payload.groupId)
                _statusBanner.value = "⭐ ¡Vínculo de Apadrinamiento Biker formalizado entre ${primaryClub.name} y $targetName!"
                return
            }

            GroupInviteQrHelper.QrIntentType.RIDE_INVITE -> {
                // Invitación oficial a rodar (libre y gratuita) - válida tanto si tienes club como si eres independiente
                invitationRepository.createInvitation(
                    type = InvitationType.RODADA,
                    clubId = payload.groupId,
                    clubName = targetName,
                    inviterUid = payload.founderId.ifEmpty { "u-inviter" },
                    inviterName = payload.presidentName.ifEmpty { "Líder Rodada" },
                    customId = payload.inviteCode
                )
                val existing = repository.pendingInvitations.value.find { it.id == payload.inviteCode || it.groupId == payload.groupId }
                if (existing != null) {
                    _selectedInvitationId.value = existing.id
                    _statusBanner.value = "🏍️ Abriendo invitación a rodar para '${existing.groupName}'"
                } else {
                    val newInv = ClubInvitationModel(
                        id = payload.inviteCode,
                        groupId = payload.groupId,
                        groupName = targetName,
                        countryCode = payload.countryCode.ifEmpty { targetGroup?.countryCode ?: "MX" },
                        invitedUserId = user.id,
                        statusKind = MemberStatusKind.GUEST,
                        monthlyCostUsd = 0.0,
                        state = "pending",
                        presidentName = payload.presidentName,
                        founderId = payload.founderId
                    )
                    repository.addPendingInvitation(newInv)
                    _selectedInvitationId.value = newInv.id
                    _statusBanner.value = "🏍️ Invitación a rodada recibida de '$targetName'. ¡Libre y gratuita!"
                }
                return
            }

            GroupInviteQrHelper.QrIntentType.MEMBER -> {
                // Estatuto Biker: Si ya perteneces a un club, no puedes ingresar como miembro raso a otro
                if (primaryClub != null) {
                    if (primaryClub.id == payload.groupId) {
                        _statusBanner.value = "🛡️ Ya eres miembro y líder de ${primaryClub.name}"
                        return
                    }
                    _statusBanner.value = "🛡️ Estatuto Biker: Ya perteneces a '${primaryClub.name}'. Por estatutos no puedes ser miembro de dos clubes. Solicita a '$targetName' su QR de Hermandad, Apadrinamiento o Invitación a Rodar."
                    return
                }

                // Si NO tiene club, flujo regular de admisión de miembro
                invitationRepository.createInvitation(
                    type = InvitationType.HERMANDAD,
                    clubId = payload.groupId,
                    clubName = targetName,
                    inviterUid = payload.founderId.ifEmpty { "u-inviter" },
                    inviterName = payload.presidentName.ifEmpty { "Líder Hermandad" },
                    customId = payload.inviteCode
                )
                val existing = repository.pendingInvitations.value.find { it.id == payload.inviteCode || it.groupId == payload.groupId }
                if (existing != null) {
                    _selectedInvitationId.value = existing.id
                    _statusBanner.value = "Abriendo solicitud de admisión para '${existing.groupName}'"
                } else {
                    val newInv = ClubInvitationModel(
                        id = payload.inviteCode,
                        groupId = payload.groupId,
                        groupName = targetName,
                        countryCode = payload.countryCode.ifEmpty { targetGroup?.countryCode ?: "MX" },
                        invitedUserId = user.id,
                        statusKind = MemberStatusKind.GUEST,
                        monthlyCostUsd = 0.0,
                        state = "pending",
                        presidentName = payload.presidentName,
                        founderId = payload.founderId
                    )
                    repository.addPendingInvitation(newInv)
                    _selectedInvitationId.value = newInv.id
                    _statusBanner.value = "Solicitud de admisión detectada para '$targetName'"
                }
            }
        }
    }

    fun updateMemberRoleOrStatus(
        groupId: String,
        memberUserId: String,
        statusKind: MemberStatusKind,
        clubRole: ClubRole?
    ) {
        val actor = repository.currentUser.value
        val res = repository.assignMemberRoleOrStatus(groupId, actor.id, memberUserId, statusKind, clubRole)
        res.fold(
            onSuccess = { updatedGroup ->
                val name = updatedGroup.members.find { it.userId == memberUserId }?.displayName ?: memberUserId
                _statusBanner.value = "✓ Estatus y cargo actualizados para $name"
                _selectedMemberUserId.value = null
            },
            onFailure = { err ->
                _statusBanner.value = "Regla R2.4/R3.3: ${err.message}"
            }
        )
    }

    fun expelMember(groupId: String, memberUserId: String, context: android.content.Context? = null) {
        val actor = repository.currentUser.value
        val groupBefore = repository.groups.value.find { it.id == groupId }
        val memberBefore = groupBefore?.members?.find { it.userId == memberUserId }

        val res = repository.expelMemberFromClub(groupId, actor.id, memberUserId)
        res.fold(
            onSuccess = { updatedGroup ->
                _statusBanner.value = "✓ Piloto removido de '${updatedGroup.name}'"
                _selectedMemberUserId.value = null
                if (actor.id == memberUserId) {
                    context?.let { ctx ->
                        val prefs = ctx.getSharedPreferences("rbs_auth_prefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit().remove("current_club_id").apply()
                    }
                }

                // Despacho optimista en segundo plano con rollback si el backend lo rechaza
                viewModelScope.launch {
                    val remoteRes = repository.apiClient.expelMember(groupId, memberUserId)
                    remoteRes.fold(
                        onSuccess = { apiRes ->
                            if (!apiRes.isSuccess && !repository.devOverrides.value.useFakeBackend) {
                                if (memberBefore != null) {
                                    repository.addMemberToClub(groupId, memberBefore)
                                }
                                _statusBanner.value = "⚠️ Servidor rechazó la expulsión (${apiRes.statusCode}). Acción revertida."
                            }
                        },
                        onFailure = { err ->
                            if (!repository.devOverrides.value.useFakeBackend) {
                                if (memberBefore != null) {
                                    repository.addMemberToClub(groupId, memberBefore)
                                }
                                _statusBanner.value = "⚠️ Error de red al expulsar: ${err.message}. Acción revertida."
                            }
                        }
                    )
                }
            },
            onFailure = { err ->
                _statusBanner.value = "No se pudo expulsar: ${err.message}"
            }
        )
    }

    fun updateClubDetails(
        groupId: String,
        name: String,
        countryCode: String,
        stateRegion: String,
        foundationDate: String,
        tier: ClubTier,
        city: String? = null,
        logoCustomUri: String? = null,
        logoPhashHex: String? = null
    ) {
        val res = repository.updateGroupDetails(groupId, name, countryCode, stateRegion, foundationDate, tier, city, logoPhashHex, logoCustomUri)
        res.fold(
            onSuccess = {
                _statusBanner.value = "Club actualizado: '${it.name}' (${it.tier.code}) · ${it.stateRegion}, ${it.countryCode}"
            },
            onFailure = { err ->
                _statusBanner.value = "Error al actualizar club: ${err.message}"
            }
        )
    }

    fun updateClubLogo(groupId: String, logoCustomUri: String?, logoPhashHex: String? = null) {
        val res = repository.updateClubLogo(groupId, logoCustomUri, logoPhashHex)
        res.fold(
            onSuccess = {
                _statusBanner.value = "🛡️ Escudo / Logo de '${it.name}' actualizado exitosamente"
            },
            onFailure = { err ->
                _statusBanner.value = "Error al actualizar logo: ${err.message}"
            }
        )
    }

    fun updateClubLogoFromUri(context: android.content.Context, groupId: String, uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    val maxDim = 512
                    val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                        val ratio = Math.min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                        android.graphics.Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * ratio).toInt(),
                            (bitmap.height * ratio).toInt(),
                            true
                        )
                    } else bitmap

                    val logoDir = java.io.File(context.filesDir, "club_logos").apply { mkdirs() }
                    val targetFile = java.io.File(logoDir, "logo_${groupId}.jpg")
                    targetFile.outputStream().use { out ->
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    }

                    // Convertir a Base64 para sincronización instantánea inter-dispositivos
                    val baos = java.io.ByteArrayOutputStream()
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                    val base64Logo = "data:image/jpeg;base64," + android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        updateClubLogo(groupId, targetFile.absolutePath)
                        val group = repository.groups.value.find { it.id == groupId }
                        viewModelScope.launch {
                            repository.apiClient.updateGroupProfile(
                                groupId = groupId,
                                colorsDescription = group?.colorsDescription,
                                clubSiglas = group?.clubSiglas,
                                traditionType = group?.traditionType,
                                directivaScope = group?.directivaScope,
                                directivaRegion = group?.directivaRegion,
                                motto = group?.motto,
                                city = group?.city,
                                foundationDate = group?.foundationDate,
                                logoUrl = base64Logo,
                                tier = group?.tier?.code
                            )
                            repository.syncClubsAndInvitations()
                        }
                    }
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _statusBanner.value = "Error: No se pudo decodificar la imagen seleccionada"
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _statusBanner.value = "Error al procesar foto del escudo: ${e.message}"
                }
            }
        }
    }

    fun updateDirectivaOfficer(groupId: String, role: ClubRole, newOfficerUserId: String) {
        val res = repository.updateDirectivaOfficer(groupId, role, newOfficerUserId)
        res.fold(
            onSuccess = {
                _statusBanner.value = "✓ Mesa Directiva: ${role.apiValue.replace("_", " ").uppercase()} asignado exitosamente"
                // Despacho asíncrono al backend Fastify
                viewModelScope.launch {
                    repository.apiClient.updateMemberRoleOrStatus(
                        groupId = groupId,
                        memberUserId = newOfficerUserId,
                        statusKind = "full",
                        clubRole = role.apiValue
                    )
                    repository.syncClubsAndInvitations()
                }
            },
            onFailure = { err ->
                _statusBanner.value = "Error directiva: ${err.message}"
            }
        )
    }

    fun updateMemberClassAndRole(
        groupId: String,
        memberUserId: String,
        newClass: MemberClass,
        clubRole: ClubRole?
    ) {
        val res = repository.updateMemberClass(groupId, memberUserId, newClass, clubRole)
        res.fold(
            onSuccess = {
                _statusBanner.value = "✓ Miembro actualizado: ${newClass.displayName}${if (clubRole != null) " · ${clubRole.apiValue.uppercase()}" else ""}"
                _selectedMemberUserId.value = null

                // Despacho optimista en segundo plano al backend Fastify + PostgreSQL
                viewModelScope.launch {
                    val statusStr = when (newClass) {
                        MemberClass.FOUNDER, MemberClass.FULL_MEMBER -> "full"
                        MemberClass.PROSPECT -> "prospect"
                        MemberClass.GUEST -> "guest"
                    }
                    val roleStr = clubRole?.apiValue
                    repository.apiClient.updateMemberRoleOrStatus(groupId, memberUserId, statusStr, roleStr)
                    repository.syncClubsAndInvitations()
                }
            },
            onFailure = { err ->
                _statusBanner.value = "Error al editar miembro: ${err.message}"
            }
        )
    }

    fun updateClubProfile(
        groupId: String,
        colorsDescription: String,
        clubSiglas: String,
        traditionType: String,
        directivaScope: String,
        directivaRegion: String,
        motto: String,
        city: String? = null,
        foundationDate: String? = null
    ) {
        val res = repository.updateClubProfile(
            groupId = groupId,
            colorsDescription = colorsDescription,
            clubSiglas = clubSiglas,
            traditionType = traditionType,
            directivaScope = directivaScope,
            directivaRegion = directivaRegion,
            motto = motto,
            city = city,
            foundationDate = foundationDate
        )
        res.fold(
            onSuccess = { optimistic ->
                viewModelScope.launch {
                    val logoPayload = if (optimistic.logoCustomUri?.startsWith("data:image") == true) optimistic.logoCustomUri else null
                    val apiRes = repository.apiClient.updateGroupProfile(
                        groupId = groupId,
                        colorsDescription = colorsDescription,
                        clubSiglas = clubSiglas,
                        traditionType = traditionType,
                        directivaScope = directivaScope,
                        directivaRegion = directivaRegion,
                        motto = motto,
                        city = city,
                        foundationDate = foundationDate,
                        logoUrl = logoPayload,
                        tier = optimistic.tier.code
                    ).getOrNull()

                    if (apiRes != null && apiRes.isSuccess) {
                        val applied = repository.applyGroupProfileFromApi(groupId, apiRes.body)
                        if (applied) {
                            _statusBanner.value = "✓ Perfil e identidad biker de '${optimistic.name}' guardados con éxito"
                        } else {
                            repository.syncClubsAndInvitations()
                            _statusBanner.value = "✓ Perfil guardado en servidor. Sincronizando datos actualizados..."
                        }
                    } else {
                        // Sincronizar para revertir el optimismo local: evita que el cambio
                        // "dure un segundo" y vuelva a los valores antiguos del servidor.
                        val detail = apiRes?.body?.get("error")?.toString()?.trim('"')
                        _statusBanner.value = if (detail.isNullOrBlank()) {
                            "⚠️ No se pudo guardar el perfil en el servidor. Se restauraron los datos actuales."
                        } else {
                            "⚠️ No se pudo guardar el perfil ($detail). Se restauraron los datos actuales."
                        }
                        repository.syncClubsAndInvitations()
                    }
                }
            },
            onFailure = { err ->
                _statusBanner.value = "Error al actualizar perfil: ${err.message}"
            }
        )
    }

    fun createSensitiveVote(groupId: String, proposal: String, targetLabel: String) {
        val actor = repository.currentUser.value
        val res = repository.createLeaderVote(groupId, proposal, targetLabel, actor.id)
        res.fold(
            onSuccess = { v ->
                _statusBanner.value = "Votación convocada: '${v.proposal}' (${v.approvals.size}/${v.requiredVotes} votos)"
            },
            onFailure = { err ->
                _statusBanner.value = "Error al convocar votación: ${err.message}"
            }
        )
    }

    fun voteOnSensitiveAction(groupId: String, voteId: String, approve: Boolean) {
        val actor = repository.currentUser.value
        val res = repository.castLeaderVote(groupId, voteId, actor.id, approve)
        res.fold(
            onSuccess = { v ->
                _statusBanner.value = "Voto registrado (${v.approvals.size}/${v.requiredVotes}) · Estado: ${v.state}"
            },
            onFailure = { err ->
                _statusBanner.value = "Error en votación: ${err.message}"
            }
        )
    }

    fun startGroupTransfer(groupId: String, toLeaderUserId: String) {
        val actor = repository.currentUser.value
        val res = repository.initiateGroupTransfer(groupId, actor.id, toLeaderUserId)
        res.fold(
            onSuccess = {
                _statusBanner.value = "Transferencia iniciada hacia $toLeaderUserId · Espera de seguridad de 7 días (R5.3)"
            },
            onFailure = { err ->
                _statusBanner.value = "No se pudo iniciar transferencia: ${err.message}"
            }
        )
    }

    fun setClubSponsor(clubId: String, sponsorId: String?) {
        val res = repository.updateClubSponsor(clubId, sponsorId)
        res.fold(
            onSuccess = { updated ->
                val sponsorMsg = if (updated.sponsorGroupName != null) "Apadrinado por '${updated.sponsorGroupName}'" else "Club Independiente y Autónomo"
                val councilMsg = if (updated.isNationalCouncil) " · 🏛️ Miembro de la Mesa Directiva Nacional (>7 apadrinamientos)" else ""
                _statusBanner.value = "Hermandad actualizada: '${updated.name}' ($sponsorMsg)$councilMsg"
            },
            onFailure = { err ->
                _statusBanner.value = "No se pudo actualizar apadrinamiento: ${err.message}"
            }
        )
    }

    fun saveUserProfile(displayName: String, bikeModel: String, mapColorHex: String, avatarUri: String? = null) {
        val current = repository.currentUser.value
        val finalAvatar = if (avatarUri != null) avatarUri else current.avatarUri
        repository.updateProfile(displayName, bikeModel, mapColorHex, finalAvatar)
        _statusBanner.value = "Perfil actualizado localmente y sincronizado"
    }

    fun removeAvatarImage() {
        val current = repository.currentUser.value
        repository.updateProfile(current.displayName, current.bikeModel, current.mapColorHex, avatarUri = "")
        _statusBanner.value = "Avatar restablecido a iniciales metálicas"
    }

    fun loginOrRegisterUser(
        email: String,
        displayName: String,
        countryCode: String,
        acceptedTerms: Boolean,
        activateLeader: Boolean
    ): Result<UserProfileModel> {
        val res = repository.loginOrRegister(email, displayName, countryCode, acceptedTerms, activateLeader)
        res.fold(
            onSuccess = { u ->
                _statusBanner.value = "Sesión iniciada como ${u.displayName} (${u.accountType.name})"
            },
            onFailure = { err ->
                _statusBanner.value = "Debes aceptar términos y reglas de convivencia (${err.message})"
            }
        )
        return res
    }

    /**
     * Iniciar sesión o registrarse con Cuenta de Google.
     * Si el usuario ya tenía configurada la cuenta como Líder, se recuerda y se aplica automáticamente.
     */
    fun loginWithGoogle(
        email: String,
        displayName: String,
        requestedRole: AccountType? = null,
        context: Context? = null
    ): Result<UserProfileModel> {
        val cleanEmail = email.trim().lowercase()
        val prefs = context?.getSharedPreferences("rbs_auth_prefs", Context.MODE_PRIVATE)
        val savedRoleStr = prefs?.getString("google_role_$cleanEmail", null)
            ?: prefs?.getString("google_role_last", null)

        val effectiveRole = when {
            requestedRole != null -> requestedRole
            savedRoleStr != null -> {
                try {
                    AccountType.valueOf(savedRoleStr)
                } catch (e: Exception) {
                    AccountType.MEMBER
                }
            }
            cleanEmail.contains("comandante") || cleanEmail.contains("lider") -> AccountType.LEADER
            else -> AccountType.MEMBER
        }

        val res = repository.loginOrRegister(
            email = cleanEmail,
            displayName = displayName.ifEmpty { cleanEmail.substringBefore("@") },
            countryCode = "MX",
            acceptedTermsAndConvivencia = true,
            activateLeaderAccount = (effectiveRole == AccountType.LEADER)
        )

        res.fold(
            onSuccess = { user ->
                prefs?.edit()
                    ?.putBoolean("is_authenticated", true)
                    ?.putString("user_id", user.id)
                    ?.putString("user_email", user.email)
                    ?.putString("user_display_name", user.displayName)
                    ?.putString("account_type", user.accountType.name)
                    ?.putString("google_role_$cleanEmail", user.accountType.name)
                    ?.putString("google_role_last", user.accountType.name)
                    ?.putString("auth_provider", "GOOGLE")
                    ?.apply()

                val roleLabel = if (user.accountType == AccountType.LEADER) "Líder 👑" else "Miembro 👤"
                _statusBanner.value = "Ingresaste con Google como ${user.displayName} ($roleLabel recordado)"
            },
            onFailure = { err ->
                _statusBanner.value = "Error al ingresar con Google: ${err.message}"
            }
        )
        return res
    }

    /**
     * Alternar rol de usuario entre Líder y Miembro desde Configuración o Perfil.
     * Guarda la preferencia para que futuros inicios de sesión con Google la recuerden.
     */
    fun switchAccountType(newType: AccountType, context: Context? = null): UserProfileModel {
        val updated = repository.switchAccountType(newType)
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences("rbs_auth_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("account_type", newType.name)
                .putString("google_role_${updated.email.lowercase()}", newType.name)
                .putString("google_role_last", newType.name)
                .apply()
        }
        val roleLabel = if (newType == AccountType.LEADER) "Líder 👑" else "Miembro 👤"
        _statusBanner.value = "Rol cambiado a $roleLabel. Recordado para tu cuenta de Google."
        return updated
    }

    /**
     * Restaurar sesión previa persistida en el dispositivo.
     */
    fun restoreSession(
        id: String,
        email: String,
        displayName: String,
        accountType: AccountType
    ) {
        repository.restoreSession(
            id = id,
            email = email,
            displayName = displayName,
            accountType = accountType
        )
    }

    fun ensureClubLoaded(clubId: String, currentUid: String, currentDisplayName: String, currentRole: AccountType) {
        if (repository.groups.value.none { it.id == clubId }) {
            val aguilasClub = ClubGroupModel(
                id = clubId,
                countryCode = "VE",
                name = "Aguilas del Camino",
                nameNormalized = "aguilas del camino",
                logoPhashHex = "a1b2c3d4e5f60718",
                stateRegion = "Miranda",
                isExtension = false,
                parentGroupId = null,
                parentGroupName = null,
                leaderIds = listOf("omartinezpaz@gmail.com"),
                principalLeaderId = "omartinezpaz@gmail.com",
                tier = ClubTier.MC,
                foundationDate = "2018-03-15",
                members = listOf(
                    ClubMemberModel(
                        userId = "omartinezpaz@gmail.com",
                        displayName = "Capitán Oscar",
                        bikeModel = "BMW R1250GS",
                        statusKind = MemberStatusKind.FULL,
                        clubRole = ClubRole.PRESIDENT,
                        isLeader = true,
                        isFounder = true,
                        paymentSemaphore = PaymentSemaphore.GREEN,
                        validRides = 14
                    ),
                    ClubMemberModel(
                        userId = "f2850920-3014-3990-8d87-106a245c2365",
                        displayName = "ozmartinezpaz",
                        bikeModel = "Super Shadwon 250",
                        statusKind = MemberStatusKind.GUEST,
                        clubRole = null,
                        isLeader = false,
                        isFounder = false,
                        paymentSemaphore = PaymentSemaphore.GREEN,
                        validRides = 0
                    )
                )
            )
            repository.restoreClubIfMissing(aguilasClub)
        }
        if (_selectedGroupId.value == null || _selectedGroupId.value == "OVERVIEW") {
            _selectedGroupId.value = clubId
        }
    }

    fun logoutUser() {
        repository.resetToUxTestEmptyState("Guest")
        _statusBanner.value = "Sesión cerrada correctamente"
    }

    fun purchaseDirectoryPackage() {
        val res = repository.purchaseDirectoryPackage()
        res.fold(
            onSuccess = {
                _statusBanner.value = "💳 ¡Paquete VIP de $15 USD activado! Acceso desbloqueado al Directorio Global y Censo Biker."
            },
            onFailure = { err ->
                _statusBanner.value = "Error al activar paquete: ${err.message}"
            }
        )
    }

    fun setDirectoryPackagePaid(paid: Boolean) {
        repository.setDirectoryPackagePaid(paid)
        _statusBanner.value = if (paid) "Paquete de $15 USD activado" else "Paquete de $15 USD desactivado (Modo Paywall)"
    }

    fun submitUsurpationReport(
        reportingClubId: String,
        reportingClubName: String,
        reportingPresidentName: String,
        reportedTargetName: String,
        countryCode: String,
        stateRegion: String,
        infringement: UsurpationInfringement,
        description: String,
        evidenceNotes: String
    ) {
        val report = UsurpationReport(
            id = "rep-${java.util.UUID.randomUUID().toString().take(8)}",
            reportingClubId = reportingClubId,
            reportingClubName = reportingClubName,
            reportingPresidentName = reportingPresidentName,
            reportedTargetName = reportedTargetName,
            countryCode = countryCode,
            stateRegion = stateRegion,
            infringement = infringement,
            description = description,
            evidenceNotes = evidenceNotes,
            reportedAtDate = "2026-10-28",
            status = "EN_REVISION"
        )
        repository.submitUsurpationReport(report)
        _statusBanner.value = "⚠️ Denuncia radicada ante la Mesa Directiva Nacional (${report.infringement.title})"
    }

    fun acceptSponsorshipRequest(requestId: String) {
        val res = repository.acceptSponsorshipRequest(requestId)
        res.fold(
            onSuccess = {
                _statusBanner.value = "🤝 ¡Apadrinamiento formal aceptado! Se ha sumado una estrella de hermandad."
            },
            onFailure = { err ->
                _statusBanner.value = "Error al procesar apadrinamiento: ${err.message}"
            }
        )
    }

    fun rejectSponsorshipRequest(requestId: String) {
        val res = repository.rejectSponsorshipRequest(requestId)
        res.fold(
            onSuccess = {
                _statusBanner.value = "Solicitud de apadrinamiento rechazada respetuosamente."
            },
            onFailure = { err ->
                _statusBanner.value = "Error: ${err.message}"
            }
        )
    }

    fun requestSponsorship(requestingClubId: String, targetSponsorClubId: String, message: String) {
        val res = repository.sendSponsorshipRequest(requestingClubId, targetSponsorClubId, message)
        res.fold(
            onSuccess = { req ->
                _statusBanner.value = "📨 Solicitud de apadrinamiento enviada a '${req.targetSponsorClubName}'"
            },
            onFailure = { err ->
                _statusBanner.value = "No se pudo enviar solicitud: ${err.message}"
            }
        )
    }

    fun sponsorClubByQr(ahijadoClubId: String) {
        val currentUserId = repository.currentUser.value.id
        val myUserClub = repository.groups.value.find { g -> g.members.any { it.userId == currentUserId && it.clubRole == ClubRole.PRESIDENT } }
            ?: repository.groups.value.find { g -> g.members.any { it.userId == currentUserId } }
        if (myUserClub == null) {
            _statusBanner.value = "Debes pertenecer a un club para apadrinar"
            return
        }
        val res = repository.updateClubSponsor(ahijadoClubId, myUserClub.id)
        res.fold(
            onSuccess = { updatedAhijado ->
                _statusBanner.value = "🤝 ¡Apadrinamiento Presencial Exitoso! Has otorgado una estrella de hermandad a '${updatedAhijado.name}' y entra en tu linaje."
            },
            onFailure = { err ->
                _statusBanner.value = "Error al otorgar apadrinamiento: ${err.message}"
            }
        )
    }

    fun setStatusBanner(msg: String?) {
        _statusBanner.value = msg
    }

    val consentsSummary = repository.consentsSummary

    fun toggleConsent(kind: String, granted: Boolean, groupId: String? = null) {
        val res = repository.toggleConsent(kind, granted, groupId)
        res.fold(
            onSuccess = {
                val label = when (kind) {
                    "location" -> "Ubicación en rodada"
                    "emergency_package" -> "Ficha médica de emergencia SOS"
                    "notifications" -> "Notificaciones de grupo"
                    else -> kind
                }
                val estado = if (granted) "activado" else "revocado"
                _statusBanner.value = "Consentimiento de $label $estado correctamente (RULES R15)"
            },
            onFailure = { err ->
                _statusBanner.value = "Error al actualizar consentimiento: ${err.message}"
            }
        )
    }

    fun exportUserData(context: Context, onComplete: (File?) -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.exportUserDataJson()
            res.fold(
                onSuccess = { jsonText ->
                    try {
                        val fileName = "rbs_export_${System.currentTimeMillis()}.json"
                        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                        val file = File(exportDir, fileName)
                        file.writeText(jsonText, Charsets.UTF_8)
                        _statusBanner.value = "✅ Datos exportados exitosamente (${file.length()} bytes)"
                        onComplete(file)
                    } catch (e: Exception) {
                        _statusBanner.value = "Error guardando exportación: ${e.message}"
                        onComplete(null)
                    }
                },
                onFailure = { err ->
                    _statusBanner.value = "Error al exportar datos: ${err.message}"
                    onComplete(null)
                }
            )
        }
    }

    fun deleteAccount(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val res = repository.deleteUserAccount()
            res.fold(
                onSuccess = {
                    _statusBanner.value = "Cuenta y datos personales eliminados conforme a RGPD (RULES R15.3)"
                    onSuccess()
                },
                onFailure = { err ->
                    _statusBanner.value = "Error al eliminar cuenta: ${err.message}"
                }
            )
        }
    }

    val moderationReports = repository.moderationReports
    val leaderVotes = repository.leaderVotes

    fun fileReport(
        groupId: String,
        reportedUserId: String,
        rideId: String?,
        reason: String,
        note: String?,
        reportedName: String = "Rider Reportado"
    ) {
        viewModelScope.launch {
            val res = repository.fileModerationReport(groupId, reportedUserId, rideId, reason, note, reportedName)
            res.fold(
                onSuccess = {
                    _statusBanner.value = "🛡️ Reporte enviado a la Mesa Directiva del grupo (R13.1, R13.2)"
                },
                onFailure = { err ->
                    _statusBanner.value = "Error enviando reporte: ${err.message}"
                }
            )
        }
    }

    fun resolveReport(
        groupId: String,
        reportId: String,
        resolution: String?,
        state: String
    ) {
        viewModelScope.launch {
            val res = repository.resolveModerationReport(groupId, reportId, resolution, state)
            res.fold(
                onSuccess = {
                    val action = when (resolution) {
                        "warning" -> "Advertencia emitida"
                        "muted" -> "Silenciado en rodadas"
                        "ride_removed" -> "Expulsado de la rodada"
                        "group_removed" -> "Retirado del grupo"
                        else -> "Reporte cerrado ($state)"
                    }
                    _statusBanner.value = "⚖️ Moderación aplicada: $action (RULES R13.4)"
                },
                onFailure = { err ->
                    _statusBanner.value = "Error al resolver reporte: ${err.message}"
                }
            )
        }
    }

    fun proposeVote(
        groupId: String,
        proposal: String,
        targetId: String?,
        targetName: String?
    ) {
        viewModelScope.launch {
            val res = repository.proposeLeaderVote(groupId, proposal, targetId, targetName)
            res.fold(
                onSuccess = {
                    _statusBanner.value = "🗳️ Votación de líderes abierta (Requiere mayoría 2 de 3, RULES R14.2)"
                },
                onFailure = { err ->
                    _statusBanner.value = "Error al iniciar votación: ${err.message}"
                }
            )
        }
    }

    fun castVote(
        voteId: String,
        approve: Boolean
    ) {
        viewModelScope.launch {
            val res = repository.castLeaderVoteBallot(voteId, approve)
            res.fold(
                onSuccess = { state ->
                    val txt = if (state == "approved") "¡Aprobada por mayoría 2 de 3!" else "Voto registrado (en espera de mayoría)"
                    _statusBanner.value = "🗳️ $txt (RULES R14.2)"
                },
                onFailure = { err ->
                    _statusBanner.value = "Error al votar: ${err.message}"
                }
            )
        }
    }
}