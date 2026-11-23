package com.rutabikersync.app.feature.groups

import androidx.compose.runtime.LaunchedEffect

import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import com.rutabikersync.app.feature.qr.QrScannerHelper
import com.rutabikersync.app.feature.badges.GamificationProfileScreen
import com.rutabikersync.app.feature.rides.RideHistoryScreen
import com.rutabikersync.app.ui.theme.HeritageBrassGold
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.rutabikersync.app.core.debug.AppLogExporter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import com.rutabikersync.app.ui.components.HeritageBevelCard
import com.rutabikersync.app.ui.components.HeritageBrassButton
import com.rutabikersync.app.ui.components.HeritageEmblemBadge
import com.rutabikersync.app.ui.components.HeritageMetricCard
import com.rutabikersync.app.ui.components.HeritageAchievementBadge
import com.rutabikersync.app.ui.components.HeritageClubPatchBadge
import com.rutabikersync.app.R
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.components.skeleton.SkeletonCard
import com.rutabikersync.app.ui.components.skeleton.SkeletonCircle
import com.rutabikersync.app.ui.components.skeleton.SkeletonLine
import com.rutabikersync.app.ui.components.skeleton.SkeletonMemberRow
import com.rutabikersync.app.ui.components.skeleton.SkeletonProfileHeader
import com.rutabikersync.app.ui.components.skeleton.StateHost
import com.rutabikersync.app.ui.components.skeleton.UiState
import com.rutabikersync.app.ui.theme.BikerColors
import com.rutabikersync.app.core.uxtest.UxTestTracker

/**
 * Pestaña Grupo (SCREENS.md D1–D9) con soporte completo para StateHost y Skeletons (SKELETONS.md §7).
 */
