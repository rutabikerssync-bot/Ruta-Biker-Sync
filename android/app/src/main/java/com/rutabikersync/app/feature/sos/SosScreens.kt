package com.rutabikersync.app.feature.sos

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rutabikersync.app.ui.components.HeritageBevelCard
import com.rutabikersync.app.ui.theme.*

/**
 * Componentes de interfaz para el sistema de SOS, cuenta regresiva de 20 s
 * y visualización del paquete de rescate médico de 60 min.
 * (Fase 5 - SCREENS.md C4, C5).
 */

/**
 * Superposición a pantalla completa para guantes durante la cuenta regresiva de 20 segundos.
 */
@Composable
fun SosCountdownOverlay(
    remainingSeconds: Int,
    triggerType: SosTriggerType,
    onCancelFalseAlarm: () -> Unit,
    onConfirmImmediately: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SosPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SosBgPulse"
    )

    val progress = remainingSeconds / 20f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // 1. Cabecera de Alerta
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444).copy(alpha = pulseAlpha))
                        .border(2.dp, Color(0xFFFCA5A5), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Alerta SOS",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (triggerType == SosTriggerType.FALL_DETECTION) "¡IMPACTO O CAÍDA DETECTADA!" else "¡ALERTA SOS EN PROCESO!",
                    color = Color(0xFFFCA5A5),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Transmitiendo tu posición y paquete de rescate médico al convoy en:",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 2. Cronómetro Masivo Circular
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(140.dp)
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFEF4444),
                    trackColor = Color(0xFF334155),
                    strokeWidth = 10.dp
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$remainingSeconds",
                        color = Color.White,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "segundos",
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 3. Botonera gigante para guantes
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Botón principal: CANCELAR FALSA ALARMA (Touch target masivo)
                Button(
                    onClick = onCancelFalseAlarm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .border(2.dp, Color(0xFF34D399), RoundedCornerShape(16.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "👍 ESTOY BIEN",
                            color = Color(0xFF042F2E),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(Cancelar Falsa Alarma)",
                            color = Color(0xFF064E3B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Botón secundario: CONFIRMAR INMEDIATAMENTE
                Button(
                    onClick = onConfirmImmediately,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B1214)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                ) {
                    Text(
                        text = "🚨 CONFIRMAR SOS AHORA",
                        color = Color(0xFFFCA5A5),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Banner persistente de alerta SOS activa en cabina.
 */
@Composable
fun SosActiveBanner(
    alertEvent: SosAlertEvent,
    isSelf: Boolean,
    onViewPacketClick: () -> Unit,
    onResolveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgGradient = if (isSelf) {
        Brush.horizontalGradient(listOf(Color(0xFF7F1D1D), Color(0xFF991B1B)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF831843), Color(0xFF7F1D1D)))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgGradient)
            .border(1.5.dp, Color(0xFFEF4444), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "SOS",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = if (isSelf) "🚨 TU SOS ESTÁ ACTIVO EN EL CONVOY" else "🚨 SOS DE: ${alertEvent.senderName.uppercase()}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = if (isSelf) "Ubicación y ficha médica transmitidas" else "Disparador: ${alertEvent.triggerType.displayName}",
                        color = Color(0xFFFECACA),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (alertEvent.rescuePacket != null) {
                    Button(
                        onClick = onViewPacketClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Ficha Médica",
                            color = Color(0xFF0B0E14),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick = onResolveClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242732)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isSelf) "Finalizar" else "Entendido",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Diálogo modal con la ficha médica de rescate de 60 min (R10.2, R10.3, R10.5).
 */
@Composable
fun EmergencyRescuePacketDialog(
    packet: EmergencyRescuePacket,
    remainingMinutes: Int = 60,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp),
                shape = RoundedCornerShape(20.dp),
                color = HeritageSand,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                // Barra superior de cierre y temporizador de 60 min
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(HeritageRustRed),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Ficha",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "FICHA DE RESCATE MÉDICO",
                                color = HeritageRustRed,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "⏱ Acceso temporal por 60 min ($remainingMinutes min restantes)",
                                color = HeritageLeatherMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = HeritageLeatherBrown
                        )
                    }
                }

                // Banner obligatorio R10.5
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(HeritageCardWarm)
                        .border(1.dp, HeritageBorder, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Aviso",
                            tint = HeritageAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AVISO: Esta información es solo para auxilio inmediato y no sustituye atención médica profesional.",
                            color = HeritageLeatherMuted,
                            fontSize = 10.sp
                        )
                    }
                }

                // Contenido Scrolleable de la Ficha (adaptativo y sin huecos forzados)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Datos del Piloto y Tipo de Sangre
                    HeritageBevelCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = packet.fullName,
                                    color = HeritageLeatherBrown,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "\"${packet.riderAlias}\"",
                                    color = HeritageCopper,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (packet.organDonor) {
                                    Text(
                                        text = "❤️ Donante de Órganos Registrado",
                                        color = HeritageGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            // Badge de Tipo de Sangre Gigante
                            Box(
                                modifier = Modifier
                                    .size(62.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(HeritageRustRed)
                                    .border(2.dp, HeritageRustRed, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = packet.bloodType,
                                        color = Color.White,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = "SANGRE",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Alergias y Condiciones Médicas
                    HeritageBevelCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "ALERGIAS SEVERAS",
                                color = HeritageRustRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            if (packet.allergies.isEmpty()) {
                                Text("Ninguna reportada", color = HeritageLeatherMuted, fontSize = 12.sp)
                            } else {
                                packet.allergies.forEach { allergy ->
                                    Text("• $allergy", color = HeritageLeatherBrown, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "CONDICIONES Y MEDICAMENTOS",
                                color = HeritageAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            if (packet.medicalConditions.isEmpty() && packet.regularMedications.isEmpty()) {
                                Text("Ninguna condición preexistente", color = HeritageLeatherMuted, fontSize = 12.sp)
                            } else {
                                packet.medicalConditions.forEach { cond ->
                                    Text("• Condición: $cond", color = HeritageLeatherBrown, fontSize = 12.sp)
                                }
                                packet.regularMedications.forEach { med ->
                                    Text("• Medicamento: $med", color = HeritageCopper, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Contactos de Emergencia (Con marcado telefónico rápido)
                    HeritageBevelCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "CONTACTOS DE EMERGENCIA",
                                color = HeritageCopper,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )

                            packet.emergencyContacts.forEach { contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(HeritageCardWarm)
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(contact.name, color = HeritageLeatherBrown, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("${contact.relationship} · ${contact.phoneNumber}", color = HeritageLeatherMuted, fontSize = 11.sp)
                                    }

                                    Button(
                                        onClick = {
                                            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phoneNumber}"))
                                            context.startActivity(dialIntent)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = "Llamar", tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Llamar", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Datos de la Motocicleta y Seguro
                    HeritageBevelCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "VEHÍCULO Y SEGURO",
                                color = HeritageGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Moto: ${packet.bikeBrand ?: ""} ${packet.bikeModel ?: ""} (${packet.bikeColor ?: ""})",
                                color = HeritageLeatherBrown,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Placa: ${packet.bikePlate ?: "N/A"}",
                                color = HeritageCopper,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!packet.insuranceCompany.isNullOrBlank()) {
                                Text(
                                    text = "Aseguradora: ${packet.insuranceCompany} (Póliza: ${packet.insurancePolicyNumber ?: "N/A"})",
                                    color = HeritageLeatherMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cerrar Ficha", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// COMPONENTES DEL HITO 4 (ETAPA 4): RECEPCIÓN SOS POR MESH + FICHA MÉDICA AES
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Diálogo de recepción SOS en Malla BLE (Hito 4 · Etapa 4).
 *
 * Se muestra en el Líder Principal o Sargento de Armas al recibir un [SosPacket] de la malla off-grid.
 * Muestra:
 * - Nombre/alias del piloto en emergencia (derivado de [senderName]).
 * - Distancia en saltos ([hopCount]).
 * - Coordenadas GPS del SOS (lat/lon).
 * - Si el nodo local posee la clave de rescate: datos clínicos de [MedicalRescueData].
 * - Botón "Confirmar Recepción" → emite el callback [onConfirmReception].
 * - Botón "Llamar contacto ICE" si hay teléfono en la ficha.
 *
 * Seguridad:
 * - Se establece WindowManager.FLAG_SECURE en el host Activity/Dialog para bloquear capturas.
 * - Protocolo anti-fuga: NUNCA se imprime en logcat la clave de rescate ni el authTag.
 */
@Composable
fun MeshSosReceptionDialog(
    senderName: String,
    senderShortId: UShort,
    hopCount: Int,
    latitude: Double,
    longitude: Double,
    medicalData: com.rutabikersync.app.core.mesh.sos.MedicalRescueData?,
    onConfirmReception: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val latStr = "%.6f".format(latitude)
    val lonStr = "%.6f".format(longitude)

    androidx.compose.runtime.LaunchedEffect(senderShortId) {
        if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.i("SosScreens", "MeshSosReceptionDialog visible") }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0B0E14),
                tonalElevation = 8.dp
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Cabecera de alerta SOS en malla
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "SOS",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "🚨 SOS RECIBIDO POR MESH",
                                color = Color(0xFFFCA5A5),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "ID Corto: ${senderShortId.toInt()} · $hopCount salto${if (hopCount == 1) "" else "s"}",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cerrar", tint = Color(0xFF94A3B8))
                    }
                }

                // Piloto en emergencia
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E0A0A))
                        .border(1.5.dp, Color(0xFFEF4444), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "PILOTO EN EMERGENCIA",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = senderName.ifBlank { "Piloto #${senderShortId.toInt()}" },
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // Ubicación GPS
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF131720))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "GPS",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "COORDENADAS GPS",
                                color = Color(0xFF10B981),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Lat: $latStr  |  Lon: $lonStr",
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Distancia en malla: $hopCount salto${if (hopCount == 1) "" else "s"}",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Ficha médica AES-GCM (si el nodo posee la clave de rescate)
                if (medicalData != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F1A14))
                            .border(1.5.dp, Color(0xFF10B981), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "🔐 FICHA MÉDICA DE RESCATE",
                            color = Color(0xFF10B981),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )

                        // Tipo de sangre grande
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                MedicalDataRow(label = "ALERGIAS", value = medicalData.allergies)
                                MedicalDataRow(label = "MEDICACIÓN", value = medicalData.medication)
                                MedicalDataRow(label = "CONDICIONES", value = medicalData.conditions)
                                MedicalDataRow(label = "PÓLIZA", value = medicalData.insurancePolicy)
                            }
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFEF4444)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = medicalData.bloodType,
                                        color = Color.White,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = "SANGRE",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Contacto ICE con marcado rápido
                        if (medicalData.iceName.isNotBlank() || medicalData.icePhone.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1A2820))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("CONTACTO ICE", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    Text(medicalData.iceName.ifBlank { "No especificado" }, color = Color(0xFFCBD5E1), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    if (medicalData.icePhone.isNotBlank()) {
                                        Text(medicalData.icePhone, color = Color(0xFF10B981), fontSize = 12.sp)
                                    }
                                }
                                if (medicalData.icePhone.isNotBlank()) {
                                    androidx.compose.material3.Button(
                                        onClick = {
                                            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${medicalData.icePhone}"))
                                            context.startActivity(dialIntent)
                                        },
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = "Llamar ICE", tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Llamar ICE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF131720))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Ficha médica no disponible (este nodo no posee la clave de rescate de la rodada).",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Botón principal: Confirmar Recepción → emite SosAckPacket
                androidx.compose.material3.Button(
                    onClick = {
                        onConfirmReception()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "✅ CONFIRMAR RECEPCIÓN (ACK)",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        }
    }
}

/** Fila de dato clínico reutilizable dentro de [MeshSosReceptionDialog]. */
@Composable
private fun MedicalDataRow(label: String, value: String) {
    if (value.isNotBlank()) {
        Column(modifier = Modifier.padding(bottom = 6.dp)) {
            Text(label, color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Black)
            Text(value, color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
