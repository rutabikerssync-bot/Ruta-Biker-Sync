package com.rutabikersync.app.feature.groups

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun GroupsMainTabScreen(viewModel: Phase1ViewModel) {
    val groups by viewModel.repository.groups.collectAsState()
    val invitations by viewModel.repository.pendingInvitations.collectAsState()
    val currentUser by viewModel.repository.currentUser.collectAsState()
    val forceSkeleton by viewModel.forceSkeletonPreview.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val selectedInvitationId by viewModel.selectedInvitationId.collectAsState()
    val selectedMemberUserId by viewModel.selectedMemberUserId.collectAsState()
    val banner by viewModel.statusBanner.collectAsState()

    val uiState = viewModel.buildGroupsUiState(
        groups = groups,
        invitations = invitations,
        user = currentUser,
        forceLoading = forceSkeleton
    )

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

        if (banner != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BikerColors.SurfaceDark, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = banner ?: "",
                    color = BikerColors.StatusGreen,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
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

            when {
                activeInvitation != null -> {
                    InvitationDetailScreenD2(
                        invitation = activeInvitation,
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
                            isCurrentUserLeader = activeGroup.leaderIds.contains(data.currentUser.id),
                            onUpdateMember = { newStatus, newRole ->
                                viewModel.updateMemberRoleOrStatus(
                                    activeGroup.id,
                                    activeMember.userId,
                                    newStatus,
                                    newRole
                                )
                            },
                            onBack = { viewModel.selectMember(null) }
                        )
                    } else {
                        GroupDetailScreenD3(
                            group = activeGroup,
                            currentUser = data.currentUser,
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
                            onBack = { viewModel.selectGroup(null) }
                        )
                    }
                }
                else -> {
                    GroupsOverviewScreenD1(
                        data = data,
                        onOpenInvitation = { viewModel.selectInvitation(it) },
                        onOpenGroup = { viewModel.selectGroup(it) },
                        onCreateGroup = { country, name, phash ->
                            viewModel.createNewGroup(country, name, phash)
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
    onCreateGroup: (String, String, String) -> Unit,
    onJoinByQr: (String) -> Unit
) {
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val scanned = result.contents
        if (!scanned.isNullOrBlank()) {
            UxTestTracker.logEvent("GROUPS_D1", "SCAN_QR_SUCCESS", "camera_scanner", "payload=$scanned")
            onJoinByQr(scanned)
        } else {
            UxTestTracker.logEvent("GROUPS_D1", "SCAN_QR_CANCELLED", "camera_scanner", "cancelled_or_no_permission")
        }
    }

    var showCreateForm by remember { mutableStateOf(false) }
    var manualInviteCode by remember { mutableStateOf("RUTA-TEST-01") }
    var newCountry by remember { mutableStateOf(data.currentUser.countryCode) }
    var newName by remember { mutableStateOf("") }
    var newPhash by remember { mutableStateOf("123456789abcdef0") }

    // 0. Unirse a un Grupo por QR o Código (flujo principal de invitación T4)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
            .border(1.dp, BikerColors.PrimaryAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.QrScanner,
                contentDescription = null,
                tint = BikerColors.PrimaryAmber,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Unirse a un Grupo con QR",
                color = BikerColors.PrimaryAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Text(
            text = "Escanea el código QR que te muestre el Presidente o Vicepresidente del club para unirte al grupo.",
            color = BikerColors.TextSecondary,
            fontSize = 12.sp
        )

        Button(
            onClick = {
                UxTestTracker.logEvent("GROUPS_D1", "OPEN_QR_SCANNER", "btn_escanear_qr")
                val options = com.journeyapps.barcodescanner.ScanOptions().apply {
                    setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    setPrompt("Apunta la cámara al código QR del Presidente o Vicepresidente")
                    setCameraId(0)
                    setBeepEnabled(true)
                    setBarcodeImageEnabled(false)
                    setOrientationLocked(false)
                }
                scanLauncher.launch(options)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = BikerColors.PrimaryAmber,
                contentColor = BikerColors.BackgroundDark
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = AppIcons.QrScanner, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Escanear Código QR con Cámara", fontWeight = FontWeight.Bold)
        }

        // Fallback alfanumérico
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = manualInviteCode,
                onValueChange = { manualInviteCode = it },
                label = { Text("Código de respaldo") },
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

        // Simulación solo visible para el teléfono de apoyo del moderador (P0)
        if (UxTestTracker.isModeratorSupport) {
            OutlinedButton(
                onClick = {
                    UxTestTracker.logEvent("GROUPS_D1", "SCAN_QR_SIMULATED", "moderator_sim_btn", "payload=RUTA-TEST-01")
                    onJoinByQr("rbs://invite?groupId=grp-test-01&code=RUTA-TEST-01&groupName=Águilas%20del%20Valle")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "[Moderador P0] Simular Lectura QR (RUTA-TEST-01)",
                    fontSize = 11.sp,
                    color = BikerColors.StatusYellow
                )
            }
        }
    }

    // 1. Invitaciones pendientes (RULES R2.1)
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

    // 2. Lista de grupos
    if (data.groups.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = AppIcons.TabGroups,
                contentDescription = null,
                tint = BikerColors.PrimaryAmber,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(id = R.string.groups_empty_title),
                color = BikerColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = stringResource(id = R.string.groups_empty_desc),
                color = BikerColors.TextSecondary,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    } else {
        data.groups.forEach { grp ->
        val myMembership = grp.members.find { it.userId == data.currentUser.id }
        val isLeader = grp.leaderIds.contains(data.currentUser.id)
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
                    // Placeholder circular con iniciales del grupo (SKELETONS.md §6)
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
                            } else if (isLeader) {
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
                            text = "Líderes: ${grp.leaderIds.size}/3 · Miembros: ${grp.members.size}",
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
}

    // 3. Crear grupo (A4 / D1) con verificación en vivo de nombre/leet y pHash
    OutlinedButton(
        onClick = { showCreateForm = !showCreateForm },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(id = R.string.groups_create_btn),
            color = BikerColors.PrimaryAmber,
            fontWeight = FontWeight.Bold
        )
    }

    if (showCreateForm) {
        val collisionPreview = data.groups
            .filter { it.countryCode.equals(newCountry.trim(), ignoreCase = true) }
            .firstNotNullOfOrNull { existing ->
                val c = ClubRulesEngine.checkGroupNameCollision(newName, existing.name)
                if (c.collides) "Colisión en ${existing.countryCode} con '${existing.name}' (${c.reason})" else null
            }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Nuevo Grupo (RULES R4.1, R5.1, R5.2)",
                color = BikerColors.PrimaryAmber,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = newCountry,
                onValueChange = { if (it.length <= 2) newCountry = it.uppercase() },
                label = { Text("País ISO-2 (ej. MX, CO, ES)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Nombre del grupo (verifica leet y tildes)") },
                modifier = Modifier.fillMaxWidth()
            )
            if (newName.isNotBlank()) {
                Text(
                    text = collisionPreview
                        ?: "✓ Nombre disponible en $newCountry (normalizado: '${ClubRulesEngine.normalizeGroupName(newName)}')",
                    color = if (collisionPreview != null) BikerColors.StatusRed else BikerColors.StatusGreen,
                    fontSize = 12.sp
                )
            }
            OutlinedTextField(
                value = newPhash,
                onValueChange = { if (it.length <= 16) newPhash = it.lowercase() },
                label = { Text("Logo pHash 64-bit hex (16 chars)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (newName.length >= 3 && newPhash.length == 16) {
                        UxTestTracker.logEvent(
                            screen = "GROUPS_D1",
                            action = "TAP_CREATE_GROUP",
                            target = "btn_create_group",
                            details = "country=$newCountry"
                        )
                        onCreateGroup(newCountry, newName, newPhash)
                        showCreateForm = false
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BikerColors.PrimaryAmber,
                    contentColor = BikerColors.BackgroundDark
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Registrar grupo único en $newCountry", fontWeight = FontWeight.Bold)
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

        ConsentCheckboxRow(
            checked = consentMembership,
            onCheckedChange = { consentMembership = it },
            label = stringResource(id = R.string.consent_membership)
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
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BikerColors.PrimaryAmber,
                    contentColor = BikerColors.BackgroundDark
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(id = R.string.action_accept_invite), fontWeight = FontWeight.Bold)
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

/**
 * D3/D5/D6/D8/D9. Vista principal del grupo: miembros, cargos, semáforo de pagos, invitación, votación 2/3 y transferencia.
 */
@Composable
private fun GroupDetailScreenD3(
    group: ClubGroupModel,
    currentUser: UserProfileModel,
    onSelectMember: (String) -> Unit,
    onInviteMember: (String, MemberStatusKind) -> Unit,
    onVote: (String, Boolean) -> Unit,
    onTransfer: (String) -> Unit,
    onBack: () -> Unit
) {
    var inviteUserId by remember { mutableStateOf("u-guest-1") }
    var inviteStatus by remember { mutableStateOf(MemberStatusKind.GUEST) }
    var transferTargetId by remember { mutableStateOf("u-leader-2") }

    val myMember = group.members.find { it.userId == currentUser.id }
    val isLeader = group.leaderIds.contains(currentUser.id)
    val isPrincipalLeader = group.principalLeaderId == currentUser.id
    val canSeePaymentSemaphore = isLeader || myMember?.clubRole == ClubRole.TREASURER || myMember?.clubRole == ClubRole.PRESIDENT || myMember?.clubRole == ClubRole.VICE_PRESIDENT

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "${group.name} [${group.countryCode}]",
                    color = BikerColors.PrimaryAmber,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "Líderes activos: ${group.leaderIds.size}/3 · pHash: ${group.logoPhashHex}",
                    color = BikerColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        HorizontalDivider(color = BikerColors.SkeletonBaseDark)

        Text(
            text = stringResource(id = R.string.groups_members_title),
            color = BikerColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )

        group.members.forEach { member ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BikerColors.BackgroundDark, RoundedCornerShape(8.dp))
                    .clickable { onSelectMember(member.userId) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(BikerColors.SkeletonBaseDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.displayName.take(2).uppercase(),
                            color = BikerColors.PrimaryAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = member.displayName,
                                color = BikerColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            if (member.isFounder) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    painter = painterResource(id = AppIcons.FounderFlameRes),
                                    contentDescription = "Fundador",
                                    tint = BikerColors.PrimaryAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else if (member.isLeader) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    painter = painterResource(id = AppIcons.LeaderCrownShieldRes),
                                    contentDescription = "Líder",
                                    tint = BikerColors.PrimaryAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = buildString {
                                append("Estatus: ${member.statusKind.apiValue.uppercase()}")
                                if (member.clubRole != null) {
                                    append(" · Cargo: ${member.clubRole.apiValue}")
                                }
                                append(" · Rango: ${RiderRank.fromValidRides(member.validRides).name}")
                            },
                            color = BikerColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                if (canSeePaymentSemaphore) {
                    PaymentSemaphoreBadge(semaphore = member.paymentSemaphore)
                }
            }
        }

        // Invitar miembro por QR (Restringido estrictamente a Presidente y Vicepresidente)
        val canShareQr = GroupInviteQrHelper.canShareQr(isLeader, myMember?.clubRole)
        var showQrDialog by remember { mutableStateOf(false) }

        HorizontalDivider(color = BikerColors.SkeletonBaseDark)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BikerColors.BackgroundDark, RoundedCornerShape(8.dp))
                .border(1.dp, if (canShareQr) BikerColors.PrimaryAmber.copy(alpha = 0.5f) else BikerColors.SkeletonBaseDark, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.QrCode,
                    contentDescription = null,
                    tint = if (canShareQr) BikerColors.PrimaryAmber else BikerColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Invitación Oficial con Código QR",
                    color = if (canShareQr) BikerColors.PrimaryAmber else BikerColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            if (canShareQr) {
                Text(
                    text = "Autorizado: El Presidente y Vicepresidente pueden generar el código QR para que los nuevos miembros lo escaneen directamente con su cámara.",
                    color = BikerColors.TextSecondary,
                    fontSize = 12.sp
                )

                Button(
                    onClick = {
                        UxTestTracker.logEvent("GROUPS_D3", "GENERATE_INVITE_QR", "btn_show_qr", "groupId=${group.id}")
                        showQrDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BikerColors.PrimaryAmber,
                        contentColor = BikerColors.BackgroundDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = AppIcons.QrCode, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Mostrar QR de Invitación", fontWeight = FontWeight.Bold)
                }

                if (showQrDialog) {
                    val qrPayload = remember(group) {
                        GroupInviteQrHelper.encodePayload(
                            GroupInviteQrHelper.InviteQrPayload(
                                groupId = group.id,
                                inviteCode = "RUTA-TEST-01",
                                groupName = group.name,
                                countryCode = group.countryCode
                            )
                        )
                    }
                    val qrBitmap = remember(qrPayload) {
                        runCatching { GroupInviteQrHelper.generateQrBitmap(qrPayload, 512) }.getOrNull()
                    }

                    AlertDialog(
                        onDismissRequest = { showQrDialog = false },
                        title = {
                            Text(
                                text = "QR de Invitación · ${group.name}",
                                color = BikerColors.PrimaryAmber,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (qrBitmap != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(230.dp)
                                            .background(Color.White, RoundedCornerShape(8.dp))
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = qrBitmap.asImageBitmap(),
                                            contentDescription = "Código QR de Invitación",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                } else {
                                    Text("Error al generar matriz de imagen QR", color = BikerColors.StatusRed)
                                }

                                Text(
                                    text = "Código de respaldo: RUTA-TEST-01",
                                    color = BikerColors.PrimaryAmber,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )

                                Text(
                                    text = "Autorizado por: ${myMember?.clubRole?.apiValue?.uppercase() ?: if (isLeader) "PRESIDENTE" else "DIRECTIVA"}",
                                    color = BikerColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = { showQrDialog = false }) {
                                Text("Cerrar")
                            }
                        },
                        containerColor = BikerColors.SurfaceDark
                    )
                }
            } else {
                Text(
                    text = "🔒 Solo el Presidente y Vicepresidente del grupo tienen autorización para generar el código QR de invitación.",
                    color = BikerColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Invitar miembro manual por ID de usuario
        if (isLeader) {
            HorizontalDivider(color = BikerColors.SkeletonBaseDark)
            Text(
                text = "Invitar Miembro por ID de Usuario",
                color = BikerColors.PrimaryAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            OutlinedTextField(
                value = inviteUserId,
                onValueChange = { inviteUserId = it },
                label = { Text("ID de usuario o código manual") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemberStatusKind.entries.forEach { st ->
                    FilterChip(
                        selected = inviteStatus == st,
                        onClick = { inviteStatus = st },
                        label = { Text(st.apiValue.uppercase()) }
                    )
                }
            }
            Button(
                onClick = { onInviteMember(inviteUserId.trim(), inviteStatus) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BikerColors.PrimaryAmber,
                    contentColor = BikerColors.BackgroundDark
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = AppIcons.InviteMember, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(id = R.string.groups_invite_btn), fontWeight = FontWeight.Bold)
            }
        }

        // D8. Votaciones sensibles de líderes (RULES R14.2)
        if (group.openVotes.isNotEmpty() && isLeader) {
            HorizontalDivider(color = BikerColors.SkeletonBaseDark)
            Text(
                text = stringResource(id = R.string.groups_votes_title),
                color = BikerColors.PrimaryAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            group.openVotes.forEach { vote ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BikerColors.BackgroundDark, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Propuesta: ${vote.proposal} (${vote.targetLabel}) · Estado: ${vote.state.uppercase()}",
                        color = BikerColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Votos a favor: ${vote.approvals.size}/${vote.requiredVotes} requeridos",
                        color = BikerColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onVote(vote.id, true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BikerColors.StatusGreen,
                                contentColor = BikerColors.BackgroundDark
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Aprobar", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { onVote(vote.id, false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Rechazar", color = BikerColors.StatusRed)
                        }
                    }
                }
            }
        }

        // D9. Transferencia de grupo con espera de 7 días (RULES R5.3)
        if (isPrincipalLeader) {
            HorizontalDivider(color = BikerColors.SkeletonBaseDark)
            Text(
                text = stringResource(id = R.string.groups_transfer_title),
                color = BikerColors.PrimaryAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            if (group.pendingTransferToUserId != null) {
                Text(
                    text = "⏳ En espera de 7 días para transferir a ${group.pendingTransferToUserId}",
                    color = BikerColors.StatusYellow,
                    fontSize = 12.sp
                )
            } else {
                OutlinedTextField(
                    value = transferTargetId,
                    onValueChange = { transferTargetId = it },
                    label = { Text("ID de miembro con cuenta Líder activa") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { onTransfer(transferTargetId.trim()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = AppIcons.TransferGroup, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Iniciar transferencia (7 días espera)", color = BikerColors.PrimaryAmber)
                }
            }
        }
    }
}

/**
 * D4. Detalle de miembro (SCREENS.md D4): cambiar estatus o cargo respetando R2.4 y R3.3.
 */
@Composable
private fun MemberDetailScreenD4(
    group: ClubGroupModel,
    member: ClubMemberModel,
    isCurrentUserLeader: Boolean,
    onUpdateMember: (MemberStatusKind, ClubRole?) -> Unit,
    onBack: () -> Unit
) {
    var selectedStatus by remember(member) { mutableStateOf(member.statusKind) }
    var selectedRole by remember(member) { mutableStateOf(member.clubRole) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "D4 · Ficha de ${member.displayName}",
                color = BikerColors.PrimaryAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }

        Text(
            text = "Moto: ${member.bikeModel} · Rodadas válidas: ${member.validRides} (${RiderRank.fromValidRides(member.validRides).name})",
            color = BikerColors.TextSecondary,
            fontSize = 13.sp
        )

        if (isCurrentUserLeader) {
            Text(
                text = "Estatus en ${group.name} (RULES R2):",
                color = BikerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemberStatusKind.entries.forEach { st ->
                    FilterChip(
                        selected = selectedStatus == st,
                        onClick = {
                            selectedStatus = st
                            if (st == MemberStatusKind.GUEST) {
                                selectedRole = null
                            }
                        },
                        label = { Text(st.apiValue.uppercase()) }
                    )
                }
            }

            Text(
                text = "Cargo del club (RULES R3.1 · Prohibido en Invitados R2.4):",
                color = BikerColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = selectedRole == null,
                    onClick = { selectedRole = null },
                    label = { Text("Sin cargo") }
                )
                ClubRole.entries.forEach { role ->
                    FilterChip(
                        selected = selectedRole == role,
                        onClick = { selectedRole = role },
                        label = { Text(role.apiValue.uppercase()) }
                    )
                }
            }

            Button(
                onClick = { onUpdateMember(selectedStatus, selectedRole) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BikerColors.PrimaryAmber,
                    contentColor = BikerColors.BackgroundDark
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Guardar estatus y cargo", fontWeight = FontWeight.Bold)
            }
        }
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

/**
 * F1. Perfil personal + A2 Login + A3/A4 Onboarding (SCREENS.md F1, A2, A3, A4).
 */
@Composable
fun ProfileAndAuthTabScreen(viewModel: Phase1ViewModel) {
    val user by viewModel.repository.currentUser.collectAsState()
    val forceSkeleton by viewModel.forceSkeletonPreview.collectAsState()
    val banner by viewModel.statusBanner.collectAsState()

    var displayName by remember(user.id) { mutableStateOf(user.displayName) }
    var bikeModel by remember(user.id) { mutableStateOf(user.bikeModel) }
    var mapColorHex by remember(user.id) { mutableStateOf(user.mapColorHex) }
    var showAuthFlow by remember { mutableStateOf(false) }

    var loginEmail by remember { mutableStateOf("") }
    var loginName by remember { mutableStateOf("") }
    var loginCountry by remember { mutableStateOf("MX") }
    var acceptedTerms by remember { mutableStateOf(true) }
    var activateLeaderPlan by remember { mutableStateOf(false) }

    val profileState: UiState<UserProfileModel> = if (forceSkeleton) {
        UiState.Loading
    } else {
        viewModel.repository.resolveUiState(user) { false }
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
            text = stringResource(id = R.string.profile_title),
            color = BikerColors.PrimaryAmber,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        if (banner != null) {
            Text(
                text = banner ?: "",
                color = BikerColors.StatusGreen,
                fontSize = 13.sp
            )
        }

        StateHost(
            state = profileState,
            skeleton = { SkeletonProfileHeader() },
            empty = {},
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
        ) { profile, _ ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(BikerColors.SkeletonBaseDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = profile.initials,
                            color = BikerColors.PrimaryAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profile.displayName,
                                color = BikerColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            if (profile.hasFounderFlame) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    painter = painterResource(id = AppIcons.FounderFlameRes),
                                    contentDescription = "Fundador",
                                    tint = BikerColors.PrimaryAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            text = "Cuenta: ${profile.accountType.name} · Rango: ${profile.rank.name} (${profile.validRides} rodadas válidas)",
                            color = BikerColors.TextSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Avatar 64×64 WebP (~3 KB) listo para sincronización pre-rodada",
                            color = BikerColors.StatusGreen,
                            fontSize = 11.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Apodo en el mapa y voz") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bikeModel,
                    onValueChange = { bikeModel = it },
                    label = { Text("Moto") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mapColorHex,
                    onValueChange = { mapColorHex = it },
                    label = { Text("Color en el mapa (#RRGGBB)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                            "PROFILE_TAB",
                            "SAVE_PROFILE",
                            "btn_guardar_perfil",
                            "displayName=$displayName;bike=$bikeModel;color=$mapColorHex"
                        )
                        viewModel.saveUserProfile(displayName, bikeModel, mapColorHex)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BikerColors.PrimaryAmber,
                        contentColor = BikerColors.BackgroundDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.profile_save), fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                            "PROFILE_TAB",
                            "TOGGLE_AUTH_FLOW",
                            "btn_abrir_auth"
                        )
                        showAuthFlow = !showAuthFlow
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.profile_open_auth), color = BikerColors.PrimaryAmber)
                }

                if (showAuthFlow) {
                    HorizontalDivider(color = BikerColors.SkeletonBaseDark)
                    Text(
                        text = "Iniciar Sesión o Crear Cuenta",
                        color = BikerColors.PrimaryAmber,
                        fontWeight = FontWeight.Bold
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
                            containerColor = BikerColors.PrimaryAmber,
                            contentColor = BikerColors.BackgroundDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Iniciar sesión / Registrar cuenta", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
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
                        viewModel.repository.loadDemoData()
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