@Composable
fun GroupsMainTabScreen(
    viewModel: Phase1ViewModel,
    onOpenParticipants: (routeId: String) -> Unit = {}
) {
    val groups by viewModel.repository.groups.collectAsState()
    val invitations by viewModel.repository.pendingInvitations.collectAsState()
    val currentUser by viewModel.repository.currentUser.collectAsState()
    val forceSkeleton by viewModel.forceSkeletonPreview.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val selectedInvitationId by viewModel.selectedInvitationId.collectAsState()
    val selectedMemberUserId by viewModel.selectedMemberUserId.collectAsState()
    val banner by viewModel.statusBanner.collectAsState()
    val rsvpPendingCount by viewModel.rsvpPendingCount.collectAsState()
    val showClubRoutes by viewModel.showClubRoutes.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Fase 5: vista informativa de convocatorias; la respuesta se da en PARTICIPANTES.
    if (showClubRoutes) {
        val routes by viewModel.clubRoutes.collectAsState()
        val nextRoute by viewModel.nextClubRoute.collectAsState()
        val rsvpByRoute by viewModel.clubRsvpByRoute.collectAsState()
        ClubRoutesScreen(
            groupName = groups.firstOrNull { it.id == selectedGroupId }?.name ?: "Mi Club",
            routes = routes,
            nextRoute = nextRoute,
            rsvpByRoute = rsvpByRoute,
            pendingCount = rsvpPendingCount,
            onOpenParticipants = onOpenParticipants,
            onBack = { viewModel.closeClubRoutes() }
        )
        return
    }

    val uiState = viewModel.buildGroupsUiState(
        groups = groups,
        invitations = invitations,
        user = currentUser,
        forceLoading = forceSkeleton
    )

    LaunchedEffect(Unit) {
        viewModel.syncWithBackend()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BikerColors.BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(id = R.string.groups_title),
            color = BikerColors.PrimaryAmber,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        val currentBanner = ClubRsvpUi.bannerMessage(banner, rsvpPendingCount)
        if (currentBanner != null) {
            val isPending = ClubRsvpUi.isPendingBanner(currentBanner)
            val isAlertOrWarning = isPending || currentBanner.startsWith("🛡️") || currentBanner.startsWith("Error") || currentBanner.contains("Estatuto") || currentBanner.contains("Rechazado")
            val bannerTextColor = if (isAlertOrWarning) Color(0xFFF5D061) else BikerColors.StatusGreen
            val bannerBorderColor = if (isAlertOrWarning) Color(0xFFD4AF37) else Color.Transparent

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF241C15), RoundedCornerShape(8.dp))
                    .border(1.dp, bannerBorderColor, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = currentBanner,
                    color = bannerTextColor,
                    fontSize = 13.sp,
                    fontWeight = if (isAlertOrWarning) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                // El aviso de pendientes no se puede cerrar: se retira solo al sincronizar.
                if (!isPending) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = null,
                        tint = BikerColors.TextSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { viewModel.clearBanner() }
                    )
                }
            }
        }

        StateHost(
            state = uiState,
            skeleton = {
                // SKELETONS.md §7: D1 Mis grupos -> 2 SkeletonCard
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonCard()
                    SkeletonCard()
                }
            },
            empty = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.TabGroups,
                        contentDescription = null,
                        tint = BikerColors.PrimaryAmber,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.groups_empty_title),
                        color = BikerColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = stringResource(id = R.string.groups_empty_desc),
                        color = BikerColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            },
            onRetry = {
                viewModel.repository.updateDevOverrides {
                    it.copy(
                        forceOffline = false,
                        forceServerError500 = false,
                        forceSubscriptionExpired = false
                    )
                }
                viewModel.setForceSkeletonPreview(false)
            }
        ) { data, _ ->
            val activeInvitation = data.pendingInvitations.find { it.id == selectedInvitationId }
            val activeGroup = data.groups.find { it.id == selectedGroupId }
                ?: data.groups.find { it.members.any { m -> m.userId == data.currentUser.id } }
                ?: data.groups.find { it.leaderIds.contains(data.currentUser.id) || it.principalLeaderId == data.currentUser.id }
                ?: if (selectedGroupId == "OVERVIEW") null else data.groups.firstOrNull()

            when {
                activeInvitation != null -> {
                    val domainInv = data.invitations.find { it.id == selectedInvitationId }
                    val invType = domainInv?.type ?: if (activeInvitation.statusKind == MemberStatusKind.GUEST) InvitationType.RODADA else InvitationType.HERMANDAD
                    val isAcceptingInvite by viewModel.isAcceptingInvite.collectAsState()
                    InvitationDetailScreenD2(
                        invitation = activeInvitation,
                        invitationType = invType,
                        isAccepting = isAcceptingInvite,
                        onAccept = { cMem, cLoc, cEmg, cNotif ->
                            viewModel.acceptInvite(activeInvitation.id, cMem, cLoc, cEmg, cNotif)
                        },
                        onReject = { viewModel.rejectInvite(activeInvitation.id) },
                        onBack = { viewModel.selectInvitation(null) }
                    )
                }
                activeGroup != null -> {
                    val activeMember = activeGroup.members.find { it.userId == selectedMemberUserId }
                    if (activeMember != null) {
                        MemberDetailScreenD4(
                            group = activeGroup,
                            member = activeMember,
                            isCurrentUserLeader = activeGroup.leaderIds.contains(data.currentUser.id) || activeGroup.principalLeaderId == data.currentUser.id || data.currentUser.accountType == AccountType.LEADER,
                            onUpdateMember = { newClass, newRole ->
                                viewModel.updateMemberClassAndRole(
                                    activeGroup.id,
                                    activeMember.userId,
                                    newClass,
                                    newRole
                                )
                            },
                            onExpelMember = {
                                viewModel.expelMember(
                                    activeGroup.id,
                                    activeMember.userId,
                                    context
                                )
                            },
                            onBack = { viewModel.selectMember(null) }
                        )
                    } else {
                        GroupDetailScreenD3(
                            group = activeGroup,
                            currentUser = data.currentUser,
                            invitations = data.invitations,
                            onCreateInvitation = { type ->
                                viewModel.createInvitation(type, activeGroup.id, activeGroup.name)
                            },
                            onSelectMember = { viewModel.selectMember(it) },
                            onInviteMember = { invitedId, statusKind ->
                                viewModel.sendInvite(activeGroup.id, invitedId, statusKind)
                            },
                            onVote = { voteId, approve ->
                                viewModel.voteOnSensitiveAction(activeGroup.id, voteId, approve)
                            },
                            onTransfer = { targetLeaderId ->
                                viewModel.startGroupTransfer(activeGroup.id, targetLeaderId)
                            },
                            onUpdateClubProfile = { colors, siglas, tradition, scope, region, motto, city, foundationDate ->
                                viewModel.updateClubProfile(activeGroup.id, colors, siglas, tradition, scope, region, motto, city, foundationDate)
                            },
                            onPickClubLogo = { uri ->
                                viewModel.updateClubLogoFromUri(context, activeGroup.id, uri)
                            },
                            onOpenRoutes = { viewModel.openClubRoutes(activeGroup.id) },
                            onAssignOfficer = { role, memberUserId ->
                                viewModel.updateDirectivaOfficer(activeGroup.id, role, memberUserId)
                            },
                            onBack = { viewModel.selectGroup("OVERVIEW") }
                        )
                    }
                }
                else -> {
                    GroupsOverviewScreenD1(
                        data = data,
                        onOpenInvitation = { viewModel.selectInvitation(it) },
                        onOpenGroup = { viewModel.selectGroup(it) },
                        onCreateGroup = { country, name, phash, tier ->
                            viewModel.createNewGroup(country, name, phash, tier = tier)
                        },
                        onJoinByQr = { payload ->
                            viewModel.joinByQrCode(payload)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupsOverviewScreenD1(
    data: GroupsScreenData,
    onOpenInvitation: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onCreateGroup: (String, String, String, ClubTier) -> Unit,
    onJoinByQr: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val isLeader = data.currentUser.accountType == AccountType.LEADER
    val hasNoClubs = data.groups.isEmpty()

    var showCreateForm by remember { mutableStateOf(false) }
    var showScanOptionForLeader by remember { mutableStateOf(false) }
    var manualInviteCode by remember { mutableStateOf("") }
    var newCountry by remember { mutableStateOf(data.currentUser.countryCode.ifBlank { "VE" }) }
    var newName by remember { mutableStateOf("") }
    var newStateRegion by remember { mutableStateOf("Miranda") }
    var newPhash by remember { mutableStateOf("123456789abcdef0") }
    var selectedTier by remember { mutableStateOf(ClubTier.MC) }

    // =========================================================================
    // CASO 1: LÍDER SIN CLUB (Acción dominante: Fundar Club)
    // =========================================================================
    if (isLeader && hasNoClubs) {
        val collisionPreview = data.groups
            .filter { it.countryCode.equals(newCountry.trim(), ignoreCase = true) }
            .firstNotNullOfOrNull { existing ->
                val c = ClubRulesEngine.checkGroupNameCollision(newName, existing.name)
                if (c.collides) "Colisión en ${existing.countryCode} con '${existing.name}' (${c.reason})" else null
            }

        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeritageEmblemBadge(
                    imageVector = AppIcons.TabGroups,
                    contentDescription = "Fundar Club",
                    badgeSize = 44.dp,
                    iconSize = 22.dp,
                    glowColor = Color(0xFFF3C766)
                )
                Column {
                    Text(
                        text = "Fundar tu Club Motero",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "Crea la hermandad oficial de tu convoy y convoca a tus pilotos",
                        color = Color(0xFF8E95A5),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Nombre del Club Motero") },
                placeholder = { Text("Ej: Águilas de la Montaña M.C.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (newName.isNotBlank()) {
                Text(
                    text = collisionPreview ?: "✓ Nombre disponible en $newCountry (normalizado: '${ClubRulesEngine.normalizeGroupName(newName)}')",
                    color = if (collisionPreview != null) BikerColors.StatusRed else BikerColors.StatusGreen,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = newCountry,
                    onValueChange = { if (it.length <= 2) newCountry = it.uppercase() },
                    label = { Text("País (ISO-2)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = newStateRegion,
                    onValueChange = { newStateRegion = it },
                    label = { Text("Estado / Región") },
                    singleLine = true,
                    modifier = Modifier.weight(1.5f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "CATEGORÍA BIKER TRADICIONAL",
                color = Color(0xFFD4AF37),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ClubTier.entries.forEach { tier ->
                    val isSelected = selectedTier == tier
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFFD4AF37) else Color(0xFF1E1712))
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFFD4AF37) else Color(0xFF8B6B3D).copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedTier = tier }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = tier.code,
                                color = if (isSelected) Color(0xFF1E1712) else Color(0xFFF5E6C8),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = when (tier) {
                                    ClubTier.RC -> "Racing/Riding"
                                    ClubTier.MF -> "Familia"
                                    ClubTier.MG -> "Grupo"
                                    ClubTier.MC -> "MotoClub"
                                },
                                color = if (isSelected) Color(0xFF1E1712) else Color(0xFF9E9285),
                                fontSize = 8.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Ficha de requisitos de la categoría seleccionada
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF140F0C))
                    .border(1.dp, Color(0xFF8B6B3D).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "🛡️ ${selectedTier.displayName} (${selectedTier.code})",
                        color = Color(0xFFF5D061),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "• Parche: ${selectedTier.patchDescription}",
                        color = Color(0xFFC7B8A5),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "• Cilindrada: ${selectedTier.displacementRequirement}",
                        color = Color(0xFFC7B8A5),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "• Prospecto: ${selectedTier.prospectPeriod}",
                        color = Color(0xFFC7B8A5),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "• Mínimo de miembros: ${selectedTier.minMembers} integrantes",
                        color = Color(0xFFC7B8A5),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "• Estructura: ${selectedTier.structureDescription}",
                        color = Color(0xFF9E9285),
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val canCreate = newName.trim().length >= 3 && collisionPreview == null
            HeritageBrassButton(
                text = "Registrar y Fundar Club (${selectedTier.code})",
                onClick = {
                    if (canCreate) {
                        UxTestTracker.logEvent(
                            screen = "GROUPS_D1",
                            action = "TAP_CREATE_GROUP",
                            target = "btn_create_group",
                            details = "name=$newName,country=$newCountry,tier=${selectedTier.code}"
                        )
                        onCreateGroup(newCountry, newName.trim(), newPhash, selectedTier)
                    } else if (newName.trim().length < 3) {
                        android.widget.Toast.makeText(context, "El nombre del club debe tener al menos 3 caracteres", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                icon = AppIcons.TabGroups
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Enlace sutil secundario para escanear QR de co-liderazgo
            Text(
                text = if (showScanOptionForLeader) "▲ Ocultar lector QR" else "¿Deseas unirte a otro club existente como Co-Líder?",
                color = Color(0xFFE5B869),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showScanOptionForLeader = !showScanOptionForLeader }
                    .padding(vertical = 6.dp, horizontal = 10.dp)
            )

            if (showScanOptionForLeader) {
                Spacer(modifier = Modifier.height(8.dp))
                HeritageBrassButton(
                    text = "Escanear QR de Co-Liderazgo",
                    onClick = {
                        if (activity != null) {
                            QrScannerHelper.startNativeQrScan(
                                activity = activity,
                                onSuccess = { rawCode ->
                                    UxTestTracker.logEvent("GROUPS_D1", "SCAN_QR_SUCCESS", "google_code_scanner", "payload=$rawCode")
                                    onJoinByQr(rawCode)
                                },
                                onCancelled = {
                                    UxTestTracker.logEvent("GROUPS_D1", "SCAN_QR_CANCELLED", "google_code_scanner", "user_cancelled")
                                },
                                onError = { err ->
                                    android.widget.Toast.makeText(context, "Escaneo: $err", android.widget.Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    icon = AppIcons.QrScanner
                )
            }
        }
    }

    // =========================================================================
    // CASO 2: MIEMBRO SIN CLUB (Acción dominante: Unirse con QR)
    // =========================================================================
    if (!isLeader && hasNoClubs) {
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeritageEmblemBadge(
                    imageVector = AppIcons.QrScanner,
                    contentDescription = null,
                    badgeSize = 44.dp,
                    iconSize = 22.dp,
                    glowColor = Color(0xFFF3C766)
                )
                Column {
                    Text(
                        text = "Unirse a un Club Motero",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "Escanea el código QR que te proyecte tu líder para ingresar",
                        color = Color(0xFF8E95A5),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            HeritageBrassButton(
                text = "Escanear Código QR Oficial",
                onClick = {
                    UxTestTracker.logEvent("GROUPS_D1", "OPEN_QR_SCANNER", "btn_escanear_qr")
                    if (activity != null) {
                        QrScannerHelper.startNativeQrScan(
                            activity = activity,
                            onSuccess = { rawCode ->
                                UxTestTracker.logEvent("GROUPS_D1", "SCAN_QR_SUCCESS", "google_code_scanner", "payload=$rawCode")
                                onJoinByQr(rawCode)
                            },
                            onCancelled = {
                                UxTestTracker.logEvent("GROUPS_D1", "SCAN_QR_CANCELLED", "google_code_scanner", "user_cancelled")
                            },
                            onError = { err ->
                                android.widget.Toast.makeText(context, "Escaneo: $err", android.widget.Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                },
                icon = AppIcons.QrScanner
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Fallback manual alfanumérico
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualInviteCode,
                    onValueChange = { manualInviteCode = it },
                    label = { Text("Código de invitación") },
                    placeholder = { Text("Pega el código o UUID de invitación") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (manualInviteCode.isNotBlank()) {
                            UxTestTracker.logEvent("GROUPS_D1", "JOIN_CODE_FALLBACK", "btn_unirse_codigo", "code=$manualInviteCode")
                            onJoinByQr(manualInviteCode)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BikerColors.StatusGreen)
                ) {
                    Text("Unirse", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // =========================================================================
    // INVITACIONES PENDIENTES (Si existen)
    // =========================================================================
    if (data.pendingInvitations.isNotEmpty()) {
        Text(
            text = stringResource(id = R.string.groups_pending_invites),
            color = BikerColors.StatusYellow,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        data.pendingInvitations.forEach { inv ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                    .clickable { onOpenInvitation(inv.id) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${inv.groupName} (${inv.countryCode})",
                        color = BikerColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Estatus propuesto: ${inv.statusKind.apiValue.uppercase()} · $${inv.monthlyCostUsd}/mes",
                        color = BikerColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = { onOpenInvitation(inv.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BikerColors.PrimaryAmber,
                        contentColor = BikerColors.BackgroundDark
                    )
                ) {
                    Text("Ver", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // =========================================================================
    // LISTA DE GRUPOS ACTIVOS (Si ya pertenece a un club)
    // =========================================================================
    if (data.groups.isNotEmpty()) {
        data.groups.forEach { grp ->
            val myMembership = grp.members.find { it.userId == data.currentUser.id }
            val isLeaderInGroup = grp.leaderIds.contains(data.currentUser.id)
            val isFounder = grp.principalLeaderId == data.currentUser.id

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                    .clickable { onOpenGroup(grp.id) }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(BikerColors.SkeletonBaseDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = grp.name.take(2).uppercase(),
                                color = BikerColors.PrimaryAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = grp.name,
                                    color = BikerColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "[${grp.countryCode}]",
                                    color = BikerColors.PrimaryAmber,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (isFounder) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        painter = painterResource(id = AppIcons.FounderFlameRes),
                                        contentDescription = "Fundador",
                                        tint = BikerColors.PrimaryAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else if (isLeaderInGroup) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        painter = painterResource(id = AppIcons.LeaderCrownShieldRes),
                                        contentDescription = "Líder",
                                        tint = BikerColors.PrimaryAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${grp.members.size} miembros · ${grp.stateRegion}",
                                color = BikerColors.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    PaymentSemaphoreBadge(
                        semaphore = myMembership?.paymentSemaphore ?: PaymentSemaphore.GREEN
                    )
                }
            }
        }

        // Opción secundaria para líderes con club: crear extensión/capítulo si lo desean
        if (isLeader) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showCreateForm = !showCreateForm },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE5B869))
            ) {
                Text(if (showCreateForm) "Cerrar formulario" else "+ Crear Capítulo o Extensión de Club")
            }

            if (showCreateForm) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Nueva Extensión Oficial de Club",
                        color = BikerColors.PrimaryAmber,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = newCountry,
                        onValueChange = { if (it.length <= 2) newCountry = it.uppercase() },
                        label = { Text("País ISO-2 (ej. MX, CO, VE)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nombre del capítulo") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (newName.length >= 3) {
                                onCreateGroup(newCountry, newName, newPhash, ClubTier.MC)
                                showCreateForm = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BikerColors.PrimaryAmber,
                            contentColor = BikerColors.BackgroundDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Registrar Capítulo", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * D2. Invitación (detalle) con bloque de costo $2.50/mes y 4 consentimientos separados (RULES R2.1, R15.1).
 */
@Composable
private fun InvitationDetailScreenD2(
    invitation: ClubInvitationModel,
    invitationType: InvitationType = if (invitation.statusKind == MemberStatusKind.GUEST) InvitationType.RODADA else InvitationType.HERMANDAD,
    isAccepting: Boolean = false,
    onAccept: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onReject: () -> Unit,
    onBack: () -> Unit
) {
    var consentMembership by remember { mutableStateOf(true) }
    var consentLocation by remember { mutableStateOf(true) }
    var consentEmergency by remember { mutableStateOf(true) }
    var consentNotifications by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.Back,
                contentDescription = "Atrás",
                tint = BikerColors.TextPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Invitación a ${invitation.groupName} (${invitation.countryCode})",
                color = BikerColors.PrimaryAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Text(
            text = "Estatus propuesto: ${invitation.statusKind.apiValue.uppercase()}",
            color = BikerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(id = R.string.invite_cost_notice),
            color = BikerColors.StatusYellow,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        HorizontalDivider(color = BikerColors.SkeletonBaseDark)

        val membershipLabel = if (invitationType == InvitationType.RODADA) {
            stringResource(id = R.string.consent_ride_participation)
        } else {
            stringResource(id = R.string.consent_membership)
        }

        ConsentCheckboxRow(
            checked = consentMembership,
            onCheckedChange = { consentMembership = it },
            label = membershipLabel
        )
        ConsentCheckboxRow(
            checked = consentLocation,
            onCheckedChange = { consentLocation = it },
            label = stringResource(id = R.string.consent_location)
        )
        ConsentCheckboxRow(
            checked = consentEmergency,
            onCheckedChange = { consentEmergency = it },
            label = stringResource(id = R.string.consent_emergency)
        )
        ConsentCheckboxRow(
            checked = consentNotifications,
            onCheckedChange = { consentNotifications = it },
            label = stringResource(id = R.string.consent_notifications)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(id = R.string.action_reject_invite), color = BikerColors.StatusRed)
            }
            Button(
                onClick = {
                    if (!isAccepting) {
                        UxTestTracker.logEvent(
                            screen = "INVITATION_D2",
                            action = "TAP_ACCEPT_INVITE",
                            target = "btn_accept",
                            details = "inviteId=${invitation.id.take(8)}"
                        )
                        onAccept(
                            consentMembership,
                            consentLocation,
                            consentEmergency,
                            consentNotifications
                        )
                    }
                },
                enabled = !isAccepting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BikerColors.PrimaryAmber,
                    contentColor = BikerColors.BackgroundDark,
                    disabledContainerColor = BikerColors.PrimaryAmber.copy(alpha = 0.6f),
                    disabledContentColor = BikerColors.BackgroundDark
                ),
                modifier = Modifier.weight(1f)
            ) {
                if (isAccepting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = BikerColors.BackgroundDark,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verificando...", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                } else {
                    val buttonText = if (invitationType == InvitationType.RODADA) {
                        stringResource(id = R.string.action_accept_invite)
                    } else {
                        "Aceptar y Unirme"
                    }
                    Text(buttonText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ConsentCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = BikerColors.TextPrimary, fontSize = 13.sp)
    }
}

private data class DirectivaOfficer(
    val role: ClubRole,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val badgeRes: Int? = null,
    val officerName: String?
)

@Composable
private fun DirectivaOfficerCard(
    officer: DirectivaOfficer,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    HeritageBevelCard(
        modifier = modifier
            .heightIn(min = 68.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        cardColor = Color(0xFF1F1A16),
        borderColor = if (officer.officerName != null) Color(0xFFD4AF37).copy(alpha = 0.6f) else Color(0xFF8B6B3D).copy(alpha = 0.4f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF3E2D1E),
                                Color(0xFF1E1712)
                            )
                        )
                    )
                    .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (officer.badgeRes != null) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = officer.badgeRes),
                        contentDescription = officer.title,
                        modifier = Modifier.size(34.dp)
                    )
                } else {
                    Icon(
                        imageVector = officer.icon,
                        contentDescription = officer.title,
                        tint = Color(0xFFF5D061),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = officer.title.uppercase(),
                    color = Color(0xFFD4AF37),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = officer.officerName ?: "Sin asignar · Toca para asignar",
                    color = if (officer.officerName != null) Color(0xFFF5E6C8) else Color(0xFF8C827A),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun HeritagePaymentPill(semaphore: PaymentSemaphore) {
    val (label, textColor, bgColor, dotColor) = when (semaphore) {
        PaymentSemaphore.GREEN -> Quadruple("Al día", Color(0xFF34D399), Color(0xFF064E3B).copy(alpha = 0.6f), Color(0xFF10B981))
        PaymentSemaphore.YELLOW -> Quadruple("En gracia", Color(0xFFFBBF24), Color(0xFF78350F).copy(alpha = 0.6f), Color(0xFFF59E0B))
        PaymentSemaphore.RED -> Quadruple("Pendiente", Color(0xFFF87171), Color(0xFF7F1D1D).copy(alpha = 0.6f), Color(0xFFEF4444))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, dotColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * D3/D5/D6/D8/D9. Vista principal del club Heritage Premium (Pantalla 2):
 * Cresta de club, Mesa Directiva de 6 cargos oficiales (RULES R3.1 / D46),
 * Código de Admisión QR a pantalla completa y listado de miembros con semáforos de pago.
 */
/**
 * Sub-pestañas canónicas dentro de "Mi Club" (Fase 2).
 */
private enum class ClubSubTab(val label: String, val icon: String) {
    MIEMBROS("Miembros", "👥"),
    INVITACIONES("Invitaciones", "📜"),
    MESA("Mesa Directiva", "⚖️")
}

/**
 * Estructura de evaluación canónica para determinar si un club cumple con
 * los estatutos tradicionales de antigüedad para postularse o pertenecer
 * a la Mesa Directiva Regional o Nacional de cualquier país.
 */
data class DirectivaEligibility(
    val years: Int,
    val isNationalEligible: Boolean,
    val isRegionalEligible: Boolean,
    val statusBadge: String,
    val detailText: String
)

private fun formatFoundationDate(dateStr: String): String {
    return try {
        val clean = dateStr.trim().substringBefore("T")
        val parts = clean.split("-")
        if (parts.size >= 3) {
            "${parts[2]}/${parts[1]}/${parts[0]}"
        } else clean
    } catch (_: Exception) {
        dateStr
    }
}

private fun evaluateDirectivaEligibility(dateStr: String): DirectivaEligibility {
    val years = try {
        val parts = dateStr.trim().split("-")
        val year = parts[0].toInt()
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        (currentYear - year).coerceAtLeast(0)
    } catch (_: Exception) {
        0
    }

    val isNational = years >= 5
    val isRegional = years >= 3
    val (badge, detail) = when {
        isNational -> "🏛️ Apto Mesa Directiva Nacional y Regional" to "Cumple con el requisito canónico de antigüedad (≥ 5 años de rodadas y trayectoria comprobada)."
        isRegional -> "🏛️ Apto Mesa Directiva Regional" to "Cumple con el requisito para Mesa Regional (≥ 3 años). Para Mesa Nacional requiere ≥ 5 años."
        else -> "🌱 En Consolidación (< 3 años)" to "Club con $years año(s) de fundado. Requiere mínimo 3 años de antigüedad para postularse a la Mesa Directiva."
    }
    return DirectivaEligibility(years, isNational, isRegional, badge, detail)
}

private fun resolveCountryDisplay(code: String): String {
    return when (code.uppercase().trim()) {
        "VE" -> "🇻🇪 Venezuela"
        "CO" -> "🇨🇴 Colombia"
        "MX" -> "🇲🇽 México"
        "AR" -> "🇦🇷 Argentina"
        "CL" -> "🇨🇱 Chile"
        "PE" -> "🇵🇪 Perú"
        "EC" -> "🇪🇨 Ecuador"
        "BR" -> "🇧🇷 Brasil"
        "ES" -> "🇪🇸 España"
        "US" -> "🇺🇸 USA"
        else -> code.uppercase()
    }
}

/**
 * D3. Detalle y Gestión de Mi Club (Heritage Elite).
 * Estructura limpia de 3 bloques principales + Sub-pestañas (Miembros | Invitaciones | Mesa).
 */
@Composable
private fun GroupDetailScreenD3(
    group: ClubGroupModel,
    currentUser: UserProfileModel,
    invitations: List<Invitation> = emptyList(),
    onCreateInvitation: (InvitationType) -> Invitation = { _ -> error("Not implemented") },
    onSelectMember: (String) -> Unit,
    onInviteMember: (String, MemberStatusKind) -> Unit,
    onVote: (String, Boolean) -> Unit,
    onTransfer: (String) -> Unit,
    onUpdateClubProfile: (colors: String, siglas: String, tradition: String, scope: String, region: String, motto: String, city: String?, foundationDate: String) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onPickClubLogo: (Uri) -> Unit = {},
    onAssignOfficer: (role: ClubRole, memberUserId: String) -> Unit = { _, _ -> },
    onOpenRoutes: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onPickClubLogo(uri)
        }
    }

    val logoBitmap = remember(group.logoCustomUri) {
        group.logoCustomUri?.let { path ->
            try {
                if (path.startsWith("data:image") || (path.length > 200 && !path.startsWith("/") && !path.startsWith("content:"))) {
                    val base64Str = if (path.contains(",")) path.substringAfter(",") else path
                    val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)?.asImageBitmap()
                } else {
                    val file = File(path)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    } else {
                        val uri = Uri.parse(path)
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                        }
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    val eligibility = remember(group.foundationDate) {
        evaluateDirectivaEligibility(group.foundationDate)
    }

    val myMember = group.members.find { it.userId == currentUser.id }
    val isLeader = group.leaderIds.contains(currentUser.id)
    val isPrincipalLeader = group.principalLeaderId == currentUser.id
    val canSeePaymentSemaphore = isLeader || myMember?.clubRole == ClubRole.TREASURER || myMember?.clubRole == ClubRole.PRESIDENT || myMember?.clubRole == ClubRole.VICE_PRESIDENT

    // Invitar miembro formal por Hermandad (Restringido estrictamente a Presidente y Vicepresidente, RULES R3.2)
    val canShareHermandad = GroupInviteQrHelper.canShareQr(isLeader, myMember?.clubRole)

    var selectedSubTab by remember { mutableStateOf(ClubSubTab.MIEMBROS) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var assigningOfficerRole by remember { mutableStateOf<ClubRole?>(null) }
    var activeInvitationType by remember { mutableStateOf(InvitationType.HERMANDAD) }
    var currentGeneratedInvite by remember { mutableStateOf<Invitation?>(null) }

    val officers = remember(group.members, group.principalLeaderId) {
        val presidentMem = group.members.find { it.clubRole == ClubRole.PRESIDENT }
            ?: group.members.find { it.userId == group.principalLeaderId }
            ?: group.members.find { it.isLeader && it.isFounder }
            ?: group.members.firstOrNull { it.isLeader }

        listOf(
            DirectivaOfficer(
                role = ClubRole.PRESIDENT,
                title = "Presidente",
                icon = AppIcons.RolePresident,
                badgeRes = R.drawable.ic_officer_presidente,
                officerName = presidentMem?.displayName
            ),
            DirectivaOfficer(
                role = ClubRole.VICE_PRESIDENT,
                title = "Vicepresidente",
                icon = AppIcons.RoleVicePresident,
                badgeRes = R.drawable.ic_officer_vicepresidente,
                officerName = group.members.find { it.clubRole == ClubRole.VICE_PRESIDENT }?.displayName
            ),
            DirectivaOfficer(
                role = ClubRole.SERGEANT_AT_ARMS,
                title = "Sargento de Armas",
                icon = AppIcons.RoleSergeantAtArms,
                badgeRes = R.drawable.ic_officer_sargento_armas,
                officerName = group.members.find { it.clubRole == ClubRole.SERGEANT_AT_ARMS }?.displayName
            ),
            DirectivaOfficer(
                role = ClubRole.ROAD_CAPTAIN,
                title = "Capitán de Ruta",
                icon = AppIcons.RoleRoadCaptain,
                badgeRes = R.drawable.ic_officer_capitan_ruta,
                officerName = group.members.find { it.clubRole == ClubRole.ROAD_CAPTAIN }?.displayName
            ),
            DirectivaOfficer(
                role = ClubRole.SECRETARY,
                title = "Secretario",
                icon = AppIcons.RoleSecretary,
                badgeRes = R.drawable.ic_officer_secretario,
                officerName = group.members.find { it.clubRole == ClubRole.SECRETARY }?.displayName
            ),
            DirectivaOfficer(
                role = ClubRole.TREASURER,
                title = "Tesorero",
                icon = AppIcons.RoleTreasurer,
                badgeRes = R.drawable.ic_officer_tesorero,
                officerName = group.members.find { it.clubRole == ClubRole.TREASURER }?.displayName
            )
        )
    }

    val presidentOfficer = officers.find { it.role == ClubRole.PRESIDENT }
    val assignedOfficersCount = officers.count { it.officerName != null }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Cabecera Oficial: Escudo Dorado "MI CLUB" + Botón Vista General / Otros Grupos
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HeritageEmblemBadge(
                    imageVector = AppIcons.TabGroups,
                    badgeSize = 32.dp,
                    contentDescription = "Mi Club"
                )
                Text(
                    text = "MI CLUB",
                    color = Color(0xFFF5E6C8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 1.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenRoutes,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFD4AF37)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF5D061)),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Ver Rutas", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF241C15))
                        .border(1.dp, Color(0xFF8B6B3D).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Ver otros clubes",
                        tint = Color(0xFFD4AF37),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // =========================================================================
        // BLOQUE 1: TARJETA INSIGNIA (HERO CARD MC)
        // =========================================================================
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            cardColor = Color(0xFF1F1A16),
            borderColor = Color(0xFFD4AF37).copy(alpha = 0.6f),
            contentPadding = PaddingValues(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // 1. Sello de Parche y Categoría dorado superior derecho (ej. RC · Racing Club)
                val patchSiglas = group.clubSiglas.ifBlank { group.tier.code }
                val patchCategoryName = when (patchSiglas) {
                    "MC" -> "Moto Club"
                    "MG" -> "Moto Grupo"
                    "MF" -> "Moto Familia"
                    "RC" -> "Racing Club"
                    else -> group.tier.code
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                        .background(Color(0xFF2C2216), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = patchSiglas,
                            color = Color(0xFFF5D061),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "· $patchCategoryName",
                            color = Color(0xFFB8A99A),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Logo Central editable por el líder (Parche & Escudo oficial)
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .clickable(enabled = isLeader || isPrincipalLeader) {
                                photoPickerLauncher.launch("image/*")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (logoBitmap != null) {
                            Image(
                                bitmap = logoBitmap,
                                contentDescription = group.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .border(
                                        width = 2.dp,
                                        brush = Brush.sweepGradient(
                                            listOf(
                                                Color(0xFFF6CE74),
                                                Color(0xFF8A550C),
                                                Color(0xFFF6CE74),
                                                Color(0xFFB8781B),
                                                Color(0xFFF6CE74)
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                            )
                        } else {
                            HeritageEmblemBadge(
                                painter = painterResource(id = R.drawable.ic_logo_ruta_biker_sync),
                                badgeSize = 72.dp,
                                iconSize = 54.dp,
                                contentDescription = group.name
                            )
                        }
                        if (isLeader || isPrincipalLeader) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2C2216))
                                    .border(1.dp, Color(0xFFD4AF37), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Cambiar Escudo/Logo",
                                    tint = Color(0xFFF5D061),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }

                    // 2. Nombre del Club
                    Text(
                        text = group.name.uppercase(),
                        color = Color(0xFFF5E6C8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center
                    )

                    // Origen y Trayectoria (1 sola línea limpia)
                    Text(
                        text = "${resolveCountryDisplay(group.countryCode)}${if (!group.city.isNullOrBlank()) " (${group.city})" else ""} · Fundado ${formatFoundationDate(group.foundationDate)} (${eligibility.years} años)",
                        color = Color(0xFF9E9285),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // 3. Estatus Institucional: Insignia de Aval de Mesa Directiva
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (eligibility.isNationalEligible) Color(0xFF142918)
                                else if (eligibility.isRegionalEligible) Color(0xFF282414)
                                else Color(0xFF241D17)
                            )
                            .border(
                                1.dp,
                                if (eligibility.isNationalEligible) Color(0xFF4ADE80).copy(alpha = 0.7f)
                                else if (eligibility.isRegionalEligible) Color(0xFFFACC15).copy(alpha = 0.7f)
                                else Color(0xFF8B6B3D).copy(alpha = 0.5f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = eligibility.statusBadge,
                            color = if (eligibility.isNationalEligible) Color(0xFF86EFAC)
                                   else if (eligibility.isRegionalEligible) Color(0xFFFDE047)
                                   else Color(0xFFD4AF37),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Fila de Chips Secundarios: Colores y Jurisdicción (cápsulas compactas)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Chip 1: Colores Oficiales (sin la palabra "Colores:")
                        if (group.colorsDescription.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF2A2016))
                                    .border(1.dp, Color(0xFF8B6B3D).copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                    .clickable(enabled = isLeader || isPrincipalLeader) { showEditProfileDialog = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "🎨 ${group.colorsDescription}",
                                    color = Color(0xFFF5D061),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Chip 2: Capítulo / Alcance con pin de ubicación 📍
                        val scopeDisplay = group.directivaScope.ifBlank { "Capítulo Autónomo Local" }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF241C16))
                                .border(1.dp, Color(0xFF8B6B3D).copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                .clickable(enabled = isLeader || isPrincipalLeader) { showEditProfileDialog = true }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "📍 $scopeDisplay",
                                color = Color(0xFFD4AF37),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // 4. Lema Oficial en cursiva suave
                    if (group.motto.isNotBlank()) {
                        Text(
                            text = "«${group.motto}»",
                            color = Color(0xFFE5C07B).copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                        )
                    }

                    // 5. Botón de Acción Sutil y estilizado
                    if (isLeader || isPrincipalLeader) {
                        Spacer(modifier = Modifier.height(2.dp))
                        OutlinedButton(
                            onClick = { showEditProfileDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF241C15).copy(alpha = 0.4f),
                                contentColor = Color(0xFFC7B8A5)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF8B6B3D).copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = Color(0xFFD4AF37)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Editar Identidad y Perfil del Club",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE5D5C5)
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // BLOQUE 2: MESA DIRECTIVA COMPACTA
        // =========================================================================
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            cardColor = Color(0xFF1B1612),
            borderColor = Color(0xFF8B6B3D).copy(alpha = 0.4f),
            contentPadding = PaddingValues(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    HeritageEmblemBadge(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_officer_presidente),
                        badgeSize = 36.dp,
                        iconSize = 24.dp,
                        contentDescription = "Presidente"
                    )
                    Column {
                        Text(
                            text = "PRESIDENTE",
                            color = Color(0xFFD4AF37),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = presidentOfficer?.officerName ?: "Sin asignar",
                            color = Color(0xFFF5E6C8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Chip de acceso rápido a la sub-pestaña de la Mesa
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF261D16))
                        .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .clickable { selectedSubTab = ClubSubTab.MESA }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$assignedOfficersCount/6 Oficiales",
                        color = Color(0xFFF5D061),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // =========================================================================
        // BLOQUE 3: ACCIONES DESTACADAS DE INVITACIÓN (HERMANDAD Y RODADA)
        // =========================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Acción 1: Invitación de Hermandad (Membresía formal)
            Button(
                onClick = {
                    if (canShareHermandad) {
                        activeInvitationType = InvitationType.HERMANDAD
                        currentGeneratedInvite = onCreateInvitation(InvitationType.HERMANDAD)
                        showQrDialog = true
                    }
                },
                enabled = canShareHermandad,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD4AF37),
                    contentColor = Color(0xFF1E1712),
                    disabledContainerColor = Color(0xFF2A221A),
                    disabledContentColor = Color(0xFF6B5C4D)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Text(
                    text = "🛡️ Hermandad",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            // Acción 2: Invitación a Rodar (Convocatoria abierta y libre)
            Button(
                onClick = {
                    activeInvitationType = InvitationType.RODADA
                    currentGeneratedInvite = onCreateInvitation(InvitationType.RODADA)
                    showQrDialog = true
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF382A1E),
                    contentColor = Color(0xFFF5E6C8)
                ),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD4AF37).copy(alpha = 0.5f)),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Text(
                    text = "🏍️ Rodada",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        // =========================================================================
        // SELECTOR DE SUB-PESTAÑAS CANÓNICO (Miembros | Invitaciones | Mesa)
        // =========================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1B1612), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF8B6B3D).copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ClubSubTab.values().forEach { tab ->
                val isSelected = selectedSubTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFFD4AF37) else Color.Transparent)
                        .clickable { selectedSubTab = tab }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val countBadge = when (tab) {
                        ClubSubTab.MIEMBROS -> " (${group.members.size})"
                        ClubSubTab.INVITACIONES -> " (${invitations.filter { it.clubId == group.id }.size})"
                        ClubSubTab.MESA -> ""
                    }
                    Text(
                        text = "${tab.icon} ${tab.label}$countBadge",
                        color = if (isSelected) Color(0xFF1E1712) else Color(0xFF9E9285),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // =========================================================================
        // CONTENIDO DE LAS SUB-PESTAÑAS
        // =========================================================================
        when (selectedSubTab) {
            ClubSubTab.MIEMBROS -> {
                // Orden jerárquico motero: Presidente -> Oficiales -> Full Patch -> Prospect / Guest
                val sortedMembers = remember(group.members) {
                    group.members.sortedWith(
                        compareBy<ClubMemberModel> { it.clubRole != ClubRole.PRESIDENT }
                            .thenBy { it.clubRole == null }
                            .thenBy { it.statusKind != MemberStatusKind.FULL }
                            .thenBy { it.displayName }
                    )
                }

                sortedMembers.forEach { member ->
                    val isPresident = member.clubRole == ClubRole.PRESIDENT
                    val isOfficer = member.clubRole != null
                    val roleLabel = when {
                        isPresident -> "Presidente · Fundador"
                        isOfficer -> "${member.clubRole?.name?.replace("_", " ")} · Mesa"
                        member.statusKind == MemberStatusKind.FULL -> "Full Patch"
                        member.statusKind == MemberStatusKind.PROSPECT -> "Aspirante (Prospect)"
                        else -> "Guest · Invitado"
                    }

                    HeritageBevelCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectMember(member.userId) },
                        cardColor = Color(0xFF1E1712),
                        borderColor = if (isPresident) Color(0xFFD4AF37) else Color(0xFF8B6B3D).copy(alpha = 0.3f),
                        contentPadding = PaddingValues(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(if (isPresident) Color(0xFF2C2216) else Color(0xFF171310))
                                        .border(1.dp, if (isPresident) Color(0xFFD4AF37) else Color(0xFF8B6B3D), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = member.displayName.take(2).uppercase(),
                                        color = if (isPresident) Color(0xFFF5D061) else Color(0xFFC7B8A5),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = member.displayName,
                                            color = Color(0xFFF5E6C8),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        if (member.isFounder) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(id = AppIcons.FounderFlameRes),
                                                contentDescription = "Fundador",
                                                tint = Color(0xFFF5D061),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = roleLabel,
                                        color = Color(0xFF9E9285),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            if (canSeePaymentSemaphore) {
                                PaymentSemaphoreBadge(semaphore = member.paymentSemaphore)
                            }
                        }
                    }
                }
            }

            ClubSubTab.INVITACIONES -> {
                val clubInvitations = invitations.filter { it.clubId == group.id }
                if (clubInvitations.isEmpty()) {
                    HeritageBevelCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = AppIcons.QrCode,
                                contentDescription = null,
                                tint = Color(0xFF8B6B3D),
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "Sin invitaciones emitidas",
                                color = Color(0xFFF5E6C8),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Genera una Invitación de Hermandad o a Rodar con los botones superiores",
                                color = Color(0xFF9E9285),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    clubInvitations.forEach { inv ->
                        val (statusColor, statusLabel) = when (inv.status) {
                            InvitationStatus.PENDING -> Color(0xFFF5D061) to "PENDIENTE"
                            InvitationStatus.ACCEPTED -> BikerColors.StatusGreen to "ACEPTADA"
                            InvitationStatus.REJECTED -> BikerColors.StatusRed to "RECHAZADA"
                            InvitationStatus.EXPIRED -> Color(0xFF9E9285) to "EXPIRADA"
                        }

                        HeritageBevelCard(
                            modifier = Modifier.fillMaxWidth(),
                            cardColor = Color(0xFF1A1512),
                            borderColor = Color(0xFF8B6B3D).copy(alpha = 0.3f),
                            contentPadding = PaddingValues(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (inv.type == InvitationType.HERMANDAD) "🛡️ Hermandad" else "🏍️ Rodada",
                                            color = Color(0xFFF5E6C8),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "· ${inv.id}",
                                            color = Color(0xFF9E9285),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        text = if (inv.inviteeName != null) "Aceptada por: ${inv.inviteeName}" else "Emitida por: ${inv.inviterName}",
                                        color = Color(0xFF9E9285),
                                        fontSize = 11.sp
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(statusColor.copy(alpha = 0.15f))
                                        .border(1.dp, statusColor.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = statusLabel,
                                        color = statusColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ClubSubTab.MESA -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "⚖️ Mesa Directiva · 6 Cargos Canónicos MC",
                        color = Color(0xFFD4AF37),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = if (isLeader || isPrincipalLeader) "Toca cualquier cargo para asignar o relevar al oficial (Requiere Full Patch o Prospecto según Estatuto P7):" else "Oficiales de la mesa de gobierno del club:",
                        color = Color(0xFF9E9285),
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DirectivaOfficerCard(
                            officer = officers[0],
                            modifier = Modifier.weight(1f),
                            onClick = { if (isLeader || isPrincipalLeader) assigningOfficerRole = officers[0].role }
                        )
                        DirectivaOfficerCard(
                            officer = officers[1],
                            modifier = Modifier.weight(1f),
                            onClick = { if (isLeader || isPrincipalLeader) assigningOfficerRole = officers[1].role }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DirectivaOfficerCard(
                            officer = officers[2],
                            modifier = Modifier.weight(1f),
                            onClick = { if (isLeader || isPrincipalLeader) assigningOfficerRole = officers[2].role }
                        )
                        DirectivaOfficerCard(
                            officer = officers[3],
                            modifier = Modifier.weight(1f),
                            onClick = { if (isLeader || isPrincipalLeader) assigningOfficerRole = officers[3].role }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DirectivaOfficerCard(
                            officer = officers[4],
                            modifier = Modifier.weight(1f),
                            onClick = { if (isLeader || isPrincipalLeader) assigningOfficerRole = officers[4].role }
                        )
                        DirectivaOfficerCard(
                            officer = officers[5],
                            modifier = Modifier.weight(1f),
                            onClick = { if (isLeader || isPrincipalLeader) assigningOfficerRole = officers[5].role }
                        )
                    }
                }
            }
        }

        // =========================================================================
        // MODAL PROYECTOR DE QR CANÓNICO (ALTO CONTRASTE)
        // =========================================================================
        if (showQrDialog) {
            val inviteId = currentGeneratedInvite?.id ?: "RUTA-TEST-01"
            val president = group.members.find { it.clubRole == ClubRole.PRESIDENT }
            val leaderName = president?.displayName ?: "Capitán Oscar"
            val leaderId = president?.userId ?: group.principalLeaderId

            val qrPayload = remember(inviteId, activeInvitationType) {
                GroupInviteQrHelper.encodePayload(
                    GroupInviteQrHelper.InviteQrPayload(
                        groupId = group.id,
                        inviteCode = inviteId,
                        groupName = group.name,
                        countryCode = group.countryCode,
                        presidentName = leaderName,
                        founderId = leaderId,
                        intentType = if (activeInvitationType == InvitationType.HERMANDAD) GroupInviteQrHelper.QrIntentType.MEMBER else GroupInviteQrHelper.QrIntentType.RIDE_INVITE
                    )
                )
            }

            val qrBitmap = remember(qrPayload) {
                runCatching { GroupInviteQrHelper.generateQrBitmap(qrPayload, 512) }.getOrNull()
            }

            AlertDialog(
                onDismissRequest = { showQrDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HeritageEmblemBadge(
                            imageVector = AppIcons.TabGroups,
                            badgeSize = 32.dp,
                            contentDescription = null
                        )
                        Column {
                            Text(
                                text = if (activeInvitationType == InvitationType.HERMANDAD) "INVITACIÓN DE HERMANDAD" else "INVITACIÓN A RODAR",
                                color = Color(0xFFD4AF37),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = group.name,
                                color = Color(0xFFF5E6C8),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(230.dp)
                                    .background(Color.White, RoundedCornerShape(12.dp))
                                    .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Código QR Oficial",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "CÓDIGO OFICIAL",
                                color = Color(0xFF9E9285),
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = inviteId,
                                color = Color(0xFFF5D061),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                letterSpacing = 2.sp
                            )
                        }

                        Text(
                            text = if (activeInvitationType == InvitationType.HERMANDAD)
                                "Apunta la cámara de Ruta Biker para unirte a la hermandad (Sujeto a R2.2: membresía única)."
                            else
                                "Invitación abierta para rodar juntos como convoy amigo (R2.3: Libre y gratuita).",
                            color = Color(0xFFC7B8A5),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                },
                confirmButton = {
                    HeritageBrassButton(
                        text = "Cerrar",
                        onClick = { showQrDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    )
                },
                containerColor = Color(0xFF1E1712),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // =========================================================================
        // MODAL 1: FORMULARIO DE PERFIL E IDENTIDAD DEL CLUB (COLORES, SIGLAS, TRADICIÓN, MESA)
        // =========================================================================
        if (showEditProfileDialog) {
            ClubProfileEditDialog(
                group = group,
                onDismiss = { showEditProfileDialog = false },
                onPickLogo = { photoPickerLauncher.launch("image/*") },
                onSave = { colors, siglas, tradition, scope, region, motto, city, foundationDate ->
                    onUpdateClubProfile(colors, siglas, tradition, scope, region, motto, city, foundationDate)
                }
            )
        }

        // =========================================================================
        // MODAL 2: ASIGNADOR RÁPIDO DE OFICIALES EN MESA DIRECTIVA
        // =========================================================================
        assigningOfficerRole?.let { role ->
            AssignOfficerDialog(
                role = role,
                members = group.members,
                onDismiss = { assigningOfficerRole = null },
                onAssign = { memberUserId ->
                    onAssignOfficer(role, memberUserId)
                }
            )
        }

        // Espacio para librar la barra de navegación inferior
        Spacer(modifier = Modifier.height(96.dp))
    }
}

/**
 * Formulario modal de Perfil e Identidad Biker del Club:
 * Permite registrar y actualizar Siglas de Parche, Colores Oficiales, Tradición Biker,
 * Fecha de Fundación (Año, Mes, Día), Mesa Directiva (Nacional, Regional, Capítulo), País/Región, Ciudad Matriz y Lema.
 */
@Composable
private fun ClubProfileEditDialog(
    group: ClubGroupModel,
    onDismiss: () -> Unit,
    onPickLogo: () -> Unit,
    onSave: (
        colors: String,
        siglas: String,
        tradition: String,
        scope: String,
        region: String,
        motto: String,
        city: String?,
        foundationDate: String
    ) -> Unit
) {
    var colors by remember { mutableStateOf(group.colorsDescription.ifBlank { "Negro y Oro" }) }
    var siglas by remember { mutableStateOf(group.clubSiglas.ifBlank { group.tier.code }) }
    var tradition by remember { mutableStateOf(group.traditionType.ifBlank { "Moto Club (MC - Tradición 1%er / Clásica)" }) }
    var scope by remember { mutableStateOf(group.directivaScope.ifBlank { "Mesa Directiva Nacional" }) }
    var region by remember { mutableStateOf(group.directivaRegion.ifBlank { group.countryCode }) }
    var motto by remember { mutableStateOf(group.motto.ifBlank { "Hermandad, Respeto y Carretera" }) }
    var city by remember { mutableStateOf(group.city ?: "") }

    val dateOnly = group.foundationDate.substringBefore('T').substringBefore(' ').trim()
    val dateParts = dateOnly.split("-")
    var foundationYear by remember { mutableStateOf(dateParts.getOrNull(0)?.filter { it.isDigit() }?.take(4).orEmpty().ifBlank { "2018" }) }
    var foundationMonth by remember { mutableStateOf(dateParts.getOrNull(1)?.filter { it.isDigit() }?.takeLast(2).orEmpty().ifBlank { "05" }) }
    var foundationDay by remember { mutableStateOf(dateParts.getOrNull(2)?.filter { it.isDigit() }?.takeLast(2).orEmpty().ifBlank { "15" }) }

    var expandedTraditionDropdown by remember { mutableStateOf(false) }

    data class TraditionDropdownItem(
        val title: String,
        val sigla: String,
        val subtitle: String
    )

    val traditionOptions = listOf(
        TraditionDropdownItem(
            title = "Moto Club (MC - Tradición 1%er / Clásica)",
            sigla = "MC",
            subtitle = "3 piezas completas con territorio · Mesa completa (6 oficiales)"
        ),
        TraditionDropdownItem(
            title = "Moto Grupo (MG - Hermandad y Ruta)",
            sigla = "MG",
            subtitle = "2 piezas sin territorio inferior · Directiva intermedia"
        ),
        TraditionDropdownItem(
            title = "Moto Familia (MF - Convivencia y Familia)",
            sigla = "MF",
            subtitle = "1 o 2 piezas familiares · Directiva básica familiar"
        ),
        TraditionDropdownItem(
            title = "Racing Club (RC - Deportivo y Velocidad)",
            sigla = "RC",
            subtitle = "1 a 2 piezas emblema central · Pista / Recreativo"
        )
    )

    val scopePresets = listOf(
        "Mesa Directiva Nacional",
        "Mesa Directiva Regional",
        "Capítulo Autónomo Local"
    )

    val formattedFoundationDateStr = remember(foundationYear, foundationMonth, foundationDay) {
        val y = foundationYear.filter { it.isDigit() }.padStart(4, '0').takeLast(4)
        val mRaw = foundationMonth.filter { it.isDigit() }.ifBlank { "5" }.toIntOrNull()?.coerceIn(1, 12) ?: 5
        val dRaw = foundationDay.filter { it.isDigit() }.ifBlank { "15" }.toIntOrNull()?.coerceIn(1, 31) ?: 15
        val m = mRaw.toString().padStart(2, '0')
        val d = dRaw.toString().padStart(2, '0')
        "$y-$m-$d"
    }

    val liveEligibility = remember(formattedFoundationDateStr) {
        evaluateDirectivaEligibility(formattedFoundationDateStr)
    }

    val dialogLogoBitmap = remember(group.logoCustomUri) {
        group.logoCustomUri?.let { path ->
            try {
                if (path.startsWith("data:image") || (path.length > 200 && !path.startsWith("/") && !path.startsWith("content:"))) {
                    val base64Str = if (path.contains(",")) path.substringAfter(",") else path
                    val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)?.asImageBitmap()
                } else {
                    val file = File(path)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    val outlinedColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFFD4AF37),
        unfocusedBorderColor = Color(0xFF8B6B3D).copy(alpha = 0.5f),
        focusedLabelColor = Color(0xFFD4AF37),
        unfocusedLabelColor = Color(0xFF9E9285),
        focusedTextColor = Color(0xFFF5E6C8),
        unfocusedTextColor = Color(0xFFF5E6C8)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeritageEmblemBadge(
                    imageVector = Icons.Default.Edit,
                    badgeSize = 32.dp,
                    contentDescription = null
                )
                Column {
                    Text(
                        text = "PERFIL E IDENTIDAD DEL CLUB",
                        color = Color(0xFFD4AF37),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = group.name,
                        color = Color(0xFFF5E6C8),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 0. Foto / Escudo del Club (Cambio interactivo desde el modal)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickLogo() },
                    color = Color(0xFF261D16),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF8B6B3D).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, Color(0xFFD4AF37), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (dialogLogoBitmap != null) {
                                Image(
                                    bitmap = dialogLogoBitmap,
                                    contentDescription = "Escudo del Club",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_logo_ruta_biker_sync),
                                    contentDescription = "Escudo por defecto",
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ESCUDO / FOTO DEL CLUB",
                                color = Color(0xFFD4AF37),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (group.logoCustomUri != null) "✓ Foto personalizada activa. Toca para cambiarla."
                                       else "Toca para elegir logo desde tu galería",
                                color = Color(0xFFB8A99A),
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "Cambiar Escudo",
                            tint = Color(0xFFF5D061),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 1. Tradición del Club (Lista Desplegable con Autollenado de Siglas)
                Text(
                    text = "TRADICIÓN DEL CLUB (JERARQUÍA BIKER)",
                    color = Color(0xFFD4AF37),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedTraditionDropdown = true },
                        color = Color(0xFF261D16),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFD4AF37).copy(alpha = 0.8f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tradition,
                                    color = Color(0xFFF5E6C8),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = traditionOptions.find { it.title == tradition }?.subtitle
                                        ?: "Toca para seleccionar tradición biker",
                                    color = Color(0xFF9E9285),
                                    fontSize = 11.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Desplegar",
                                tint = Color(0xFFD4AF37)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = expandedTraditionDropdown,
                        onDismissRequest = { expandedTraditionDropdown = false },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .background(Color(0xFF221A14))
                            .border(1.dp, Color(0xFF8B6B3D), RoundedCornerShape(8.dp))
                    ) {
                        traditionOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = opt.title,
                                                color = if (tradition == opt.title) Color(0xFFF5D061) else Color(0xFFF5E6C8),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFF382A1B), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = opt.sigla,
                                                    color = Color(0xFFD4AF37),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                        }
                                        Text(
                                            text = opt.subtitle,
                                            color = Color(0xFF9E9285),
                                            fontSize = 11.sp
                                        )
                                    }
                                },
                                onClick = {
                                    tradition = opt.title
                                    siglas = opt.sigla // Autollenado requerido por el usuario
                                    expandedTraditionDropdown = false
                                }
                            )
                        }
                    }
                }

                // 2. Parche y Siglas Oficiales (Auto-llenado pero editable)
                Text(
                    text = "PARCHE Y SIGLAS OFICIALES",
                    color = Color(0xFFD4AF37),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                OutlinedTextField(
                    value = siglas,
                    onValueChange = { siglas = it.uppercase().take(8) },
                    label = { Text("Siglas en Parche (Auto-llenado por tradición)") },
                    supportingText = {
                        Text(
                            text = "Auto-llenado según tradición seleccionada. Puedes editar libremente si el club usa siglas propias (ej. BBMC).",
                            fontSize = 10.sp,
                            color = Color(0xFF9E9285)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedColors,
                    singleLine = true
                )

                // 3. Fecha de Fundación del Club (Año, Mes y Día)
                Text(
                    text = "FECHA DE FUNDACIÓN DEL CLUB",
                    color = Color(0xFFD4AF37),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = foundationDay,
                        onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) foundationDay = it },
                        label = { Text("Día (1-31)") },
                        modifier = Modifier.weight(1f),
                        colors = outlinedColors,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = foundationMonth,
                        onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) foundationMonth = it },
                        label = { Text("Mes (1-12)") },
                        modifier = Modifier.weight(1f),
                        colors = outlinedColors,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = foundationYear,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) foundationYear = it },
                        label = { Text("Año") },
                        modifier = Modifier.weight(1.3f),
                        colors = outlinedColors,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                // Elegibilidad canónica calculada en vivo según estatuto biker
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (liveEligibility.isNationalEligible) Color(0xFF162518)
                           else if (liveEligibility.isRegionalEligible) Color(0xFF282414)
                           else Color(0xFF241D17),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (liveEligibility.isNationalEligible) Color(0xFF4ADE80).copy(alpha = 0.6f)
                        else if (liveEligibility.isRegionalEligible) Color(0xFFFACC15).copy(alpha = 0.6f)
                        else Color(0xFF8B6B3D).copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarToday,
                                contentDescription = null,
                                tint = if (liveEligibility.isNationalEligible) Color(0xFF4ADE80)
                                       else if (liveEligibility.isRegionalEligible) Color(0xFFFACC15)
                                       else Color(0xFFD4AF37),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${liveEligibility.years} años de antigüedad · ${liveEligibility.statusBadge}",
                                color = if (liveEligibility.isNationalEligible) Color(0xFF86EFAC)
                                       else if (liveEligibility.isRegionalEligible) Color(0xFFFDE047)
                                       else Color(0xFFF5E6C8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = liveEligibility.detailText,
                            color = Color(0xFFB8A99A),
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        )
                    }
                }

                // 4. Colores Oficiales del Parche / Club
                Text(
                    text = "COLORES OFICIALES DEL CLUB",
                    color = Color(0xFFD4AF37),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                OutlinedTextField(
                    value = colors,
                    onValueChange = { colors = it },
                    label = { Text("Colores del Parche (ej. Negro y Oro, Rojo y Blanco)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedColors,
                    singleLine = true
                )

                // 5. Mesa Directiva: Alcance y Jurisdicción
                Text(
                    text = "JURISDICCIÓN DE MESA DIRECTIVA",
                    color = Color(0xFFD4AF37),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    scopePresets.forEach { preset ->
                        val isSelected = scope == preset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Color(0xFFD4AF37) else Color(0xFF261D16))
                                .border(1.dp, if (isSelected) Color(0xFFD4AF37) else Color(0xFF8B6B3D).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .clickable { scope = preset }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = preset,
                                color = if (isSelected) Color(0xFF1E1712) else Color(0xFFF5E6C8),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("País o Región (ej. Venezuela, Colombia, México)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedColors,
                    singleLine = true
                )

                // 6. Ciudad Matriz o Sede
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text("Ciudad Matriz / Sede (ej. Caracas, Miranda, Valencia)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedColors,
                    singleLine = true
                )

                // 7. Lema Oficial
                Text(
                    text = "LEMA OFICIAL",
                    color = Color(0xFFD4AF37),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                OutlinedTextField(
                    value = motto,
                    onValueChange = { motto = it },
                    label = { Text("Lema Oficial del Club") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedColors,
                    singleLine = true
                )
            }
        },
        confirmButton = {
            HeritageBrassButton(
                text = "Guardar Identidad Biker",
                onClick = {
                    onSave(
                        colors.trim(),
                        siglas.trim(),
                        tradition.trim(),
                        scope.trim(),
                        region.trim(),
                        motto.trim(),
                        city.trim().ifBlank { null },
                        formattedFoundationDateStr
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancelar", color = Color(0xFF9E9285))
            }
        },
        containerColor = Color(0xFF1E1712),
        shape = RoundedCornerShape(16.dp)
    )
}

/**
 * Diálogo modal para asignar rápidamente un oficial de la Mesa Directiva
 * seleccionando cualquier hermano del club (con ascenso automático a Full Patch si es invitado, P7).
 */
@Composable
private fun AssignOfficerDialog(
    role: ClubRole,
    members: List<ClubMemberModel>,
    onDismiss: () -> Unit,
    onAssign: (memberUserId: String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeritageEmblemBadge(
                    imageVector = AppIcons.TabGroups,
                    badgeSize = 32.dp,
                    contentDescription = null
                )
                Column {
                    Text(
                        text = "ASIGNAR CARGO DIRECTIVO",
                        color = Color(0xFFD4AF37),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = role.apiValue.replace("_", " ").uppercase(),
                        color = Color(0xFFF5E6C8),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Selecciona el piloto del club que asumirá la responsabilidad de este cargo en la mesa directiva:",
                    color = Color(0xFFC7B8A5),
                    fontSize = 12.sp
                )

                if (members.isEmpty()) {
                    Text(
                        text = "No hay otros miembros disponibles en el club.",
                        color = Color(0xFF9E9285),
                        fontSize = 12.sp
                    )
                } else {
                    members.forEach { m ->
                        val isCurrentHolder = m.clubRole == role
                        val isGuest = m.statusKind == MemberStatusKind.GUEST
                        val statusLabel = when (m.statusKind) {
                            MemberStatusKind.FULL -> "Full Patch"
                            MemberStatusKind.PROSPECT -> "Prospecto"
                            MemberStatusKind.GUEST -> "Invitado · Se ascenderá a Full Patch (P7)"
                        }

                        HeritageBevelCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAssign(m.userId)
                                    onDismiss()
                                },
                            cardColor = if (isCurrentHolder) Color(0xFF2C2216) else Color(0xFF1E1712),
                            borderColor = if (isCurrentHolder) Color(0xFFD4AF37) else Color(0xFF8B6B3D).copy(alpha = 0.4f),
                            contentPadding = PaddingValues(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF2A2016))
                                            .border(1.dp, Color(0xFFD4AF37), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = m.displayName.take(2).uppercase(),
                                            color = Color(0xFFF5D061),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = m.displayName,
                                            color = Color(0xFFF5E6C8),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = statusLabel,
                                            color = if (isGuest) Color(0xFFF5D061) else Color(0xFF9E9285),
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                if (isCurrentHolder) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFD4AF37).copy(alpha = 0.2f))
                                            .border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "ACTUAL",
                                            color = Color(0xFFF5D061),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cerrar", color = Color(0xFF9E9285))
            }
        },
        containerColor = Color(0xFF1E1712),
        shape = RoundedCornerShape(16.dp)
    )
}

/**
 * Ficha oficial y gestión de miembro del club (Heritage Elite · Micro-Fase 2.5).
 * Permite al Líder cambiar estatus, asignar cargo en mesa directiva y expulsar miembros con confirmación modal.
 */
@Composable
private fun MemberDetailScreenD4(
    group: ClubGroupModel,
    member: ClubMemberModel,
    isCurrentUserLeader: Boolean,
    onUpdateMember: (MemberClass, ClubRole?) -> Unit,
    onExpelMember: () -> Unit,
    onBack: () -> Unit
) {
    var selectedClass by remember(member) { mutableStateOf(MemberClass.from(member.statusKind, member.isFounder)) }
    var selectedRole by remember(member) { mutableStateOf(member.clubRole) }
    var showExpelDialog by remember { mutableStateOf(false) }

    val isFounder = member.isFounder || group.principalLeaderId == member.userId

    val rank = remember(member.validRides) { RiderRank.fromValidRides(member.validRides) }

    val availableRoles = remember {
        listOf(
            null to "Sin cargo directivo",
            ClubRole.VICE_PRESIDENT to "Vicepresidente",
            ClubRole.ROAD_CAPTAIN to "Capitán de Ruta",
            ClubRole.SERGEANT_AT_ARMS to "Sargento de Armas",
            ClubRole.SECRETARY to "Secretario",
            ClubRole.TREASURER to "Tesorero",
            ClubRole.ROAD_MARSHAL to "Road Marshal"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabecera con botón de retorno y título
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2016))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = "Volver al Club",
                    tint = Color(0xFFD4AF37),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Ficha de Hermandad",
                    color = Color(0xFF9E9285),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = member.displayName,
                    color = Color(0xFFF5E6C8),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Tarjeta Resumen del Piloto (Heritage)
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            cardColor = Color(0xFF1E1712),
            borderColor = Color(0xFF8B6B3D).copy(alpha = 0.5f),
            contentPadding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2A2016))
                                .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = member.displayName.take(2).uppercase(),
                                color = Color(0xFFD4AF37),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Column {
                            Text(
                                text = member.displayName,
                                color = Color(0xFFF5E6C8),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (member.bikeModel.isNotBlank()) member.bikeModel else "Sin moto registrada",
                                color = Color(0xFF9E9285),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF2A2016), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF8B6B3D).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${member.validRides} Rodadas · ${rank.name}",
                            color = Color(0xFFD4AF37),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (isCurrentUserLeader && !isFounder) {
            // Sección 1: Estatus Biker en el Club (Full, Prospecto, Fundador, Invitado)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ESTATUS EN ${group.name.uppercase()}",
                    color = Color(0xFFD4AF37),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                val statusOptions = listOf(
                    MemberClass.FULL_MEMBER to "Full Member",
                    MemberClass.PROSPECT to "Prospecto",
                    MemberClass.FOUNDER to "Fundador 🔥",
                    MemberClass.GUEST to "Invitado"
                )
                statusOptions.chunked(2).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowOptions.forEach { (stClass, label) ->
                            val isSelected = selectedClass == stClass
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFFD4AF37) else Color(0xFF1E1712))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFFD4AF37) else Color(0xFF8B6B3D).copy(alpha = 0.3f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        selectedClass = stClass
                                        if (stClass == MemberClass.GUEST) {
                                            selectedRole = null
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color(0xFF1E1712) else Color(0xFF9E9285),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Sección 2: Cargo en Mesa Directiva
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "CARGO EN MESA DIRECTIVA",
                    color = Color(0xFFD4AF37),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                if (selectedClass == MemberClass.GUEST) {
                    Text(
                        text = "ℹ️ Al asignar un cargo en la Mesa, el miembro será promovido automáticamente a Full Member (Estatuto Biker P7).",
                        color = Color(0xFFF5D061),
                        fontSize = 11.sp
                    )
                }
                availableRoles.chunked(2).forEach { rowRoles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowRoles.forEach { (role, label) ->
                            val isSelected = selectedRole == role
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF382A1E) else Color(0xFF1E1712))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFFD4AF37) else Color(0xFF8B6B3D).copy(alpha = 0.3f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        selectedRole = role
                                        if (role != null && selectedClass == MemberClass.GUEST) {
                                            selectedClass = MemberClass.FULL_MEMBER
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color(0xFFF5E6C8) else Color(0xFF9E9285),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        if (rowRoles.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Botón Guardar Cambios (Dorado)
            Button(
                onClick = { onUpdateMember(selectedClass, selectedRole) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD4AF37),
                    contentColor = Color(0xFF1E1712)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Guardar Cambios",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            // Botón Expulsar del Club (Rojo Alerta)
            Button(
                onClick = { showExpelDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3E1414),
                    contentColor = Color(0xFFFCA5A5)
                ),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(
                    text = "🗑️ Expulsar del Club",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }

    // Diálogo Modal de Confirmación de Expulsión
    if (showExpelDialog) {
        AlertDialog(
            onDismissRequest = { showExpelDialog = false },
            title = {
                Text(
                    text = "¿Expulsar a ${member.displayName}?",
                    color = Color(0xFFF5E6C8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "Esta acción revocará formalmente su membresía en '${group.name}'. El piloto dejará de pertenecer a la hermandad del club.",
                    color = Color(0xFFD4AF37).copy(alpha = 0.9f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExpelDialog = false
                        onExpelMember()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Confirmar Expulsión", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                Button(
                    onClick = { showExpelDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color(0xFF9E9285)
                    )
                ) {
                    Text("Cancelar", color = Color(0xFF9E9285), fontSize = 12.sp)
                }
            },
            containerColor = Color(0xFF1E1712),
            shape = RoundedCornerShape(12.dp)
        )
    }
}

/**
 * Semáforo accesible (ICONS.md §1 regla 2 y §11: icono CheckCircle ✓ / Warning ⚠ / Cancel ✕ + texto).
 */
@Composable
fun PaymentSemaphoreBadge(semaphore: PaymentSemaphore) {
    val (icon, color, textRes) = when (semaphore) {
        PaymentSemaphore.GREEN -> Triple(AppIcons.PaymentGreen, BikerColors.StatusGreen, R.string.sem_green)
        PaymentSemaphore.YELLOW -> Triple(AppIcons.PaymentYellow, BikerColors.StatusYellow, R.string.sem_yellow)
        PaymentSemaphore.RED -> Triple(AppIcons.PaymentRed, BikerColors.StatusRed, R.string.sem_red)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(BikerColors.BackgroundDark, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(id = textRes),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
private data class RankXpInfo(
    val rankTitle: String,
    val nextRankTitle: String,
    val currentXp: Int,
    val nextXp: Int
)

/**
 * F1. Perfil personal oficial Heritage Premium (Pantalla 5) con XP, Métricas, Insignias y Edición.
 */
@Composable
fun ProfileAndAuthTabScreen(
    viewModel: Phase1ViewModel,
    initialShowGamification: Boolean = false,
    onLogout: () -> Unit = {},
    onSubscreenToggled: ((Boolean) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val user by viewModel.repository.currentUser.collectAsState()
    val forceSkeleton by viewModel.forceSkeletonPreview.collectAsState()
    val banner by viewModel.statusBanner.collectAsState()

    var displayName by remember(user.id) { mutableStateOf(user.displayName) }
    var bikeModel by remember(user.id) { mutableStateOf(user.bikeModel) }
    var mapColorHex by remember(user.id) { mutableStateOf(user.mapColorHex) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showAuthFlow by remember { mutableStateOf(false) }

    var loginEmail by remember { mutableStateOf("") }
    var loginName by remember { mutableStateOf("") }
    var loginCountry by remember { mutableStateOf("MX") }
    var acceptedTerms by remember { mutableStateOf(true) }
    var activateLeaderPlan by remember { mutableStateOf(false) }
    var showGamificationProfile by remember { mutableStateOf(initialShowGamification) }
    var showRideHistory by remember { mutableStateOf(false) }

    LaunchedEffect(showGamificationProfile, showRideHistory) {
        onSubscreenToggled?.invoke(showGamificationProfile || showRideHistory)
    }
    var selectedBadgePreview by remember {
        mutableStateOf<Triple<String, String, Triple<String, Int, Color>>?>(null)
    }

    val profileState: UiState<UserProfileModel> = if (forceSkeleton) {
        UiState.Loading
    } else {
        viewModel.repository.resolveUiState(user) { false }
    }

    val validRides = user.validRides
    val rankInfo = when {
        validRides >= 50 -> RankXpInfo("Leyenda", "Máximo Rango", 5000 + (validRides - 50) * 10, 5000)
        validRides >= 25 -> RankXpInfo("Élite", "Leyenda", 2000 + (validRides - 25) * 120, 5000)
        validRides >= 10 -> RankXpInfo("Veterano", "Élite", 500 + (validRides - 10) * 100, 2000)
        validRides >= 3 -> RankXpInfo("Rider", "Veterano", 100 + (validRides - 3) * 55, 500)
        else -> RankXpInfo("Novato", "Rider", validRides * 30, 100)
    }

    val progressFraction = if (rankInfo.nextXp > rankInfo.currentXp) {
        (rankInfo.currentXp.toFloat() / rankInfo.nextXp.toFloat()).coerceIn(0.06f, 1f)
    } else 1f

    val totalKm = if (validRides > 0) validRides * 120 else 0
    val streakMonths = if (validRides > 0) 1 else 0

    if (showRideHistory) {
        RideHistoryScreen(
            onNavigateBack = { showRideHistory = false }
        )
        return
    }

    if (showGamificationProfile) {
        val currentRankEnum = when (rankInfo.rankTitle) {
            "Leyenda" -> com.rutabikersync.app.feature.badges.BikerRank.LEYENDA
            "Élite" -> com.rutabikersync.app.feature.badges.BikerRank.ELITE
            "Veterano" -> com.rutabikersync.app.feature.badges.BikerRank.VETERANO
            "Rider" -> com.rutabikersync.app.feature.badges.BikerRank.RIDER
            else -> com.rutabikersync.app.feature.badges.BikerRank.NOVATO
        }
        val nextRankEnum = when (rankInfo.nextRankTitle) {
            "Leyenda" -> com.rutabikersync.app.feature.badges.BikerRank.LEYENDA
            "Élite" -> com.rutabikersync.app.feature.badges.BikerRank.ELITE
            "Veterano" -> com.rutabikersync.app.feature.badges.BikerRank.VETERANO
            "Rider" -> com.rutabikersync.app.feature.badges.BikerRank.RIDER
            else -> null
        }
        val currentClubName = viewModel.repository.groups.value.firstOrNull()?.name ?: "Águilas del Camino MC"
        GamificationProfileScreen(
            pilotName = user.displayName.ifBlank { "Piloto Activo" },
            clubName = currentClubName,
            currentXp = rankInfo.currentXp,
            currentRank = currentRankEnum,
            nextRank = nextRankEnum,
            nextRankXp = rankInfo.nextXp,
            activeStreakMonths = streakMonths,
            totalMeshKm = totalKm.toDouble(),
            onOpenRideHistory = {
                showGamificationProfile = false
                showRideHistory = true
            },
            onShareProfile = {
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "🛡️ Expediente Biker — ${user.displayName.ifBlank { "Piloto Activo" }}")
                    putExtra(
                        android.content.Intent.EXTRA_TEXT,
                        """
                        ╔══════════════════════════════════════╗
                        ║   🏍️ RUTA BIKER SYNC · EXPEDIENTE    ║
                        ╚══════════════════════════════════════╝
                        
                        👤 Piloto: ${user.displayName.ifBlank { "Piloto Activo" }}
                        ⚔️ Club: $currentClubName
                        🎖️ Rango: ${rankInfo.rankTitle}
                        💎 XP Acumulado: ${rankInfo.currentXp} XP
                        📡 Km en Malla Táctica: $totalKm km
                        🔥 Racha Activa: $streakMonths meses
                        
                        🏆 Insignias de Honor: 7 / 10 obtenidas
                        🛡️ Piloto Protegido (Oro)
                        📡 Sin Cobertura (Plata)
                        🚑 Convoy Seguro (Oro)
                        🆘 Primer SOS (Legendario)
                        
                        ⚡ ¡Conectado y transmitiendo en la malla táctica off-grid!
                        #RutaBikerSync #HermandadBiker #MallaTactical
                        """.trimIndent()
                    )
                }
                context.startActivity(android.content.Intent.createChooser(shareIntent, "Compartir Expediente Biker"))
            },
            onNavigateBack = { showGamificationProfile = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF151821), Color(0xFF0A0B0E))
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (banner != null) {
            Text(
                text = banner ?: "",
                color = BikerColors.StatusGreen,
                fontSize = 13.sp
            )
        }

        // Cabecera Oficial del Piloto: Avatar concéntrico metálico, datos y estado en línea
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Doble halo concéntrico con anillo en Oro Ámbar (#D4AF37)
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFD4AF37).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF2C3240), Color(0xFF11141A))
                                )
                            )
                            .border(2.dp, Color(0xFFD4AF37), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = user.initials,
                            color = Color(0xFFD4AF37),
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (displayName.isNotBlank()) displayName else "Piloto",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                    Text(
                        text = if (bikeModel.isNotBlank()) bikeModel else "Sin moto registrada",
                        color = Color(0xFF9CA3AF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF34D399))
                        )
                        Text(
                            text = "En línea",
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "·",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                        Text(
                            text = rankInfo.rankTitle,
                            color = Color(0xFFD4AF37),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Botón de engranaje metálico para Ajustes / Editar Perfil (Tarea T2)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF242834), Color(0xFF13151B))
                        )
                    )
                    .border(1.5.dp, Color(0xFF454B5C), CircleShape)
                    .clickable {
                        UxTestTracker.logEvent("PROFILE_TAB", "OPEN_EDIT_DIALOG", "btn_settings")
                        showEditProfileDialog = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Ajustes de Perfil",
                    tint = Color(0xFFE5B869),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Placa Biselada: Rango y Experiencia (XP) - Clickable para abrir perfil de gamificación
        HeritageBevelCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showGamificationProfile = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val rankDrawableRes = when (rankInfo.rankTitle) {
                    "Leyenda" -> R.drawable.ic_rank_leyenda
                    "Élite" -> R.drawable.ic_rank_elite
                    "Veterano" -> R.drawable.ic_rank_veterano
                    "Rider" -> R.drawable.ic_rank_rider
                    else -> R.drawable.ic_rank_novato
                }
                HeritageEmblemBadge(
                    painter = androidx.compose.ui.res.painterResource(id = rankDrawableRes),
                    contentDescription = "Rango del piloto",
                    badgeSize = 52.dp,
                    iconSize = 44.dp,
                    glowColor = Color(0xFFF3C766)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = rankInfo.rankTitle,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${rankInfo.currentXp} / ${rankInfo.nextXp} XP",
                            color = Color(0xFFC7CBD6),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Hacia ${rankInfo.nextRankTitle}",
                            color = Color(0xFF8E95A5),
                            fontSize = 11.sp
                        )
                    }

                    // Barra de progreso dorada en relieve
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF101217))
                            .border(1.dp, Color(0xFF272B36), RoundedCornerShape(4.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFFF6CE74), Color(0xFFDF9E32))
                                    )
                                )
                        )
                    }
                }
            }
        }

        // Cuadrícula de Métricas Oficiales (Km acumulados, Racha mensual, Rodadas)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeritageMetricCard(
                icon = AppIcons.TabRides,
                iconTint = Color(0xFFE5B869),
                value = "$totalKm km",
                label = "Total acumulado",
                modifier = Modifier.weight(1f)
            )
            HeritageMetricCard(
                icon = AppIcons.RolePresident,
                iconTint = Color(0xFFF97316),
                value = "$streakMonths meses",
                label = "Racha mensual",
                modifier = Modifier.weight(1f)
            )
            HeritageMetricCard(
                icon = AppIcons.LeaderFallback,
                iconTint = Color(0xFFE5B869),
                value = "$validRides",
                label = "Rodadas",
                modifier = Modifier.weight(1f)
            )
        }

        // Sección: Hitos y Logros
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hitos y Logros",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1F2630),
                border = BorderStroke(1.2.dp, HeritageBrassGold),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        showGamificationProfile = true
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "🎖️ Ver todos",
                        color = HeritageBrassGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "→",
                        color = Color(0xFFC86D44),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeritageAchievementBadge(
                title = "Aventurero",
                subtitle = "5.000 km",
                badgeRes = R.drawable.ic_achieve_aventurero,
                borderColor = Color(0xFFE5B869),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Aventurero",
                        "Superar los 5.000 km de carretera acumulados en convoy.",
                        Triple("ORO", R.drawable.ic_achieve_aventurero, Color(0xFFE5B869))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "1ª Rodada",
                subtitle = "Iniciación",
                badgeRes = R.drawable.ic_achieve_primera_rodada,
                borderColor = Color(0xFFF59E0B),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Primera Rodada",
                        "Completar la primera rodada oficial en convoy con el club.",
                        Triple("BRONCE", R.drawable.ic_achieve_primera_rodada, Color(0xFFF59E0B))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "Nocturna",
                subtitle = "Ruta oscura",
                badgeRes = R.drawable.ic_achieve_rodada_nocturna,
                borderColor = Color(0xFF38BDF8),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Rodada Nocturna",
                        "Completar rodadas nocturnas oficiales bajo las estrellas.",
                        Triple("PLATA", R.drawable.ic_achieve_rodada_nocturna, Color(0xFF38BDF8))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "Offline",
                subtitle = "Sin señal",
                badgeRes = R.drawable.ic_achieve_sin_cobertura,
                borderColor = Color(0xFF34D399),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Sin Cobertura",
                        "Rodar más de 30 minutos sin internet manteniendo la malla viva.",
                        Triple("PLATA", R.drawable.ic_achieve_sin_cobertura, Color(0xFF34D399))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "Viajero",
                subtitle = "500 km",
                badgeRes = R.drawable.ic_achieve_500km,
                borderColor = Color(0xFFE5B869),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Viajero (500 km)",
                        "Acumular 500 km registrados en la malla del convoy.",
                        Triple("PLATA", R.drawable.ic_achieve_500km, Color(0xFFE5B869))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "Primer SOS",
                subtitle = "Auxilio vial",
                badgeRes = R.drawable.ic_achieve_primer_sos,
                borderColor = Color(0xFFEF4444),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Primer SOS",
                        "Emitir o confirmar recepción de un auxilio vial en ruta.",
                        Triple("LEGENDARIO", R.drawable.ic_achieve_primer_sos, Color(0xFFEF4444))
                    )
                }
            )
        }

        // Sección: Insignias Recibidas
        Text(
            text = "Insignias Recibidas",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeritageClubPatchBadge(
                clubName = "Hermandad Asfalto",
                badgeText = "Club MC",
                patchColor = Color(0xFFE5B869),
                badgeRes = R.drawable.ic_rank_fundador,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                    selectedBadgePreview = Triple(
                        "Hermandad Asfalto",
                        "Parche bordado de honor de membresía de hermandad biker fundadora.",
                        Triple("CLUB MC", R.drawable.ic_rank_fundador, Color(0xFFE5B869))
                    )
                }
            )
            HeritageClubPatchBadge(
                clubName = "Riders Unidos",
                badgeText = "(Invitado)",
                patchColor = Color(0xFFEF4444),
                badgeRes = R.drawable.ic_rank_honor,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                    selectedBadgePreview = Triple(
                        "Riders Unidos",
                        "Insignia de cortesía inter-club recibida en rodada de fraternidad.",
                        Triple("INVITADO", R.drawable.ic_rank_honor, Color(0xFFEF4444))
                    )
                }
            )
            HeritageClubPatchBadge(
                clubName = "Valle Virgen",
                badgeText = "(Completada)",
                patchColor = Color(0xFF38BDF8),
                badgeRes = R.drawable.ic_rank_full_member,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                    selectedBadgePreview = Triple(
                        "Valle Virgen",
                        "Insignia conmemorativa de la travesía de montaña Valle Virgen.",
                        Triple("CONMEMORATIVA", R.drawable.ic_rank_full_member, Color(0xFF38BDF8))
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Botón de Latón Estampado Dark Elite para Editar Perfil (borde dorado, fondo carbón, texto en oro)
        Surface(
            onClick = {
                UxTestTracker.logEvent("PROFILE_TAB", "OPEN_EDIT_DIALOG", "btn_editar_perfil_laton")
                showEditProfileDialog = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1F2630),
            border = BorderStroke(1.2.dp, Color(0xFFD4AF37))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = AppIcons.Edit,
                    contentDescription = null,
                    tint = Color(0xFFD4AF37),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Editar Perfil (Moto y Pin)",
                    color = Color(0xFFD4AF37),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Botón secundario para Iniciar Sesión / Cuenta si el tester lo requiere
        OutlinedButton(
            onClick = {
                UxTestTracker.logEvent("PROFILE_TAB", "TOGGLE_AUTH_FLOW", "btn_abrir_auth")
                showAuthFlow = !showAuthFlow
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(id = R.string.profile_open_auth),
                color = Color(0xFFE5B869)
            )
        }

        if (showAuthFlow) {
            HeritageBevelCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Iniciar Sesión o Crear Cuenta",
                    color = Color(0xFFE5B869),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                OutlinedTextField(
                    value = loginEmail,
                    onValueChange = { loginEmail = it },
                    label = { Text("Correo electrónico") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = loginName,
                    onValueChange = { loginName = it },
                    label = { Text("Apodo de rider") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = loginCountry,
                    onValueChange = { if (it.length <= 2) loginCountry = it.uppercase() },
                    label = { Text("País ISO-2") },
                    modifier = Modifier.fillMaxWidth()
                )
                ConsentCheckboxRow(
                    checked = acceptedTerms,
                    onCheckedChange = { acceptedTerms = it },
                    label = "Acepto las reglas de convivencia y términos de servicio"
                )
                ConsentCheckboxRow(
                    checked = activateLeaderPlan,
                    onCheckedChange = { activateLeaderPlan = it },
                    label = "Activar cuenta de Líder ($15 USD/mes · permite convocar rodadas)"
                )
                Button(
                    onClick = {
                        viewModel.loginOrRegisterUser(
                            loginEmail,
                            loginName,
                            loginCountry,
                            acceptedTerms,
                            activateLeaderPlan
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE5B869),
                        contentColor = Color(0xFF101114)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Iniciar sesión / Registrar cuenta", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(90.dp))
    }

    // Diálogo Heritage Oficial: Editar Perfil de Piloto (Tarea T2 del UX Test)
    if (showEditProfileDialog) {
        var tempName by remember { mutableStateOf(displayName) }
        var tempBike by remember { mutableStateOf(bikeModel) }
        var tempColor by remember { mutableStateOf(mapColorHex) }

        val colorPresets = listOf(
            "#F3C766", // Oro cálido
            "#38BDF8", // Azul neón
            "#34D399", // Esmeralda
            "#EF4444", // Carmesí
            "#A855F7", // Púrpura
            "#FFFFFF"  // Blanco
        )

        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = {
                Text(
                    text = "Editar Perfil de Piloto",
                    color = Color(0xFFE5B869),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Configura tu identidad en el mapa y en la red mesh:",
                        color = Color.White,
                        fontSize = 12.sp
                    )

                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Apodo de rider") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tempBike,
                        onValueChange = { tempBike = it },
                        label = { Text("Modelo de moto") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Color de tu pin en el mapa:",
                        color = Color(0xFF9CA3AF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        colorPresets.forEach { hex ->
                            val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Yellow)
                            val isSelected = tempColor.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color(0xFF3E4455),
                                        shape = CircleShape
                                    )
                                    .clickable { tempColor = hex }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                HeritageBrassButton(
                    text = "Guardar",
                    onClick = {
                        displayName = tempName
                        bikeModel = tempBike
                        mapColorHex = tempColor
                        UxTestTracker.logEvent(
                            "PROFILE_TAB",
                            "SAVE_PROFILE",
                            "btn_guardar_perfil",
                            "displayName=$displayName;bike=$bikeModel;color=$mapColorHex"
                        )
                        viewModel.saveUserProfile(displayName, bikeModel, mapColorHex)
                        showEditProfileDialog = false
                    }
                )
            },
            dismissButton = {
                Button(
                    onClick = { showEditProfileDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242732))
                ) {
                    Text("Cancelar", color = Color.White)
                }
            },
            containerColor = Color(0xFF161820)
        )
    }

    // Modal de Detalle de Insignia en Alta Definición (130 dp)
    selectedBadgePreview?.let { preview ->
        val title = preview.first
        val description = preview.second
        val tier = preview.third.first
        val resId = preview.third.second
        val color = preview.third.third

        AlertDialog(
            onDismissRequest = { selectedBadgePreview = null },
            containerColor = Color(0xFF141720),
            shape = RoundedCornerShape(20.dp),
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(130.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF2C3240), Color(0xFF101217))
                                )
                            )
                            .border(BorderStroke(2.5.dp, color), RoundedCornerShape(24.dp))
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = resId),
                            contentDescription = title,
                            modifier = Modifier.size(114.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "DISTINCIÓN: $tier",
                        color = color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }
            },
            text = {
                Text(
                    text = description,
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedBadgePreview = null
                        showGamificationProfile = true
                    }
                ) {
                    Text("Ver Catálogo Completo >", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedBadgePreview = null }) {
                    Text("Cerrar", color = Color(0xFF94A3B8))
                }
            }
        )
    }
}

/**
 * Panel de Desarrollador (Solo Debug, sección 2.2 de la especificación maestra).
 */
@Composable
fun DevPanelDialog(
    viewModel: Phase1ViewModel,
    onDismiss: () -> Unit
) {
    val overrides by viewModel.repository.devOverrides.collectAsState()
    val forceSkeleton by viewModel.forceSkeletonPreview.collectAsState()
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BikerColors.SurfaceDark,
        title = {
            Text(
                text = stringResource(id = R.string.dev_panel_title),
                color = BikerColors.PrimaryAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.setForceSkeletonPreview(false)
                        viewModel.repository.updateDevOverrides { DevPanelOverrides() }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BikerColors.PrimaryAmber,
                        contentColor = BikerColors.BackgroundDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.dev_load_demo), fontWeight = FontWeight.Bold)
                }

                // Bloque D: exportar logs sin adb. Doble guardia: el panel ya está detrás
                // de BuildConfig.DEV_PANEL_ENABLED y aquí se vuelve a comprobar.
                Button(
                    onClick = {
                        if (!com.rutabikersync.app.BuildConfig.DEV_PANEL_ENABLED) return@Button
                        exportScope.launch {
                            val result = runCatching { AppLogExporter.export(context) }
                            exportStatus = result.fold(
                                onSuccess = {
                                    "ZIP listo (${it.lineCount} líneas, ${it.droppedLineCount} descartadas): ${it.file.name}"
                                },
                                onFailure = { "No se pudo exportar: ${it.message}" }
                            )
                            android.widget.Toast.makeText(
                                context,
                                exportStatus,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            if (result.isSuccess) AppLogExporter.share(context, result.getOrThrow().file)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BikerColors.PrimaryAmber,
                        contentColor = BikerColors.BackgroundDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.dev_export_logs), fontWeight = FontWeight.Bold)
                }
                if (exportStatus.isNotBlank()) {
                    Text(exportStatus, fontSize = 11.sp, color = BikerColors.TextSecondary)
                }

                DevSwitchRow(
                    label = "Usar FakeBackend (Off = Backend HTTP Real)",
                    checked = overrides.useFakeBackend,
                    onCheckedChange = { v ->
                        viewModel.repository.updateDevOverrides { it.copy(useFakeBackend = v) }
                    }
                )
                DevSwitchRow(
                    label = "Forzar Skeleton Loading (150ms/400ms/8s)",
                    checked = forceSkeleton,
                    onCheckedChange = { viewModel.setForceSkeletonPreview(it) }
                )
                DevSwitchRow(
                    label = "Sin red (Offline)",
                    checked = overrides.forceOffline,
                    onCheckedChange = { v ->
                        viewModel.repository.updateDevOverrides { it.copy(forceOffline = v) }
                    }
                )
                DevSwitchRow(
                    label = "Error 500 (Servidor)",
                    checked = overrides.forceServerError500,
                    onCheckedChange = { v ->
                        viewModel.repository.updateDevOverrides { it.copy(forceServerError500 = v) }
                    }
                )
                DevSwitchRow(
                    label = "Suscripción vencida (Rojo)",
                    checked = overrides.forceSubscriptionExpired,
                    onCheckedChange = { v ->
                        viewModel.repository.updateDevOverrides { it.copy(forceSubscriptionExpired = v) }
                    }
                )
                DevSwitchRow(
                    label = "En gracia / Stale cache",
                    checked = overrides.forceInGracePeriod,
                    onCheckedChange = { v ->
                        viewModel.repository.updateDevOverrides { it.copy(forceInGracePeriod = v) }
                    }
                )
                DevSwitchRow(
                    label = "Credencial caducada (>72h)",
                    checked = overrides.forceExpiredCredential,
                    onCheckedChange = { v ->
                        viewModel.repository.updateDevOverrides { it.copy(forceExpiredCredential = v) }
                    }
                )
                DevSwitchRow(
                    label = "Batería baja (<15%)",
                    checked = overrides.forceLowBattery,
                    onCheckedChange = { v ->
                        viewModel.repository.updateDevOverrides { it.copy(forceLowBattery = v) }
                    }
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cerrar", color = BikerColors.PrimaryAmber)
            }
        }
    )
}

@Composable
private fun DevSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = BikerColors.TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
