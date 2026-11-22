package com.rutabikersync.app.feature.mesh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.core.mesh.text.DeliveryStatus
import com.rutabikersync.app.core.mesh.text.TacticalChatMessage
import com.rutabikersync.app.core.mesh.text.TacticalChatStore
import com.rutabikersync.app.core.mesh.text.TextMessagePacket
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.rutabikersync.app.R
import com.rutabikersync.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla oficial de Mensajería de Texto Táctica Off-Grid (Fase 7.0 · Hito 7.0.2).
 * Estética Dark Elite + Heritage con soporte de Modo Guantes (targets >= 72 dp).
 * Arquitectura unificada anti-gap: el teclado IME ajusta la altura sin desplazar el encabezado ni ocultar mensajes.
 * Cero persistencia en almacenamiento secundario; opera 100% en memoria volátil (Regla R12).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TacticalChatScreen(
    localShortId: UShort = 1u,
    peers: List<Pair<UShort, String>> = emptyList(),
    peerAvatars: Map<UShort, String?> = emptyMap(),
    peerFullNames: Map<UShort, String> = emptyMap(),
    initialFocusKeyboard: Boolean = false,
    onSendMessage: (targetShortId: UShort, text: String) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val messages by TacticalChatStore.messages.collectAsState()
    val listState = rememberLazyListState()

    var textInput by rememberSaveable { mutableStateOf("") }
    var isUnicastMode by remember { mutableStateOf(false) }
    var selectedTargetShortId by remember { mutableStateOf<UShort>(TextMessagePacket.BROADCAST_TARGET) }

    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val presets = remember {
        listOf(
            "⛽ Gasolinera a 2 km",
            "🛑 Parada técnica (5 min)",
            "⚠️ Peligro / Baches",
            "🏍️ Reagrupar convoy",
            "👮 Retén adelante",
            "🌧️ Lluvia adelante"
        )
    }

    LaunchedEffect(initialFocusKeyboard) {
        if (initialFocusKeyboard) {
            kotlinx.coroutines.delay(400)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        containerColor = DarkSlate950,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSlate900,
                    titleContentColor = TextWhite,
                    navigationIconContentColor = TextWhite
                ),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = null,
                                tint = EmeraldMesh,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Chat Táctico Off-Grid",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = "Malla BLE · ChaCha20-Poly1305 · RAM Volátil (R12)",
                            fontSize = 10.sp,
                            color = EmeraldMesh
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        // Contenedor principal unificado: ocupa toda la pantalla y ajusta automáticamente con el teclado
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
            // 1. Selector de Destinatario: Alternancia Broadcast vs Unicast
            Surface(
                color = DarkSlate900,
                border = BorderStroke(1.dp, DarkBorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Opción 1: Convoy General (Broadcast)
                        FilterChip(
                            selected = !isUnicastMode,
                            onClick = {
                                isUnicastMode = false
                                selectedTargetShortId = TextMessagePacket.BROADCAST_TARGET
                            },
                            label = { Text("📡 Convoy General (Broadcast)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = HeritageCopperDark,
                                selectedLabelColor = TextWhite,
                                containerColor = DarkCardBg,
                                labelColor = TextMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = !isUnicastMode,
                                borderColor = if (!isUnicastMode) HeritageCopperGlow else DarkBorderSubtle
                            )
                        )

                        // Opción 2: Compañero Directo (Unicast)
                        FilterChip(
                            selected = isUnicastMode,
                            onClick = {
                                isUnicastMode = true
                                if (selectedTargetShortId == TextMessagePacket.BROADCAST_TARGET && peers.isNotEmpty()) {
                                    selectedTargetShortId = peers.first().first
                                }
                            },
                            label = { Text("👤 Directo (Unicast)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = HeritageGreenLight,
                                selectedLabelColor = TextWhite,
                                containerColor = DarkCardBg,
                                labelColor = TextMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isUnicastMode,
                                borderColor = if (isUnicastMode) EmeraldMeshGlow else DarkBorderSubtle
                            )
                        )
                    }

                    // Selector desplegable horizontal de Peers en modo Unicast
                    if (isUnicastMode && peers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Enviar a:",
                                fontSize = 11.sp,
                                color = TextMuted,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            peers.forEach { (peerId, peerName) ->
                                val isSelected = selectedTargetShortId == peerId
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) Color(0xFF2C3240) else DarkCardBg,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) HeritageBrassGold else DarkBorderSubtle
                                    ),
                                    modifier = Modifier.clickable { selectedTargetShortId = peerId }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) HeritageBrassGold else DarkCardElevated),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = peerName.take(1).uppercase(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.Black else TextWhite
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "$peerName (#$peerId)",
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) TextWhite else TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Área Central: Mensajes o Empty State Silencioso (Ocupa todo el espacio dinámico disponible)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Fondo con textura temática Biker (Dark Elite con contraste y detalles visibles 0.30f)
                Image(
                    painter = painterResource(id = R.drawable.biker_chat_pattern),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.30f
                )

                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = DarkCardElevated,
                                border = BorderStroke(1.dp, DarkBorderProminent),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = HeritageCopper,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Canal Táctico Silencioso",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = TextWhite
                            )
                            Text(
                                text = "Los mensajes se transmiten en ráfagas de radio BLE protegidas con ChaCha20-Poly1305. Cero internet. Memoria volátil purgada al finalizar.",
                                fontSize = 11.sp,
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            TacticalMessageBubble(
                                msg = msg,
                                peerAvatar = peerAvatars[msg.senderShortId],
                                peerFullName = peerFullNames[msg.senderShortId]
                            )
                        }
                    }
                }
            }

            // 3. Panel Inferior: Acciones Rápidas (Presets) + Caja de Entrada + Botón Enviar (Pegado directamente al teclado)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSlate900)
                    .border(width = 1.dp, color = DarkBorderSubtle)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // Presets para Guantes
                Text(
                    text = "ACCIONES RÁPIDAS (MODO GUANTES):",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = DarkCardElevated,
                            border = BorderStroke(1.dp, DarkBorderProminent),
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val target = if (isUnicastMode) selectedTargetShortId else TextMessagePacket.BROADCAST_TARGET
                                    val newMsg = TacticalChatMessage(
                                        senderShortId = localShortId,
                                        senderName = "Tú",
                                        targetShortId = target,
                                        text = preset,
                                        isOutgoing = true,
                                        deliveryStatus = DeliveryStatus.SENT
                                    )
                                    TacticalChatStore.addMessage(newMsg)
                                    onSendMessage(target, preset)
                                }
                        ) {
                            Text(
                                text = preset,
                                color = TextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Fila de Entrada de Texto con soporte IME y límite de 120 bytes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 120) textInput = it },
                        placeholder = {
                            val targetPeerName = peers.firstOrNull { it.first == selectedTargetShortId }?.second
                            Text(
                                text = if (!isUnicastMode || selectedTargetShortId == TextMessagePacket.BROADCAST_TARGET)
                                    "Mensaje a todo el convoy..."
                                else
                                    "Directo a ${targetPeerName ?: "#$selectedTargetShortId"}...",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedContainerColor = DarkCardBg,
                            unfocusedContainerColor = DarkCardBg,
                            focusedBorderColor = HeritageCopper,
                            unfocusedBorderColor = DarkBorderSubtle
                        ),
                        supportingText = {
                            val byteCount = textInput.toByteArray(Charsets.UTF_8).size
                            Text(
                                text = "$byteCount/120 B",
                                color = when {
                                    byteCount >= 115 -> RescueRed
                                    byteCount >= 100 -> WarningAmber
                                    else -> TextTertiary
                                },
                                fontSize = 10.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        maxLines = 2,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                    )

                    // Botón Ergonómico de Enviar (≥72 dp con feedback háptico)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (textInput.isNotBlank()) HeritageCopper else DarkCardElevated,
                        border = BorderStroke(1.dp, if (textInput.isNotBlank()) HeritageCopperGlow else DarkBorderSubtle),
                        modifier = Modifier
                            .size(width = 72.dp, height = 56.dp)
                            .clickable(enabled = textInput.isNotBlank()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val msgText = textInput.trim()
                                val target = if (isUnicastMode) selectedTargetShortId else TextMessagePacket.BROADCAST_TARGET
                                val newMsg = TacticalChatMessage(
                                    senderShortId = localShortId,
                                    senderName = "Tú",
                                    targetShortId = target,
                                    text = msgText,
                                    isOutgoing = true,
                                    deliveryStatus = DeliveryStatus.SENT
                                )
                                TacticalChatStore.addMessage(newMsg)
                                onSendMessage(target, msgText)
                                textInput = ""
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Enviar",
                                tint = if (textInput.isNotBlank()) Color.White else TextMuted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Burbuja oficial de mensaje táctico con estética Dark Elite + Heritage.
 * Distingue visualmente mensajes salientes (acento Cobre Heritage #C86D44) de entrantes (DarkCardElevated con avatar y rol).
 */
@Composable
fun TacticalMessageBubble(
    msg: TacticalChatMessage,
    peerAvatar: String? = null,
    peerFullName: String? = null
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(msg.timestampMs) { timeFormat.format(Date(msg.timestampMs)) }
    val alignment = if (msg.isOutgoing) Alignment.End else Alignment.Start
    val palette = chatBubblePalette(msg.isOutgoing, msg.isBroadcast)

    val resolvedName = remember(peerFullName, msg.senderName) {
        peerFullName?.takeIf { it.isNotBlank() } ?: msg.senderName
    }

    val avatarBitmap = remember(peerAvatar) {
        val raw = peerAvatar?.takeIf { it.isNotBlank() } ?: return@remember null
        try {
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    val initials = remember(resolvedName) {
        val parts = resolvedName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size >= 2) {
            "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
        } else {
            resolvedName.trim().take(2).uppercase().ifBlank { "P" }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (msg.isOutgoing) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            // Avatar para mensajes entrantes (Foto real o iniciales reales calculadas)
            if (!msg.isOutgoing) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(DarkCardElevated)
                        .border(1.2.dp, HeritageBrassGold, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap,
                            contentDescription = resolvedName,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = initials,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = HeritageBrassGold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            Surface(
                shape = RoundedCornerShape(
                    topStart = 14.dp,
                    topEnd = 14.dp,
                    bottomStart = if (msg.isOutgoing) 14.dp else 2.dp,
                    bottomEnd = if (msg.isOutgoing) 2.dp else 14.dp
                ),
                color = palette.background,
                border = BorderStroke(
                    width = 1.2.dp,
                    color = palette.border
                ),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // Cabecera: Nombre y Rol
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (msg.isOutgoing) "TÚ" else resolvedName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = palette.headerText
                            )
                            if (!msg.isOutgoing) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "· ${msg.senderRole}",
                                    fontSize = 10.sp,
                                    color = ChatTextMeta,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }

                        // Badge de Canal (BROADCAST vs DIRECTO)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = palette.badgeBackground,
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = if (msg.isBroadcast) "BROADCAST" else "DIRECTO",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = palette.badgeText,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Cuerpo del mensaje
                    Text(
                        text = msg.text,
                        fontSize = 14.sp,
                        color = palette.text,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Metadatos de seguridad y entrega
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Cifrado ChaCha20",
                            tint = palette.metaText,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = formattedTime,
                            fontSize = 10.sp,
                            color = palette.metaText
                        )

                        if (msg.isOutgoing) {
                            Spacer(modifier = Modifier.width(5.dp))
                            when (msg.deliveryStatus) {
                                DeliveryStatus.ACK_RECEIVED -> {
                                    Icon(
                                        imageVector = Icons.Default.DoneAll,
                                        contentDescription = "ACK confirmado",
                                        tint = EmeraldMeshGlow,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("ACK", fontSize = 9.sp, color = EmeraldMeshGlow, fontWeight = FontWeight.Bold)
                                }
                                DeliveryStatus.SENT -> {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Enviado por malla",
                                        tint = palette.metaText,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Enviado", fontSize = 9.sp, color = palette.metaText)
                                }
                                DeliveryStatus.FAILED -> {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Fallo de entrega",
                                        tint = RescueRed,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Fallido", fontSize = 9.sp, color = RescueRed, fontWeight = FontWeight.Bold)
                                }
                                DeliveryStatus.SENDING -> {
                                    Text("...", fontSize = 9.sp, color = palette.metaText)
                                }
                            }
                        } else if (msg.hopCount > 0) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "(${msg.hopCount} saltos)",
                                fontSize = 9.sp,
                                color = palette.metaText
                            )
                        }
                    }
                }
            }
        }
    }
}
