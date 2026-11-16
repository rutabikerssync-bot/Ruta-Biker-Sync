package com.rutabikersync.app.feature.sos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.rutabikersync.app.feature.vault.EmergencyMedicalSheet
import com.rutabikersync.app.feature.vault.VaultRepository
import com.rutabikersync.app.ui.components.HeritageBevelCard
import com.rutabikersync.app.ui.theme.HeritageSand
import com.rutabikersync.app.ui.theme.HeritageCard
import com.rutabikersync.app.ui.theme.HeritageCardWarm
import com.rutabikersync.app.ui.theme.HeritageBorder
import com.rutabikersync.app.ui.theme.HeritageLeatherBrown
import com.rutabikersync.app.ui.theme.HeritageLeatherMuted
import com.rutabikersync.app.ui.theme.HeritageCopper
import com.rutabikersync.app.ui.theme.HeritageRustRed
import com.rutabikersync.app.ui.theme.HeritageGreen
import com.rutabikersync.app.ui.theme.HeritageGreenLight
import com.rutabikersync.app.ui.theme.HeritageAmber

/**
 * Pantalla de Bloqueo con Ficha Médica de Emergencia (Fase 5 - P2, DESIGN.md §8, RULES.md R10.2).
 *
 * Permite a paramédicos y auxiliadores consultar los datos vitales del motociclista accidentado
 * (tipo de sangre, alergias, contactos ICE, póliza de seguro) directamente sobre la pantalla de bloqueo
 * sin requerir el PIN, patrón o huella dactilar, SOLO si el usuario activó la opción [EmergencyMedicalSheet.showOnLockScreen].
 */
class EmergencyLockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // T-07 (B2-3): defensa en profundidad; actividad no exportada y solo lanzamientos internos expl?citos.
        if (intent?.component?.packageName != null && intent.component?.packageName != packageName) {
            finish()
            return
        }
        if (!intent.getBooleanExtra(EXTRA_INTERNAL_LAUNCH, false)) {
            finish()
            return
        }

        // Configuración para mostrar la actividad sobre la pantalla de bloqueo (Keyguard)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Si el usuario desactivó explícitamente mostrar la ficha en pantalla de bloqueo, salir
        val repository = VaultRepository.getInstance(this)
        val sheet = repository.medicalSheetFlow.value
        if (!sheet.showOnLockScreen) {
            finish()
            return
        }

        setContent {
            val liveSheet by repository.medicalSheetFlow.collectAsState()
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = HeritageSand
            ) {
                LockScreenEmergencySheetContent(
                    sheet = liveSheet,
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_INTERNAL_LAUNCH = "com.rutabikersync.app.extra.INTERNAL_EMERGENCY_LAUNCH"

        fun createIntent(context: Context): Intent {
            return Intent(context, EmergencyLockScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_INTERNAL_LAUNCH, true)
            }
        }
    }
}

/**
 * Vista visual de la Ficha de Emergencia optimizada para pantalla de bloqueo y uso por paramédicos.
 */
@Composable
fun LockScreenEmergencySheetContent(
    sheet: EmergencyMedicalSheet,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HeritageSand)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Cabecera de Alerta Médica
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
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(HeritageRustRed),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Emergencia",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column {
                    Text(
                        text = "FICHA DE RESCATE BIKER",
                        color = HeritageRustRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Pantalla de Bloqueo (ICE)",
                        color = HeritageLeatherBrown,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(HeritageCardWarm)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar",
                    tint = HeritageLeatherBrown
                )
            }
        }

        // 1. Tarjeta Grupo Sanguíneo y Donante (Métrica Vital)
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "GRUPO SANGUÍNEO",
                        color = HeritageLeatherMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = sheet.bloodType,
                        color = HeritageRustRed,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                if (sheet.isOrganDonor) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(HeritageGreenLight)
                            .border(1.dp, HeritageGreen, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Donante",
                                tint = HeritageGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "DONANTE DE ÓRGANOS",
                                color = HeritageGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 2. Alergias Críticas (Alerta Roja)
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Alergias",
                        tint = HeritageRustRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ALERGIAS MÉDICAS CRÍTICAS",
                        color = HeritageRustRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = sheet.allergies.ifBlank { "Ninguna conocida" },
                    color = HeritageLeatherBrown,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 3. Contacto de Emergencia ICE con llamada directa
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "CONTACTO DE EMERGENCIA (ICE)",
                    color = HeritageCopper,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sheet.emergencyContactName,
                            color = HeritageLeatherBrown,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${sheet.emergencyContactRelationship} · ${sheet.emergencyContactPhone}",
                            color = HeritageLeatherMuted,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = {
                            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                                data = Uri.parse("tel:${sheet.emergencyContactPhone}")
                            }
                            context.startActivity(dialIntent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Llamar",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("Llamar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 4. Medicamentos Habituales y Condiciones
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "CONDICIONES Y TRATAMIENTOS",
                    color = HeritageAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Condiciones Crónicas:", color = HeritageLeatherMuted, fontSize = 11.sp)
                        Text(sheet.chronicConditions.ifBlank { "Ninguna" }, color = HeritageLeatherBrown, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Medicamentos:", color = HeritageLeatherMuted, fontSize = 11.sp)
                        Text(sheet.habitualMedications.ifBlank { "Ninguno" }, color = HeritageLeatherBrown, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // 5. Seguro Médico y Póliza
        if (sheet.medicalInsuranceName.isNotBlank()) {
            HeritageBevelCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Seguro",
                            tint = HeritageGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "SEGURO Y COBERTURA DE MOTO",
                            color = HeritageGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = sheet.medicalInsuranceName,
                        color = HeritageLeatherBrown,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Póliza: ${sheet.insurancePolicyNumber.ifBlank { "N/A" }}",
                        color = HeritageLeatherMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Aviso Legal Obligatorio R10.5
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(HeritageCardWarm)
                .border(1.dp, HeritageBorder, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Legal",
                    tint = HeritageAmber,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Aviso Legal (R10.5): Esta ficha médica es proporcionada voluntariamente por el usuario para asistencia en emergencias de carretera. No sustituye la evaluación ni intervención de los servicios públicos o privados de urgencias médicas.",
                    color = HeritageLeatherMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Botón Cerrar
        Button(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Cerrar Ficha de Bloqueo", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
