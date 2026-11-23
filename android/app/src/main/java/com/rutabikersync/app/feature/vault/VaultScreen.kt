package com.rutabikersync.app.feature.vault

import android.app.Activity
import android.view.WindowManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.SecureFlagPolicy
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.rutabikersync.app.feature.sos.EmergencyLockScreenActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.DatePickerDialog
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.rutabikersync.app.R
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.core.mesh.RideLifecycleState
import com.rutabikersync.app.feature.groups.Phase1ViewModel
import com.rutabikersync.app.ui.components.HeritageBevelCard
import com.rutabikersync.app.ui.components.HeritageBrassButton
import com.rutabikersync.app.ui.components.HeritageEmblemBadge
import com.rutabikersync.app.ui.theme.HeritageTheme
import com.rutabikersync.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

@Composable
fun VaultScreen(
    viewModel: VaultViewModel = viewModel(),
    phase1ViewModel: Phase1ViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rawDocuments by viewModel.documents.collectAsState()
    val hookDocuments by VaultTestHook.overrideDocuments.collectAsState()
    val documents = hookDocuments ?: rawDocuments
    val medicalSheet by viewModel.medicalSheet.collectAsState()
    val isBackingUp by viewModel.isBackingUp.collectAsState()
    val isRestoring by viewModel.isRestoring.collectAsState()
    val recoveryError by viewModel.recoveryError.collectAsState()
    val recoverySuccessMessage by viewModel.recoverySuccessMessage.collectAsState()
    val isSharingMedical by viewModel.isSharingMedical.collectAsState()
    val shareMedicalSuccess by viewModel.shareMedicalSuccess.collectAsState()
    val shareMedicalError by viewModel.shareMedicalError.collectAsState()
    val viewedMemberMedicalSheet by viewModel.viewedMemberMedicalSheet.collectAsState()
    val fetchMedicalError by viewModel.fetchMedicalError.collectAsState()
    val isRotatingMnemonic by viewModel.isRotatingMnemonic.collectAsState()
    val rotatedMnemonic by viewModel.rotatedMnemonic.collectAsState()
    val rotateError by viewModel.rotateError.collectAsState()

    val isRevokingMedical by viewModel.isRevokingMedical.collectAsState()
    val revokeMedicalSuccess by viewModel.revokeMedicalSuccess.collectAsState()
    val revokeMedicalError by viewModel.revokeMedicalError.collectAsState()

    var showShareMedicalDialog by remember { mutableStateOf(false) }
    var showViewMemberDialog by remember { mutableStateOf(false) }
    var showSecuritySettingsDialog by remember { mutableStateOf(false) }
    var showConfirmRotateDialog by remember { mutableStateOf(false) }
    var showRotateMnemonicDialog by remember { mutableStateOf(false) }
    var showConfirmRevokeDialog by remember { mutableStateOf(false) }

    var showAddDocDialog by remember { mutableStateOf(false) }
    var documentToEdit by remember { mutableStateOf<BikerDocument?>(null) }
    var showEditMedicalDialog by remember { mutableStateOf(false) }
    var documentToDelete by remember { mutableStateOf<BikerDocument?>(null) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var mnemonicWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var recoveryInputText by remember { mutableStateOf("") }

    if (com.rutabikersync.app.BuildConfig.DEBUG) {
        val hookShowDialog by VaultTestHook.showRestoreDialogFlow.collectAsState()
        val hookInput by VaultTestHook.restoreInputFlow.collectAsState()
        val hookTrigger by VaultTestHook.restoreTrigger.collectAsState()

        LaunchedEffect(hookShowDialog) {
            if (hookShowDialog) {
                showRestoreDialog = true
            }
        }
        LaunchedEffect(hookInput) {
            if (hookInput.isNotEmpty()) {
                recoveryInputText = hookInput
            }
        }
        LaunchedEffect(hookTrigger) {
            val words = hookTrigger
            if (words != null && words.isNotEmpty()) {
                VaultTestHook.restoreTrigger.value = null
                viewModel.restoreVault(words) { res ->
                    if (res.isSuccess) {
                        Toast.makeText(context, "Bóveda recuperada exitosamente", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val hookShowEditMedical by VaultTestHook.showEditMedicalDialogFlow.collectAsState()
        val hookShowShareMedical by VaultTestHook.showShareMedicalDialogFlow.collectAsState()
        val hookShowViewMemberMedical by VaultTestHook.showViewMemberMedicalDialogFlow.collectAsState()
        val hookTargetUserId by VaultTestHook.testTargetUserId.collectAsState()
        val hookRideId by VaultTestHook.testActiveRideId.collectAsState()
        val hookGroupId by VaultTestHook.testActiveGroupId.collectAsState()
        val hookShareTrigger by VaultTestHook.testShareTrigger.collectAsState()
        val hookShowSecuritySettings by VaultTestHook.showSecuritySettingsFlow.collectAsState()
        val hookTriggerRotate by VaultTestHook.triggerRotateFlow.collectAsState()

        LaunchedEffect(hookShowEditMedical) {
            if (hookShowEditMedical) {
                showEditMedicalDialog = true
            }
        }
        LaunchedEffect(hookShowShareMedical) {
            showShareMedicalDialog = hookShowShareMedical
        }
        LaunchedEffect(hookShowViewMemberMedical) {
            if (hookShowViewMemberMedical) {
                val uId = VaultTestHook.testTargetUserId.value ?: hookTargetUserId ?: ""
                val rId = VaultTestHook.testActiveRideId.value ?: hookRideId ?: ""
                viewModel.fetchMemberMedicalSheet(uId, rId)
                showViewMemberDialog = true
                VaultTestHook.showViewMemberMedicalDialogFlow.value = false
            }
        }
        LaunchedEffect(hookShareTrigger) {
            if (hookShareTrigger) {
                val rId = VaultTestHook.testActiveRideId.value ?: hookRideId ?: ""
                val gId = VaultTestHook.testActiveGroupId.value ?: hookGroupId ?: ""
                viewModel.shareMedicalSheet(rId, gId)
                VaultTestHook.testShareTrigger.value = false
            }
        }
        LaunchedEffect(hookShowSecuritySettings) {
            if (hookShowSecuritySettings) {
                showSecuritySettingsDialog = true
            }
        }
        LaunchedEffect(hookTriggerRotate) {
            if (hookTriggerRotate) {
                viewModel.rotateMnemonic {
                    showRotateMnemonicDialog = true
                }
                VaultTestHook.triggerRotateFlow.value = false
            }
        }

        val hookTriggerRevoke by VaultTestHook.triggerRevokeMedicalFlow.collectAsState()
        val hookTriggerReshare by VaultTestHook.triggerReshareMedicalFlow.collectAsState()

        LaunchedEffect(hookTriggerRevoke) {
            val rId = hookTriggerRevoke
            if (rId != null && rId.isNotEmpty()) {
                showShareMedicalDialog = false
                viewModel.revokeMedicalSheet(rId)
                VaultTestHook.triggerRevokeMedicalFlow.value = null
            }
        }
        LaunchedEffect(hookTriggerReshare) {
            if (hookTriggerReshare) {
                val rId = viewModel.lastSharedRideId ?: VaultTestHook.testActiveRideId.value ?: ""
                val gId = viewModel.lastSharedGroupId ?: VaultTestHook.testActiveGroupId.value ?: ""
                viewModel.shareMedicalSheet(rId, gId)
                VaultTestHook.triggerReshareMedicalFlow.value = false
            }
        }
    }

    // Conteo de alertas de vencimiento
    val now = System.currentTimeMillis()
    val expiringCount = remember(documents) {
        documents.count {
            val info = it.computeExpirationInfo(now)
            info.severity == ExpirationSeverity.EXPIRING_SOON ||
                    info.severity == ExpirationSeverity.CRITICAL ||
                    info.severity == ExpirationSeverity.EXPIRED
        }
    }

    // 5.2: Puerta biométrica — el contenido solo se muestra tras autenticación
    VaultBiometricGate(modifier = modifier) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cabecera oficial Bóveda (Screen 4 / SCREENS F4) con escudo en relieve
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeritageEmblemBadge(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Bóveda Biker",
                    badgeSize = 48.dp,
                    iconSize = 24.dp,
                    glowColor = Color(0xFFE5B869)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.vault_title),
                        color = HeritageTheme.colors.textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isBright = HeritageTheme.isBrightRider
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isBright) Color(0xFFE8F8F0) else Color(0xFF10B981).copy(alpha = 0.15f))
                                .border(1.dp, if (isBright) Color(0xFFA7F3D0) else Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.vault_local_aes),
                                color = if (isBright) Color(0xFF059669) else Color(0xFF34D399),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isBright) Color(0xFFFEF3C7) else Color(0xFFF59E0B).copy(alpha = 0.15f))
                                .border(1.dp, if (isBright) Color(0xFFFDE68A) else Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "\uD83D\uDD12 Huella",
                                color = if (isBright) Color(0xFFD97706) else Color(0xFFFBBF24),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "R10.1 Personal",
                            color = HeritageTheme.colors.textSecondary,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
                IconButton(
                    onClick = { showSecuritySettingsDialog = true }
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Ajustes de Seguridad E2EE",
                        tint = if (HeritageTheme.isBrightRider) HeritageCopper else Color(0xFFD4AF37),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Barra de Respaldo y Recuperación BIP-0039 E2EE
            val hasMnemonic = viewModel.hasMnemonicBackedUp
            val hasMasterKey = viewModel.hasMasterKey

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!hasMnemonic) {
                    // Botón prioritario de respaldo solo si aún no está resguardada
                    Button(
                        onClick = {
                            mnemonicWords = viewModel.getOrGenerateMnemonic()
                            viewModel.clearRecoveryStatus()
                            viewModel.backupMasterKey(mnemonicWords)
                            showBackupDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD4AF37),
                            contentColor = Color(0xFF0F172A)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Respaldar Bóveda (12 Palabras)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                } else {
                    // Badge de bóveda resguardada si la mnemónica ya fue configurada
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .clickable {
                                mnemonicWords = viewModel.getOrGenerateMnemonic()
                                viewModel.clearRecoveryStatus()
                                showBackupDialog = true
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                            Text(
                                text = "Bóveda Resguardada (E2EE)",
                                color = Color(0xFF34D399),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Botón Recuperar Bóveda: VISIBLE ÚNICAMENTE si no hay Master Key en este dispositivo
                if (!hasMasterKey) {
                    OutlinedButton(
                        onClick = {
                            viewModel.clearRecoveryStatus()
                            recoveryInputText = ""
                            showRestoreDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, HeritageCopper),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF1E293B)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = HeritageCopper
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Recuperar Bóveda",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }
            }

            // Tira de resumen y advertencia de vencimientos vibrante
            if (expiringCount > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 4.dp,
                            shape = RoundedCornerShape(14.dp),
                            ambientColor = Color(0x30F59E0B),
                            spotColor = Color(0x40D97706)
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFFEF3C7),
                                    Color(0xFFFFEDD5),
                                    Color(0xFFFDE68A)
                                )
                            )
                        )
                        .border(1.5.dp, Color(0xFFF59E0B), RoundedCornerShape(14.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 0.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tira vertical acentuada en el borde izquierdo
                        Box(
                            modifier = Modifier
                                .width(5.dp)
                                .height(46.dp)
                                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                                .background(Color(0xFFEA580C))
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        // Medallón vibrante con icono de alerta
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF59E0B))
                                .border(1.5.dp, Color(0xFFD97706), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Atención: $expiringCount documento(s) requieren renovación",
                                color = Color(0xFF9A3412),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.5.sp
                            )
                            Text(
                                text = "Revisa las fechas marcadas con alerta antes de salir a rodar.",
                                color = Color(0xFF7C2D12),
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Chip indicador de acción urgente
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFEA580C))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.vault_btn_review_urgent),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            // Sección: Documentos de la Moto (SCREENS F4)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.vault_docs_header),
                            color = HeritageTheme.colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.vault_docs_registered_count, documents.size),
                            color = HeritageTheme.colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            documentToEdit = null
                            showAddDocDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HeritageCopper,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.AddCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.vault_btn_add_capital), fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                }

                // Lista de documentos categorizados
                if (documents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                    ) {
                        // Imagen a sangre en toda la tarjeta: blindaje, hermandad y documentos.
                        Image(
                            painter = painterResource(id = R.drawable.card_boveda_vacia),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            alignment = Alignment.Center,
                            contentScale = ContentScale.Crop
                        )
                        // Degradado inferior: respeta la imagen y asegura la lectura del texto.
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color(0xFF0F172A).copy(alpha = 0.55f),
                                            Color(0xFF0F172A)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B))
                                    .border(1.5.dp, Color(0xFFD4AF37).copy(alpha = 0.6f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color(0xFFD4AF37),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Text(
                                text = "Tu Bóveda está vacía",
                                color = Color(0xFFF1F5F9),
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Guarda tu licencia, seguro y ficha médica para que estén protegidos y disponibles cuando los necesites.",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                            Button(
                                onClick = {
                                    documentToEdit = null
                                    showAddDocDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD4AF37),
                                    contentColor = Color(0xFF0F172A)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Registrar Primer Documento", fontWeight = FontWeight.Black, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    val personalDocs = documents.filter { it.type == BikerDocumentType.LICENSE }
                    val vehicleDocs = documents.filter { it.type != BikerDocumentType.LICENSE }

                    if (personalDocs.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        ) {
                            Text("🚪", fontSize = 13.sp)
                            Text(
                                text = "DOCUMENTOS PERSONALES (${personalDocs.size})",
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp
                            )
                        }
                        personalDocs.forEach { doc ->
                            DocumentCardItem(
                                doc = doc,
                                onEdit = {
                                    documentToEdit = doc
                                    showAddDocDialog = true
                                },
                                onDelete = { documentToDelete = doc }
                            )
                        }
                    }

                    if (vehicleDocs.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        ) {
                            Text("🛡️", fontSize = 13.sp)
                            Text(
                                text = "VEHÍCULO Y SEGUROS (${vehicleDocs.size})",
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp
                            )
                        }
                        vehicleDocs.forEach { doc ->
                            DocumentCardItem(
                                doc = doc,
                                onEdit = {
                                    documentToEdit = doc
                                    showAddDocDialog = true
                                },
                                onDelete = { documentToDelete = doc }
                            )
                        }
                    }
                }
            }

        // Sección: Ficha Médica de Emergencia (Sellado con Shamir / RULES R10.2 - R10.5)
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            val isBright = HeritageTheme.isBrightRider
            // Franja táctica: la misma imagen queda detrás del encabezado y de grupo sanguíneo/donante.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.card_ficha_medica_emergencia),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
                // Degradado idéntico al de la Bóveda: foto nítida arriba y fundido a Dark Slate abajo.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFF0F172A).copy(alpha = 0.55f),
                                    Color(0xFF0F172A)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(85.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        HeritageEmblemBadge(
                            imageVector = Icons.Default.LocalHospital,
                            contentDescription = null,
                            badgeSize = 36.dp,
                            iconSize = 18.dp,
                            glowColor = Color(0xFFEF4444)
                        )
                        Column {
                            Text(
                                text = "Ficha Médica de Emergencia",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Solo visible para líderes en caso de SOS (R10.2)",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    val isMedicalComplete = remember(medicalSheet) {
                        MedicalSheetValidator.isComplete(medicalSheet)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isMedicalComplete) {
                                    if (isBright) Color(0xFFE8F8F0) else Color(0xFF10B981).copy(alpha = 0.15f)
                                } else {
                                    if (isBright) Color(0xFFFEF3C7) else Color(0xFFF59E0B).copy(alpha = 0.15f)
                                }
                            )
                            .border(
                                1.dp,
                                if (isMedicalComplete) {
                                    if (isBright) Color(0xFFA7F3D0) else Color(0xFF10B981).copy(alpha = 0.4f)
                                } else {
                                    if (isBright) Color(0xFFFDE68A) else Color(0xFFF59E0B).copy(alpha = 0.4f)
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isMedicalComplete) "🟢 Ficha Completa" else "🟡 Ficha Incompleta",
                            color = if (isMedicalComplete) {
                                if (isBright) Color(0xFF059669) else Color(0xFF34D399)
                            } else {
                                if (isBright) Color(0xFFD97706) else Color(0xFFFBBF24)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                    }

                    // Fila de Grupo Sanguíneo y Donante sobre la misma imagen de fondo.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                // Badge Tipo de Sangre: sin opacidad, la foto se ve nítida detrás.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(HeritageRustRed.copy(alpha = 0.15f))
                                .border(1.dp, HeritageRustRed.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "🩸",
                                fontSize = 16.sp
                            )
                        }
                        Column(verticalArrangement = Arrangement.Center) {
                            Text(
                                text = "GRUPO SANGUÍNEO",
                                color = HeritageRustRed,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = medicalSheet.bloodType,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                // Badge Donante de Órganos: sin opacidad, mismo trato que el de sangre.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(HeritageCopper.copy(alpha = 0.15f))
                                .border(1.dp, HeritageCopper.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = HeritageCopper,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.Center) {
                            Text(
                                text = "DONANTE ÓRGANOS",
                                color = HeritageCopper,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = if (medicalSheet.isOrganDonor) "Registrado" else "No especificado",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Datos médicos tabulares con esquinas uniformes de 12dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HeritageCardWarm)
                    .border(1.dp, HeritageBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MedicalRow(label = "Alergias Conocidas", value = medicalSheet.allergies)
                MedicalRow(label = "Condiciones Crónicas", value = medicalSheet.chronicConditions)
                MedicalRow(label = "Medicamentos Habituales", value = medicalSheet.habitualMedications)
                MedicalRow(label = "Seguro Médico Privado", value = "${medicalSheet.medicalInsuranceName} (${medicalSheet.insurancePolicyNumber})")

                HorizontalDivider(color = HeritageBorder, thickness = 1.dp)

                // Contacto SOS con botón de llamada directa
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CONTACTO SOS PRIORITARIO", color = HeritageCopper, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${medicalSheet.emergencyContactName} (${medicalSheet.emergencyContactRelationship})",
                            color = HeritageLeatherBrown,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = medicalSheet.emergencyContactPhone,
                            color = HeritageLeatherMuted,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_DIAL).apply {
                                data = Uri.parse("tel:${medicalSheet.emergencyContactPhone}")
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(HeritageGreen.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Llamar contacto de emergencia",
                            tint = HeritageGreen
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Switch "Mostrar en pantalla de bloqueo"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Mostrar en pantalla de bloqueo",
                        color = if (isBright) Color(0xFF183153) else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Permite acceso a paramédicos sin desbloquear el móvil",
                        color = if (isBright) Color(0xFF64748B) else Color(0xFF9CA3AF),
                        fontSize = 10.sp
                    )
                }
                Switch(
                    checked = medicalSheet.showOnLockScreen,
                    onCheckedChange = { viewModel.toggleLockScreen(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = if (isBright) Color(0xFF1769E0) else Color(0xFFF3C766),
                        uncheckedThumbColor = if (isBright) Color(0xFF94A3B8) else Color(0xFF8E95A5),
                        uncheckedTrackColor = if (isBright) Color(0xFFE2E8F0) else Color(0xFF272B36)
                    )
                )
            }

            if (medicalSheet.showOnLockScreen) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        val intent = EmergencyLockScreenActivity.createIntent(context)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HeritageCardWarm,
                        contentColor = HeritageCopper
                    ),
                    border = BorderStroke(1.dp, HeritageBorder),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = HeritageCopper
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Previsualizar en Pantalla de Bloqueo ➔",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Botones de acción: Editar y Compartir con Rodada
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showEditMedicalDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBright) HeritageCardWarm else Color(0xFF1E293B),
                        contentColor = if (isBright) HeritageCopper else Color(0xFFD4AF37)
                    ),
                    border = BorderStroke(1.dp, if (isBright) HeritageBorder else Color(0xFF334155)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (isBright) HeritageCopper else Color(0xFFD4AF37))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Editar Ficha", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        viewModel.clearShareMedicalStatus()
                        showShareMedicalDialog = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0F766E),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Compartir", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }

            val lastShared = viewModel.lastSharedAtMillis
            val isOutdated = viewModel.isSharedMedicalOutdated
            val lastRideId = viewModel.lastSharedRideId ?: ""
            val lastGroupId = viewModel.lastSharedGroupId ?: ""

            // Banner reactivo de revocación o error
            if (revokeMedicalSuccess != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF065F46))
                        .border(1.dp, Color(0xFF10B981), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "✅ $revokeMedicalSuccess",
                        color = Color(0xFFA7F3D0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (revokeMedicalError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF7F1D1D))
                        .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "⚠️ $revokeMedicalError",
                        color = Color(0xFFFECACA),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Hito 4: Alerta ámbar de modificación local (inmutabilidad criptográfica)
            if (lastShared != null && lastShared > 0L && isOutdated) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isBright) Color(0xFFFEF3C7) else Color(0xFF78350F).copy(alpha = 0.35f))
                        .border(1.dp, if (isBright) Color(0xFFF59E0B) else Color(0xFFD97706), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("⚠️", fontSize = 14.sp)
                            Text(
                                text = "Tu ficha fue modificada localmente. La copia en posesión de los líderes no incluye estos cambios. Pulsa [Volver a compartir] para actualizar.",
                                color = if (isBright) Color(0xFF92400E) else Color(0xFFFDE68A),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.shareMedicalSheet(lastRideId, lastGroupId)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD97706),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Volver a compartir", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Hito 4: Timestamp visible y botón de Revocación Anticipada (Hito 3)
            if (lastShared != null && lastShared > 0L) {
                Spacer(modifier = Modifier.height(8.dp))
                val sdf = remember { java.text.SimpleDateFormat("dd/MM/yyyy 'a las' HH:mm", java.util.Locale.getDefault()) }
                Text(
                    text = "📡 Compartida con el club el ${sdf.format(java.util.Date(lastShared))}",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )

                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { showConfirmRevokeDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFEF4444)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFEF4444))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Detener Compartición", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFEF4444))
                }
            }
        }

        // Descargo legal y de seguridad R10.5
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⚠️ Regla Biker R10.5: Los datos de la Bóveda no sustituyen a los servicios oficiales de salud ni a las autoridades viales.",
                color = Color(0xFF6B7280),
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(140.dp))
    }

    // Botón Flotante Permanente para Agregar Documento (FAB en BottomEnd elegante MD3)
    val isBright = HeritageTheme.isBrightRider
    FloatingActionButton(
        onClick = {
            documentToEdit = null
            showAddDocDialog = true
        },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(bottom = 84.dp, end = 20.dp),
        containerColor = HeritageCopper,
        contentColor = Color.White,
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = "Agregar Documento", modifier = Modifier.size(26.dp))
    }
}

    // Modal de Agregar / Editar Documento
    if (showAddDocDialog) {
        AddOrEditDocumentDialog(
            initialDoc = documentToEdit,
            onDismiss = {
                showAddDocDialog = false
                documentToEdit = null
            },
            onSave = { updatedDoc ->
                viewModel.saveDocument(updatedDoc)
                showAddDocDialog = false
                documentToEdit = null
                Toast.makeText(context, "Documento cifrado y guardado en la Bóveda", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Modal de Editar Ficha Médica
    if (showEditMedicalDialog) {
        EditMedicalSheetDialog(
            currentSheet = medicalSheet,
            onDismiss = { showEditMedicalDialog = false },
            onSave = { updatedSheet ->
                viewModel.saveMedicalSheet(updatedSheet)
                showEditMedicalDialog = false
                Toast.makeText(context, "Ficha médica sellada y guardada localmente", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Modal de Compartir Ficha Médica con Rodada
    if (showShareMedicalDialog) {
        val rideSessionState by RideService.sharedState.collectAsState()
        val clubGroups by phase1ViewModel.repository.groups.collectAsState()
        val userClub = clubGroups.firstOrNull()

        val hookRideId by VaultTestHook.testActiveRideId.collectAsState()
        val hookGroupId by VaultTestHook.testActiveGroupId.collectAsState()

        val activeRideId = VaultTestHook.testActiveRideId.value ?: hookRideId ?: rideSessionState.rideId
        val isRideActive = (VaultTestHook.testActiveRideId.value != null) || (hookRideId != null) || (rideSessionState.lifecycleState == RideLifecycleState.RUNNING && !activeRideId.isNullOrBlank())

        val activeGroupId = VaultTestHook.testActiveGroupId.value ?: hookGroupId ?: viewModel.lastSharedGroupId ?: userClub?.id ?: ""
        val targetClubName = userClub?.name ?: (if (activeGroupId.isNotBlank()) "Mi Club" else "Sin Club")

        val activeRideTitle = if (isRideActive) {
            rideSessionState.notification.title.takeIf { it.isNotBlank() && it != "Ruta Biker Sync" }
                ?: "Rodada en curso (${activeRideId?.take(8) ?: "Activa"})"
        } else {
            "Sin rodada activa"
        }

        ShareMedicalSheetDialog(
            isSharing = isSharingMedical,
            successMessage = shareMedicalSuccess,
            errorMessage = shareMedicalError,
            hasActiveRide = isRideActive,
            activeRideTitle = activeRideTitle,
            targetClubName = targetClubName,
            onDismiss = {
                showShareMedicalDialog = false
                viewModel.clearShareMedicalStatus()
            },
            onShare = {
                val rId = activeRideId ?: ""
                val gId = activeGroupId
                if (!isRideActive || rId.isBlank() || gId.isBlank()) {
                    viewModel.setShareMedicalError("No hay una rodada activa ni club vinculado para compartir la ficha (RULES R6.5)")
                } else {
                    viewModel.shareMedicalSheet(rId, gId)
                }
            }
        )
    }

    // Modal para Líder/Sargento de Armas visualizar ficha médica de miembro en rodada
    if (showViewMemberDialog) {
        ViewMemberMedicalSheetDialog(
            sheet = viewedMemberMedicalSheet,
            errorMessage = fetchMedicalError,
            onDismiss = {
                showViewMemberDialog = false
                viewModel.clearViewedMemberMedicalSheet()
            }
        )
    }

    // Modal de Confirmación para Detener Compartición (Hito 3)
    if (showConfirmRevokeDialog) {
        val lastRideId = viewModel.lastSharedRideId ?: ""
        AlertDialog(
            onDismissRequest = { showConfirmRevokeDialog = false },
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚠️", fontSize = 18.sp)
                    Text(
                        text = "¿Detener Compartición?",
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Text(
                    text = "¿Detener el acceso de tu club a tu ficha médica? Los líderes ya no podrán verla en caso de emergencia.",
                    color = Color(0xFFE2E8F0),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmRevokeDialog = false
                        viewModel.revokeMedicalSheet(lastRideId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Detener Acceso", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmRevokeDialog = false }) {
                    Text("Cancelar", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    // Confirmación de borrado
    if (documentToDelete != null) {
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            title = {
                Text("Â¿Eliminar Documento?", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Se eliminará '${documentToDelete?.title}' de la bóveda local cifrada. Esta acción no se puede deshacer.",
                    color = Color.White,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        documentToDelete?.let { viewModel.deleteDocument(it.id) }
                        documentToDelete = null
                        Toast.makeText(context, "Documento eliminado", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Eliminar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { documentToDelete = null }) {
                    Text("Cancelar")
                }
            },
            containerColor = if (HeritageTheme.isBrightRider) Color.White else Color(0xFF181A20)
        )
    }

    if (showBackupDialog) {
        BackupMnemonicDialog(
            words = mnemonicWords,
            isBackingUp = isBackingUp,
            successMessage = recoverySuccessMessage,
            errorMessage = recoveryError,
            onDismiss = {
                showBackupDialog = false
                viewModel.clearRecoveryStatus()
            }
        )
    }

    if (showRestoreDialog) {
        RestoreMnemonicDialog(
            isRestoring = isRestoring,
            errorMessage = recoveryError,
            successMessage = recoverySuccessMessage,
            inputText = recoveryInputText,
            onInputTextChanged = { recoveryInputText = it },
            onRestore = { words ->
                viewModel.restoreVault(words) { res ->
                    if (res.isSuccess) {
                        Toast.makeText(context, "Bóveda recuperada exitosamente", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = {
                showRestoreDialog = false
                viewModel.clearRecoveryStatus()
            }
        )
    }

    if (showSecuritySettingsDialog) {
        VaultSecuritySettingsDialog(
            onDismiss = { showSecuritySettingsDialog = false },
            onRotateClick = {
                showSecuritySettingsDialog = false
                showConfirmRotateDialog = true
            }
        )
    }

    if (showConfirmRotateDialog) {
        ConfirmRotateDialog(
            isRotating = isRotatingMnemonic,
            error = rotateError,
            onDismiss = {
                showConfirmRotateDialog = false
                viewModel.clearRotateStatus()
            },
            onConfirm = {
                viewModel.rotateMnemonic {
                    showConfirmRotateDialog = false
                    showRotateMnemonicDialog = true
                }
            }
        )
    }

    if (showRotateMnemonicDialog && rotatedMnemonic != null) {
        RotateMnemonicDialog(
            words = rotatedMnemonic ?: emptyList(),
            onDismissAndWipe = {
                viewModel.confirmAndWipeRotatedMnemonic()
                showRotateMnemonicDialog = false
                Toast.makeText(context, "Nuevas palabras de recuperación activas y resguardadas", Toast.LENGTH_SHORT).show()
            }
        )
    }
    } // end VaultBiometricGate
}

/**
 * Tarjeta individual de Documento Biker con Semáforo y Acciones Rápidas
 */
@Composable
private fun DocumentCardItem(
    doc: BikerDocument,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val expirationInfo = remember(doc.expirationDateMillis) {
        doc.computeExpirationInfo()
    }

    val isExpired = expirationInfo.severity == ExpirationSeverity.EXPIRED
    val isWarning = expirationInfo.severity == ExpirationSeverity.EXPIRING_SOON || expirationInfo.severity == ExpirationSeverity.CRITICAL

    val customCardBg = when {
        isExpired -> Color(0xFFFFF2F2)
        isWarning -> Color(0xFFFFFBEB)
        else -> null
    }

    val customCardBorder = when {
        isExpired -> Color(0xFFFCA5A5)
        isWarning -> Color(0xFFFCD34D)
        else -> null
    }

    val folioBg = when {
        isExpired -> Color(0xFFFEE2E2)
        isWarning -> Color(0xFFFEF3C7)
        else -> HeritageCardWarm
    }

    val folioBorder = when {
        isExpired -> Color(0xFFFCA5A5)
        isWarning -> Color(0xFFFDE68A)
        else -> HeritageBorder
    }

    val venceTextColor = when {
        isExpired -> Color(0xFFDC2626)
        isWarning -> Color(0xFFD97706)
        else -> HeritageCopper
    }

    val icon: ImageVector = when (doc.type) {
        BikerDocumentType.LICENSE -> Icons.Default.CreditCard
        BikerDocumentType.INSURANCE -> Icons.Default.VerifiedUser
        BikerDocumentType.CIRCULATION -> Icons.Default.Description
        BikerDocumentType.MECHANICAL_INSPECTION -> Icons.Default.Build
        BikerDocumentType.OTHER -> Icons.Default.Folder
    }

    HeritageBevelCard(
        modifier = Modifier.fillMaxWidth(),
        cardColor = customCardBg,
        borderColor = customCardBorder
    ) {
        val isBright = HeritageTheme.isBrightRider
        val statusAccentColor = when {
            isBright -> when (expirationInfo.severity) {
                ExpirationSeverity.VALID -> Color(0xFF10B981)
                ExpirationSeverity.EXPIRING_SOON -> Color(0xFFF59E0B)
                ExpirationSeverity.CRITICAL -> Color(0xFFEA580C)
                ExpirationSeverity.EXPIRED -> Color(0xFFEF4444)
                ExpirationSeverity.NO_EXPIRATION -> Color(0xFF94A3B8)
            }
            else -> expirationInfo.severity.textColor
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Indicador de vigencia en borde izquierdo
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(38.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(statusAccentColor)
                )
                HeritageEmblemBadge(
                    imageVector = icon,
                    contentDescription = null,
                    badgeSize = 40.dp,
                    iconSize = 20.dp,
                    glowColor = if (isBright) Color(0xFF1769E0) else expirationInfo.severity.textColor
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = doc.title,
                        color = HeritageTheme.colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = doc.issuerOrInsurer,
                        color = HeritageTheme.colors.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Semáforo y badge de foto adjunta
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val isBright = HeritageTheme.isBrightRider
                if (doc.photoUri != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HeritageCardWarm)
                            .border(1.dp, HeritageBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "📸 Scan",
                            color = HeritageCopper,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        )
                    }
                }

                // Semáforo de Vencimiento
                val badgeBg = when {
                    isBright -> when (expirationInfo.severity) {
                        ExpirationSeverity.VALID -> Color(0xFFE8F8F0)
                        ExpirationSeverity.EXPIRING_SOON -> Color(0xFFFEF3C7)
                        ExpirationSeverity.CRITICAL -> Color(0xFFFFEDD5)
                        ExpirationSeverity.EXPIRED -> Color(0xFFFEE2E2)
                        ExpirationSeverity.NO_EXPIRATION -> Color(0xFFF1F5F9)
                    }
                    else -> expirationInfo.severity.badgeBg
                }
                val badgeBorder = when {
                    isBright -> when (expirationInfo.severity) {
                        ExpirationSeverity.VALID -> Color(0xFFA7F3D0)
                        ExpirationSeverity.EXPIRING_SOON -> Color(0xFFFDE68A)
                        ExpirationSeverity.CRITICAL -> Color(0xFFFED7AA)
                        ExpirationSeverity.EXPIRED -> Color(0xFFFECACA)
                        ExpirationSeverity.NO_EXPIRATION -> Color(0xFFCBD5E1)
                    }
                    else -> expirationInfo.severity.badgeBorder
                }
                val badgeTextColor = when {
                    isBright -> when (expirationInfo.severity) {
                        ExpirationSeverity.VALID -> Color(0xFF059669)
                        ExpirationSeverity.EXPIRING_SOON -> Color(0xFFD97706)
                        ExpirationSeverity.CRITICAL -> Color(0xFFEA580C)
                        ExpirationSeverity.EXPIRED -> Color(0xFFDC2626)
                        ExpirationSeverity.NO_EXPIRATION -> Color(0xFF475569)
                    }
                    else -> expirationInfo.severity.textColor
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeBg)
                        .border(1.dp, badgeBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${expirationInfo.severity.iconEmoji} ${expirationInfo.severity.label}",
                        color = badgeTextColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Folio y Vencimiento
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(folioBg)
                .border(1.dp, folioBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("FOLIO:", color = HeritageLeatherMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = doc.folio.ifBlank { "N/A" },
                    color = HeritageLeatherBrown,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("VENCE:", color = HeritageLeatherMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = expirationInfo.formattedDate,
                    color = venceTextColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Barra de acciones Dark Elite (limpia y sin botones viejos)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E293B)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Editar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF1F5F9))
            }

            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E293B)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFF87171)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Eliminar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF87171))
            }
        }
    }
}


/**
 * Modal para Registrar o Editar un Documento en la Bóveda
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddOrEditDocumentDialog(
    initialDoc: BikerDocument?,
    onDismiss: () -> Unit,
    onSave: (BikerDocument) -> Unit
) {
    var selectedType by remember { mutableStateOf(initialDoc?.type ?: BikerDocumentType.LICENSE) }
    var titleInput by remember { mutableStateOf(initialDoc?.title ?: selectedType.title) }
    var folioInput by remember { mutableStateOf(initialDoc?.folio ?: "") }
    var issuerInput by remember { mutableStateOf(initialDoc?.issuerOrInsurer ?: selectedType.defaultIssuer) }
    var emergencyPhoneInput by remember { mutableStateOf(initialDoc?.emergencyPhone ?: "") }
    var notesInput by remember { mutableStateOf(initialDoc?.notes ?: "") }
    var photoPath by remember { mutableStateOf(initialDoc?.photoUri) }

    val context = LocalContext.current
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = VaultImageUtils.saveImageToVault(context, uri)
            if (saved != null) {
                photoPath = saved
                Toast.makeText(context, "Foto adjunta guardada de forma segura", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Fecha de vencimiento
    val now = System.currentTimeMillis()
    var expirationMillis by remember {
        mutableStateOf(initialDoc?.expirationDateMillis ?: (now + TimeUnit.DAYS.toMillis(365)))
    }

    val sdf = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val displaySdf = remember { SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale.getDefault()) }

    var dateText by remember { mutableStateOf(sdf.format(Date(expirationMillis))) }
    var dateParseError by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    var showDatePickerInline by remember { mutableStateOf(false) }

    val updateExpiration: (Long) -> Unit = { millis ->
        expirationMillis = millis
        dateText = sdf.format(Date(millis))
        dateParseError = false
    }

    val openDatePicker: () -> Unit = {
        focusManager.clearFocus()
        showDatePickerInline = !showDatePickerInline
    }

    val vaultFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = HeritageCopper,
        unfocusedBorderColor = HeritageBorder,
        focusedContainerColor = HeritageCard,
        unfocusedContainerColor = HeritageCard,
        focusedTextColor = HeritageLeatherBrown,
        unfocusedTextColor = HeritageLeatherBrown,
        focusedLabelColor = HeritageCopper,
        unfocusedLabelColor = HeritageLeatherMuted,
        cursorColor = HeritageCopper
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 64.dp)
                .systemBarsPadding()
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            color = HeritageSand,
            border = BorderStroke(1.dp, HeritageBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = if (initialDoc == null) "Agregar Documento a la Bóveda" else "Editar Documento",
                    color = HeritageLeatherBrown,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Tipo de Documento:", color = HeritageLeatherMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                // Selector de Tipo de Documento
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        BikerDocumentType.LICENSE to "Licencia",
                        BikerDocumentType.INSURANCE to "Seguro",
                        BikerDocumentType.CIRCULATION to "Circulación",
                        BikerDocumentType.MECHANICAL_INSPECTION to "Revisión"
                    ).forEach { (type, label) ->
                        val isSelected = selectedType == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) HeritageCopper else HeritageCardWarm)
                                .border(1.dp, if (isSelected) HeritageCopperDark else HeritageBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedType = type
                                    if (initialDoc == null) {
                                        titleInput = type.title
                                        issuerInput = type.defaultIssuer
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else HeritageLeatherBrown,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Sección para adjuntar Foto / Escaneo del documento (Inmediatamente visible arriba)
                if (photoPath != null) {
                    val bitmap = remember(photoPath) { VaultImageUtils.loadBitmap(photoPath!!) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, HeritageBorder, RoundedCornerShape(10.dp))
                            .background(HeritageCardWarm)
                            .padding(8.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Documento Adjunto",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { photoPickerLauncher.launch("image/*") }) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp), tint = HeritageCopper)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Cambiar Foto", color = HeritageCopper, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                TextButton(onClick = { photoPath = null }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = HeritageRustRed)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Quitar Foto", color = HeritageRustRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HeritageCardWarm,
                            contentColor = HeritageLeatherBrown
                        ),
                        border = BorderStroke(1.dp, HeritageBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(20.dp), tint = HeritageCopper)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ADJUNTAR FOTO / ESCANEO", fontSize = 11.sp, fontWeight = FontWeight.Black, color = HeritageLeatherBrown)
                    }
                }

                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Nombre / Título del documento") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = vaultFieldColors
                )

                OutlinedTextField(
                    value = folioInput,
                    onValueChange = { folioInput = it },
                    label = { Text("Folio, Placa o Número de Póliza") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = vaultFieldColors
                )

                OutlinedTextField(
                    value = issuerInput,
                    onValueChange = { issuerInput = it },
                    label = { Text("Aseguradora o Entidad Emisora") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = vaultFieldColors
                )

                OutlinedTextField(
                    value = emergencyPhoneInput,
                    onValueChange = { emergencyPhoneInput = it },
                    label = { Text("Teléfono de Asistencia Vial / Grúa (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = vaultFieldColors
                )

                // Fecha de vencimiento exacta y 100% real
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(HeritageCardWarm)
                        .border(1.dp, HeritageBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Fecha de Vencimiento",
                            color = HeritageLeatherBrown,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(HeritageCopper.copy(alpha = 0.15f))
                                .border(1.dp, HeritageCopper.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "100% Real",
                                color = HeritageCopperDark,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Campo de texto editable con icono para abrir calendario
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { input ->
                            dateText = input
                            try {
                                val parsed = sdf.parse(input)
                                if (parsed != null && input.length == 10) {
                                    val cal = Calendar.getInstance().apply {
                                        time = parsed
                                        set(Calendar.HOUR_OF_DAY, 23)
                                        set(Calendar.MINUTE, 59)
                                    }
                                    expirationMillis = cal.timeInMillis
                                    dateParseError = false
                                } else {
                                    dateParseError = input.length >= 10
                                }
                            } catch (_: Exception) {
                                dateParseError = true
                            }
                        },
                        label = { Text("Día / Mes / Año (dd/MM/yyyy)") },
                        placeholder = { Text("Ej. 15/05/2027") },
                        isError = dateParseError,
                        trailingIcon = {
                            IconButton(onClick = openDatePicker) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = "Abrir Calendario",
                                    tint = HeritageCopper
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = vaultFieldColors,
                        singleLine = true
                    )

                    // Botón para seleccionar fecha exacta en calendario nativo
                    Button(
                        onClick = openDatePicker,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HeritageCopper,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Event,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SELECCIONAR DÍA, MES Y AÑO",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    AnimatedVisibility(visible = showDatePickerInline) {
                        val datePickerState = rememberDatePickerState(
                            initialSelectedDateMillis = expirationMillis
                        )
                        LaunchedEffect(datePickerState.selectedDateMillis) {
                            datePickerState.selectedDateMillis?.let { utcMillis ->
                                val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                    timeInMillis = utcMillis
                                }
                                val localCal = Calendar.getInstance().apply {
                                    set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH), utcCal.get(Calendar.DAY_OF_MONTH), 23, 59, 59)
                                }
                                updateExpiration(localCal.timeInMillis)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(HeritageCard)
                                .border(1.dp, HeritageCopper.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            DatePicker(
                                state = datePickerState,
                                title = null,
                                headline = null,
                                showModeToggle = false,
                                colors = DatePickerDefaults.colors(
                                    containerColor = HeritageCard,
                                    titleContentColor = HeritageLeatherBrown,
                                    headlineContentColor = HeritageLeatherBrown,
                                    weekdayContentColor = HeritageLeatherMuted,
                                    subheadContentColor = HeritageLeatherBrown,
                                    yearContentColor = HeritageLeatherBrown,
                                    currentYearContentColor = HeritageCopper,
                                    selectedYearContentColor = Color.White,
                                    selectedYearContainerColor = HeritageCopper,
                                    dayContentColor = HeritageLeatherBrown,
                                    selectedDayContentColor = Color.White,
                                    selectedDayContainerColor = HeritageCopper,
                                    todayContentColor = HeritageCopper,
                                    todayDateBorderColor = HeritageCopper
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showDatePickerInline = false }) {
                                    Text("CERRAR CALENDARIO", color = HeritageCopper, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // Resumen de la fecha seleccionada
                    Text(
                        text = "Vence: ${displaySdf.format(Date(expirationMillis))}",
                        color = HeritageCopperDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Atajos rápidos desde hoy:",
                        color = HeritageLeatherMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "+30 días" to TimeUnit.DAYS.toMillis(30),
                            "+6 meses" to TimeUnit.DAYS.toMillis(182),
                            "+1 año" to TimeUnit.DAYS.toMillis(365),
                            "+2 años" to TimeUnit.DAYS.toMillis(730)
                        ).forEach { (label, addMillis) ->
                            FilledTonalButton(
                                onClick = { updateExpiration(now + addMillis) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = HeritageCard,
                                    contentColor = HeritageLeatherBrown
                                ),
                                border = BorderStroke(1.dp, HeritageBorder)
                            ) {
                                Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = notesInput,
                    onValueChange = { notesInput = it },
                    label = { Text("Notas adicionales (ej. cobertura, endoso)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = vaultFieldColors
                )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, HeritageBorder)
                    ) {
                        Text("Cancelar", color = HeritageLeatherBrown, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            val docToSave = BikerDocument(
                                id = initialDoc?.id ?: UUID.randomUUID().toString(),
                                type = selectedType,
                                title = titleInput.ifBlank { selectedType.title },
                                folio = folioInput.trim(),
                                issuerOrInsurer = issuerInput.trim(),
                                expirationDateMillis = expirationMillis,
                                emergencyPhone = emergencyPhoneInput.trim(),
                                notes = notesInput.trim(),
                                photoUri = photoPath,
                                createdAtMillis = initialDoc?.createdAtMillis ?: System.currentTimeMillis()
                            )
                            onSave(docToSave)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cifrar y Guardar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Modal para Editar la Ficha Médica de Emergencia (RULES R10.2).
 * Protegido con FLAG_SECURE y validación clínica estricta en tiempo real.
 */
@Composable
internal fun EditMedicalSheetDialog(
    currentSheet: EmergencyMedicalSheet,
    onDismiss: () -> Unit,
    onSave: (EmergencyMedicalSheet) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    var bloodType by remember { mutableStateOf(currentSheet.bloodType) }
    var isNoneAllergiesChecked by remember {
        mutableStateOf(currentSheet.allergies.trim().equals(MedicalSheetValidator.DEFAULT_NONE_ALLERGIES, ignoreCase = true))
    }
    var allergies by remember { mutableStateOf(if (isNoneAllergiesChecked) "" else currentSheet.allergies) }

    var isNoneConditionsChecked by remember {
        mutableStateOf(currentSheet.chronicConditions.trim().equals(MedicalSheetValidator.DEFAULT_NONE_CONDITIONS, ignoreCase = true))
    }
    var conditions by remember { mutableStateOf(if (isNoneConditionsChecked) "" else currentSheet.chronicConditions) }

    var isNoneMedsChecked by remember {
        mutableStateOf(currentSheet.habitualMedications.trim().equals(MedicalSheetValidator.DEFAULT_NONE_MEDICATIONS, ignoreCase = true))
    }
    var medications by remember { mutableStateOf(if (isNoneMedsChecked) "" else currentSheet.habitualMedications) }

    var isOrganDonor by remember { mutableStateOf(currentSheet.isOrganDonor) }
    var contactName by remember { mutableStateOf(currentSheet.emergencyContactName) }
    var contactRelationship by remember { mutableStateOf(currentSheet.emergencyContactRelationship) }
    var contactPhone by remember { mutableStateOf(currentSheet.emergencyContactPhone) }
    var insuranceName by remember { mutableStateOf(currentSheet.medicalInsuranceName) }
    var policyNumber by remember { mutableStateOf(currentSheet.insurancePolicyNumber) }

    val bloodOptions = MedicalSheetValidator.VALID_BLOOD_TYPES

    val effectiveAllergies = if (isNoneAllergiesChecked) MedicalSheetValidator.DEFAULT_NONE_ALLERGIES else allergies
    val effectiveConditions = if (isNoneConditionsChecked) MedicalSheetValidator.DEFAULT_NONE_CONDITIONS else conditions
    val effectiveMeds = if (isNoneMedsChecked) MedicalSheetValidator.DEFAULT_NONE_MEDICATIONS else medications

    val validation = remember(bloodType, allergies, conditions, isNoneAllergiesChecked, isNoneConditionsChecked, contactName, contactPhone, policyNumber) {
        MedicalSheetValidator.validate(
            sheet = currentSheet.copy(
                bloodType = bloodType,
                allergies = effectiveAllergies,
                chronicConditions = effectiveConditions,
                habitualMedications = effectiveMeds,
                isOrganDonor = isOrganDonor,
                emergencyContactName = contactName,
                emergencyContactRelationship = contactRelationship,
                emergencyContactPhone = contactPhone,
                medicalInsuranceName = insuranceName,
                insurancePolicyNumber = policyNumber
            ),
            isNoneAllergiesChecked = isNoneAllergiesChecked,
            isNoneConditionsChecked = isNoneConditionsChecked
        )
    }

    val darkFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFFD4AF37),
        unfocusedBorderColor = Color(0xFF334155),
        focusedContainerColor = Color(0xFF1E293B),
        unfocusedContainerColor = Color(0xFF1E293B),
        focusedTextColor = Color(0xFFF8FAFC),
        unfocusedTextColor = Color(0xFFF8FAFC),
        focusedLabelColor = Color(0xFFD4AF37),
        unfocusedLabelColor = Color(0xFF94A3B8),
        cursorColor = Color(0xFFD4AF37),
        errorBorderColor = Color(0xFFEF4444),
        errorLabelColor = Color(0xFFEF4444)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 64.dp)
                .systemBarsPadding()
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0F172A),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🩺", fontSize = 20.sp)
                    Text("Ficha Médica de Emergencia", color = Color(0xFFD4AF37), fontWeight = FontWeight.Black, fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Selector Tipo de Sangre
                Text("GRUPO SANGUÍNEO:", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    bloodOptions.take(4).forEach { type ->
                        val isSelected = bloodType.equals(type, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Color(0xFFD4AF37) else Color(0xFF1E293B))
                                .border(1.dp, if (isSelected) Color(0xFFD4AF37) else Color(0xFF334155), RoundedCornerShape(6.dp))
                                .clickable { bloodType = type }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(type, color = if (isSelected) Color(0xFF0F172A) else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    bloodOptions.drop(4).forEach { type ->
                        val isSelected = bloodType.equals(type, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Color(0xFFD4AF37) else Color(0xFF1E293B))
                                .border(1.dp, if (isSelected) Color(0xFFD4AF37) else Color(0xFF334155), RoundedCornerShape(6.dp))
                                .clickable { bloodType = type }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(type, color = if (isSelected) Color(0xFF0F172A) else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
                if (validation.bloodTypeError != null) {
                    Text(validation.bloodTypeError!!, color = Color(0xFFEF4444), fontSize = 10.sp)
                }

                // Switch Donante
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Donante de Órganos Voluntario", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = isOrganDonor,
                        onCheckedChange = { isOrganDonor = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFD4AF37),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }

                // Alergias
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ALERGIAS:", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isNoneAllergiesChecked,
                                onCheckedChange = { isNoneAllergiesChecked = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFD4AF37),
                                    checkmarkColor = Color(0xFF0F172A)
                                )
                            )
                            Text("Ninguna conocida", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                    }
                    if (!isNoneAllergiesChecked) {
                        OutlinedTextField(
                            value = allergies,
                            onValueChange = { allergies = it },
                            placeholder = { Text("Describe alergias a medicamentos o alimentos (mínimo 10 caracteres)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = darkFieldColors,
                            isError = validation.allergiesError != null
                        )
                        if (validation.allergiesError != null) {
                            Text(validation.allergiesError!!, color = Color(0xFFEF4444), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }

                // Condiciones Crónicas
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("CONDICIONES CRÓNICAS:", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isNoneConditionsChecked,
                                onCheckedChange = { isNoneConditionsChecked = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFD4AF37),
                                    checkmarkColor = Color(0xFF0F172A)
                                )
                            )
                            Text("Ninguna", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                    }
                    if (!isNoneConditionsChecked) {
                        OutlinedTextField(
                            value = conditions,
                            onValueChange = { conditions = it },
                            placeholder = { Text("Condiciones médicas relevantes (mínimo 10 caracteres)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = darkFieldColors,
                            isError = validation.chronicConditionsError != null
                        )
                        if (validation.chronicConditionsError != null) {
                            Text(validation.chronicConditionsError!!, color = Color(0xFFEF4444), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }

                // Contacto ICE (En Caso de Emergencia)
                Text("CONTACTO ICE (EMERGENCIA):", color = Color(0xFFD4AF37), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = contactName,
                    onValueChange = { contactName = it },
                    label = { Text("Nombre del contacto (mínimo 3 letras)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors,
                    isError = validation.emergencyContactNameError != null
                )
                if (validation.emergencyContactNameError != null) {
                    Text(validation.emergencyContactNameError!!, color = Color(0xFFEF4444), fontSize = 10.sp)
                }

                OutlinedTextField(
                    value = contactRelationship,
                    onValueChange = { contactRelationship = it },
                    label = { Text("Parentesco / Rol (ej. Hermano, Esposa)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors
                )

                OutlinedTextField(
                    value = contactPhone,
                    onValueChange = { contactPhone = it },
                    label = { Text("Teléfono de emergencia (+58..., 0414...)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors,
                    isError = validation.emergencyContactPhoneError != null
                )
                if (validation.emergencyContactPhoneError != null) {
                    Text(validation.emergencyContactPhoneError!!, color = Color(0xFFEF4444), fontSize = 10.sp)
                }

                // Seguro Médico
                Text("SEGURO MÉDICO (OPCIONAL):", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = insuranceName,
                    onValueChange = { insuranceName = it },
                    label = { Text("Compañía Aseguradora (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors
                )

                OutlinedTextField(
                    value = policyNumber,
                    onValueChange = { policyNumber = it },
                    label = { Text("Folio de Póliza (mínimo 6 caracteres alfanuméricos)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors,
                    isError = validation.insurancePolicyNumberError != null
                )
                if (validation.insurancePolicyNumberError != null) {
                    Text(validation.insurancePolicyNumberError!!, color = Color(0xFFEF4444), fontSize = 10.sp)
                }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Text("Cancelar", color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            if (validation.isValid) {
                                val updated = currentSheet.copy(
                                    bloodType = bloodType.trim().uppercase(),
                                    allergies = effectiveAllergies.trim(),
                                    chronicConditions = effectiveConditions.trim(),
                                    habitualMedications = effectiveMeds.trim(),
                                    isOrganDonor = isOrganDonor,
                                    emergencyContactName = contactName.trim(),
                                    emergencyContactRelationship = contactRelationship.trim().ifBlank { "Contacto Primario" },
                                    emergencyContactPhone = contactPhone.trim(),
                                    medicalInsuranceName = insuranceName.trim().ifBlank { "Seguro Privado" },
                                    insurancePolicyNumber = policyNumber.trim(),
                                    lastUpdatedMillis = System.currentTimeMillis()
                                )
                                onSave(updated)
                            }
                        },
                        enabled = validation.isValid,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD4AF37),
                            disabledContainerColor = Color(0xFF334155),
                            contentColor = Color(0xFF0F172A),
                            disabledContentColor = Color(0xFF64748B)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Guardar Ficha Médica", fontWeight = FontWeight.Bold, fontSize = 11.5.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Modal para Compartir la Ficha Médica de Emergencia durante una Rodada Activa (RULES R6.5).
 * Protegido con FLAG_SECURE. Muestra advertencia de acceso restringido, copia inmutable y estado de carga.
 */
@Composable
internal fun ShareMedicalSheetDialog(
    isSharing: Boolean,
    successMessage: String?,
    errorMessage: String?,
    hasActiveRide: Boolean = false,
    activeRideTitle: String = "Sin rodada activa",
    targetClubName: String = "Mi Club",
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Dialog(
        onDismissRequest = { if (!isSharing) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isSharing,
            dismissOnClickOutside = !isSharing,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 64.dp)
                .systemBarsPadding()
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0F172A),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📡", fontSize = 20.sp)
                    Text(
                        text = "Compartir con mi Club",
                        color = Color(0xFFD4AF37),
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Info Rodada
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("RODADA OBJETIVO:", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(activeRideTitle, color = if (hasActiveRide) Color.White else Color(0xFFF87171), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (hasActiveRide && targetClubName.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("CLUB RECEPTOR: $targetClubName", color = Color(0xFFD4AF37), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Advertencia de Seguridad y Privacidad
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (hasActiveRide) Color(0xFF451A03).copy(alpha = 0.5f) else Color(0xFF7F1D1D).copy(alpha = 0.25f))
                        .border(1.dp, if (hasActiveRide) Color(0xFFD97706) else Color(0xFFEF4444), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (hasActiveRide) "🔒 ACCESO RESTRINGIDO (RULES R6.5)" else "⚠️ SIN RODADA ACTIVA (RULES R6.5)",
                            color = if (hasActiveRide) Color(0xFFFBBF24) else Color(0xFFFCA5A5),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = if (hasActiveRide) {
                                "Tu ficha médica cifrada solo será visible para el Líder y Sargento de Armas de $targetClubName durante la rodada activa. El acceso expira automáticamente en 24 horas o al cerrar la rodada."
                            } else {
                                "Por motivos de privacidad y seguridad médica, tu ficha solo se comparte de forma sellada con los líderes cuando estás participando en una rodada activa. Inicia o únete a una rodada antes de compartirla."
                            },
                            color = if (hasActiveRide) Color(0xFFFEF3C7) else Color(0xFFFECACA),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                // Inmutabilidad
                Text(
                    text = "ℹ️ La copia compartida es una captura inmutable al momento de enviar. Si editas tu ficha posteriormente, deberás volver a compartirla.",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )

                // Feedback Banner
                if (successMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF064E3B))
                            .border(1.dp, Color(0xFF10B981), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text("✅ $successMessage", color = Color(0xFFA7F3D0), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF7F1D1D))
                            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text("⚠️ $errorMessage", color = Color(0xFFFECACA), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isSharing,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Text("Cerrar", color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    if (isSharing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFFD4AF37),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cifrando...", color = Color(0xFFD4AF37), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onShare,
                            enabled = hasActiveRide && successMessage == null,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasActiveRide) Color(0xFF0F766E) else Color(0xFF334155),
                                disabledContainerColor = Color(0xFF1E293B),
                                contentColor = Color.White,
                                disabledContentColor = Color(0xFF64748B)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = when {
                                    successMessage != null -> "Ficha Compartida"
                                    !hasActiveRide -> "Requiere Rodada Activa"
                                    else -> "Compartir"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = if (!hasActiveRide) 11.sp else 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Diálogo para que el Líder o Sargento de Armas visualice la Ficha Médica de Emergencia
 * de un integrante durante una rodada activa (RULES R6.5 & R10.2).
 * Protegido con FLAG_SECURE.
 */
@Composable
internal fun ViewMemberMedicalSheetDialog(
    sheet: EmergencyMedicalSheet?,
    errorMessage: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            securePolicy = SecureFlagPolicy.SecureOn
        ),
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🚨", fontSize = 20.sp)
                Text(
                    text = "Ficha Médica de Emergencia (SOS)",
                    color = Color(0xFFEF4444),
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF7F1D1D))
                        .padding(12.dp)
                ) {
                    Text("⚠️ $errorMessage", color = Color(0xFFFECACA), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else if (sheet == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFFD4AF37))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Descifrando datos de emergencia...", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Badge Grupo Sanguíneo y Donante
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF7F1D1D).copy(alpha = 0.4f))
                                .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text("GRUPO SANGUÍNEO", color = Color(0xFFFCA5A5), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text(sheet.bloodType, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E293B))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text("DONANTE", color = Color(0xFF94A3B8), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text(if (sheet.isOrganDonor) "Registrado" else "No especificado", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    // Contacto ICE con botón para llamar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF064E3B).copy(alpha = 0.4f))
                            .border(1.dp, Color(0xFF10B981), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("CONTACTO DE EMERGENCIA (ICE)", color = Color(0xFF34D399), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text("${sheet.emergencyContactName} (${sheet.emergencyContactRelationship})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(sheet.emergencyContactPhone, color = Color(0xFFA7F3D0), fontSize = 12.sp)
                            }
                            IconButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_DIAL).apply {
                                        data = Uri.parse("tel:${sheet.emergencyContactPhone}")
                                    }
                                    context.startActivity(intent)
                                }
                            ) {
                                Icon(Icons.Default.Phone, contentDescription = "Llamar", tint = Color(0xFF10B981))
                            }
                        }
                    }

                    // Alergias y Condiciones
                    MedicalRow(label = "Alergias Conocidas", value = sheet.allergies)
                    MedicalRow(label = "Condiciones Crónicas", value = sheet.chronicConditions)
                    MedicalRow(label = "Medicamentos Habituales", value = sheet.habitualMedications)
                    if (sheet.insurancePolicyNumber.isNotBlank()) {
                        MedicalRow(label = "Póliza de Seguro", value = "${sheet.medicalInsuranceName} (${sheet.insurancePolicyNumber})")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cerrar", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun MedicalRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = HeritageLeatherMuted,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.ifBlank { "No especificado" },
            color = HeritageLeatherBrown,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.3f)
        )
    }
}


@Composable
fun BackupMnemonicDialog(
    words: List<String>,
    isBackingUp: Boolean,
    successMessage: String?,
    errorMessage: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = Color(0xFFD4AF37),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Frase de Recuperación (BIP-0039)",
                    color = HeritageTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Alerta explícita de seguridad
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFEF3C7))
                        .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SEGURIDAD CRÍTICA · NO COMPARTAS ESTA PANTALLA",
                                color = Color(0xFFB45309),
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp
                            )
                        }
                        Text(
                            text = "Si pierdes este teléfono, estas 12 palabras son la única vía para recuperar tus documentos. Anótalas en papel en orden.",
                            color = Color(0xFF78350F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Grid numerado de 12 palabras (2 columnas x 6 filas)
                val chunked = words.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chunked.forEachIndexed { rowIndex, rowWords ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowWords.forEachIndexed { colIndex, word ->
                                val index = rowIndex * 2 + colIndex + 1
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (HeritageTheme.isBrightRider) Color.White else Color(0xFF26201B))
                                        .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "$index.",
                                            color = Color(0xFFB85C38),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = word,
                                            color = HeritageTheme.colors.textPrimary,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            if (rowWords.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Estado de sincronización del blob de recuperación
                if (isBackingUp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFFD4AF37))
                        Text("Sincronizando respaldo E2EE...", fontSize = 11.sp, color = HeritageTheme.colors.textSecondary)
                    }
                } else if (successMessage != null) {
                    Text(
                        text = "🟢 Respaldo E2EE registrado en la nube",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF059669)
                    )
                } else if (errorMessage != null) {
                    Text(
                        text = "⚠️ $errorMessage",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDC2626)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("He anotado las 12 palabras", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        containerColor = HeritageTheme.colors.surface,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun RestoreMnemonicDialog(
    isRestoring: Boolean,
    errorMessage: String?,
    successMessage: String?,
    inputText: String,
    onInputTextChanged: (String) -> Unit,
    onRestore: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isRestoring) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = HeritageCopper,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Recuperar Bóveda en Nuevo Dispositivo",
                    color = HeritageTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Introduce las 12 palabras de recuperación generadas en tu otro dispositivo (separadas por espacio):",
                    fontSize = 12.sp,
                    color = HeritageTheme.colors.textSecondary
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("12 Palabras BIP-0039", fontSize = 11.sp) },
                    placeholder = { Text("ej: abandon ability able about above...", fontSize = 11.sp) },
                    maxLines = 4,
                    minLines = 3,
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isRestoring
                )

                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFEE2E2))
                            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = errorMessage,
                                color = Color(0xFFB91C1C),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (successMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFE8F8F0))
                            .border(1.dp, Color(0xFF10B981), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = successMessage,
                                color = Color(0xFF047857),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val words = inputText.trim().lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    onRestore(words)
                },
                enabled = !isRestoring && inputText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isRestoring) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Descifrando...", color = Color.White, fontSize = 11.sp)
                } else {
                    Text("Recuperar Bóveda", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        },
        dismissButton = {
            if (!isRestoring) {
                TextButton(onClick = onDismiss) {
                    Text("Cerrar", color = HeritageTheme.colors.textSecondary, fontSize = 11.sp)
                }
            }
        },
        containerColor = HeritageTheme.colors.surface,
        shape = RoundedCornerShape(16.dp)
    )
}

/**
 * Diálogo de Configuración de Seguridad E2EE de la Bóveda.
 */
@Composable
internal fun VaultSecuritySettingsDialog(
    onDismiss: () -> Unit,
    onRotateClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFD4AF37), modifier = Modifier.size(22.dp))
                Text(
                    text = "Seguridad de la Bóveda",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Tu Bóveda utiliza cifrado simétrico AES-256-GCM y recuperación Zero-Knowledge derivada mediante PBKDF2 (100,000 iteraciones).",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )

                // Tarjeta de rotación
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Rotación de Frase de Recuperación",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF1F5F9)
                        )
                        Text(
                            text = "Si sospechas que tus 12 palabras fueron expuestas o vistas por terceros, rota tu frase inmediatamente. La frase anterior quedará invalidada en el servidor de forma irreversible.",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onRotateClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFDC2626),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Rotar Palabras de Recuperación", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = Color(0xFF94A3B8))
            }
        },
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(16.dp)
    )
}

/**
 * Diálogo de Confirmación Crítica para Rotar Palabras de Recuperación.
 */
@Composable
internal fun ConfirmRotateDialog(
    isRotating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isRotating) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(22.dp))
                Text(
                    text = "¿Confirmar Rotación?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "⚠️ ATENCIÓN: Al rotar, la clave de recuperación anterior en el servidor será destruida de forma irreversible. Las 12 palabras viejas dejarán de funcionar de inmediato.",
                    fontSize = 12.sp,
                    color = Color(0xFFFECACA),
                    lineHeight = 16.sp
                )
                Text(
                    text = "Deberás anotar en papel el nuevo conjunto de 12 palabras. Asegúrate de tener papel y bolígrafo a mano.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 15.sp
                )
                if (error != null) {
                    Text(
                        text = "Error: $error",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isRotating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFDC2626),
                    contentColor = Color.White
                )
            ) {
                if (isRotating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Rotando en servidor...", fontSize = 12.sp)
                } else {
                    Text("Confirmar y Generar Nuevas", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRotating) {
                Text("Cancelar", color = Color(0xFF94A3B8))
            }
        },
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(16.dp)
    )
}

/**
 * Diálogo de Presentación de Nuevas Palabras BIP-0039 con FLAG_SECURE y Zeroing en RAM.
 */
@Composable
internal fun RotateMnemonicDialog(
    words: List<String>,
    onDismissAndWipe: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    AlertDialog(
        onDismissRequest = onDismissAndWipe,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn
        ),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                Column {
                    Text(
                        text = "Nuevas Palabras (BIP-0039)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Frase Renovada y Activa",
                        fontSize = 11.sp,
                        color = Color(0xFF10B981)
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
                // Banner de seguridad
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFEF3C7).copy(alpha = 0.15f))
                        .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SEGURIDAD CRÍTICA · FRASE VIGENTE",
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                color = Color(0xFFF59E0B)
                            )
                        }
                        Text(
                            text = "Estas son tus nuevas palabras. Las anteriores ya no funcionan. Anótalas en orden y guárdalas en un lugar seguro.",
                            fontSize = 11.sp,
                            color = Color(0xFFFDE68A),
                            lineHeight = 15.sp
                        )
                    }
                }

                // Cuadrícula 1..12
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0 until words.size step 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MnemonicWordChip(index = i + 1, word = words[i], modifier = Modifier.weight(1f))
                            if (i + 1 < words.size) {
                                MnemonicWordChip(index = i + 2, word = words[i + 1], modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismissAndWipe,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD4AF37),
                    contentColor = Color(0xFF0F172A)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "He anotado y resguardado mis nuevas 12 palabras",
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp
                )
            }
        },
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun MnemonicWordChip(
    index: Int,
    word: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$index.",
                color = Color(0xFFD4AF37),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = word,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
