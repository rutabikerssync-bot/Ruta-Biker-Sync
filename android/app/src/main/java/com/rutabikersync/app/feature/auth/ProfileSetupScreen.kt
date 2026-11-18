package com.rutabikersync.app.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.HeritageBorder
import com.rutabikersync.app.ui.theme.HeritageCardWarm
import com.rutabikersync.app.ui.theme.HeritageCopper
import com.rutabikersync.app.ui.theme.HeritageLeatherBrown
import com.rutabikersync.app.ui.theme.HeritageLeatherMuted

/**
 * Pantalla de configuración inicial obligatoria de perfil (Gate duro).
 *
 * Cumple con la Ley de Hick y elimina cualquier posibilidad de acceso con cuentas falsas
 * o perfiles vacíos. Sin este paso completado, el usuario no puede acceder al convoy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSetupScreen(
    email: String,
    initialDisplayName: String? = null,
    onSaveProfile: (
        aliasBiker: String,
        motorcycleModel: String,
        pinColorHex: String,
        emergencyContactName: String,
        emergencyContactPhone: String,
        bloodType: BloodType
    ) -> Unit,
    onCancelToOtherAccount: () -> Unit
) {
    var aliasBiker by remember {
        mutableStateOf(initialDisplayName ?: email.substringBefore("@"))
    }
    var motorcycleModel by remember { mutableStateOf("") }
    var selectedColorHex by remember { mutableStateOf("#E5B869") }
    var emergencyContactName by remember { mutableStateOf("") }
    var emergencyContactPhone by remember { mutableStateOf("") }
    var selectedBloodType by remember { mutableStateOf(BloodType.O_POS) }

    val isAliasValid = aliasBiker.trim().length in 3..25
    val isMotorcycleValid = motorcycleModel.trim().length in 3..40
    val isEmergencyNameValid = emergencyContactName.trim().length >= 3
    val isEmergencyPhoneValid = emergencyContactPhone.trim().length >= 7
    val isFormValid = isAliasValid && isMotorcycleValid && isEmergencyNameValid && isEmergencyPhoneValid

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1117))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Cuenta Verificada
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF161A23),
                border = BorderStroke(1.dp, Color(0xFF2A2E3D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(HeritageCopper.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Verificado",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cuenta de Google Verificada",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = email,
                            color = Color(0xFF9CA3AF),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Título Principal
            Text(
                text = "Configura tu Ficha de Piloto",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Requerido para telemetría de convoy, rescate médico SOS y seguridad en ruta.",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // SECCIÓN 1: DATOS DE PILOTO Y MOTO
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF161A23),
                border = BorderStroke(1.dp, Color(0xFF2A2E3D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "1. IDENTIFICACIÓN EN CONVOY",
                        color = Color(0xFFE5B869),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )

                    // Alias Biker
                    OutlinedTextField(
                        value = aliasBiker,
                        onValueChange = { if (it.length <= 25) aliasBiker = it },
                        label = { Text("Alias Biker (Nombre en Convoy)") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = HeritageCopper)
                        },
                        supportingText = {
                            Text(
                                if (!isAliasValid && aliasBiker.isNotBlank()) "Mínimo 3 caracteres"
                                else "Ej: Halcón, Lobo, Sombra",
                                color = if (!isAliasValid && aliasBiker.isNotBlank()) Color(0xFFEF4444) else Color(0xFF6B7280)
                            )
                        },
                        isError = !isAliasValid && aliasBiker.isNotBlank(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = HeritageCopper,
                            unfocusedBorderColor = Color(0xFF374151)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Modelo de Moto
                    OutlinedTextField(
                        value = motorcycleModel,
                        onValueChange = { if (it.length <= 40) motorcycleModel = it },
                        label = { Text("Modelo de Moto") },
                        leadingIcon = {
                            Icon(Icons.Default.TwoWheeler, contentDescription = null, tint = HeritageCopper)
                        },
                        supportingText = {
                            Text(
                                if (!isMotorcycleValid && motorcycleModel.isNotBlank()) "Mínimo 3 caracteres"
                                else "Ej: BMW R1250GS / KLR 650 / V-Strom",
                                color = if (!isMotorcycleValid && motorcycleModel.isNotBlank()) Color(0xFFEF4444) else Color(0xFF6B7280)
                            )
                        },
                        isError = !isMotorcycleValid && motorcycleModel.isNotBlank(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = HeritageCopper,
                            unfocusedBorderColor = Color(0xFF374151)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Color de Pin en Mapa
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = HeritageCopper,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Color de tu baliza en el mapa en vivo",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserProfile.ALLOWED_PIN_COLORS.forEach { colorHex ->
                                val color = Color(android.graphics.Color.parseColor(colorHex))
                                val isSelected = selectedColorHex.equals(colorHex, ignoreCase = true)

                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) Color.White else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColorHex = colorHex },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Seleccionado",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // SECCIÓN 2: FICHA MÉDICA Y EMERGENCIA SOS
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF161A23),
                border = BorderStroke(1.dp, Color(0xFF2A2E3D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "2. FICHA MÉDICA DE RESCATE (SOS)",
                        color = Color(0xFFE5B869),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )

                    // Selector de Tipo de Sangre
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MedicalServices,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Tipo de Sangre",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BloodType.entries.forEach { bType ->
                                val isSelected = selectedBloodType == bType
                                Surface(
                                    onClick = { selectedBloodType = bType },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Color(0xFFDC2626) else Color(0xFF1F2430),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFFEF4444) else Color(0xFF374151)
                                    ),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 14.dp)
                                    ) {
                                        Text(
                                            text = bType.label,
                                            color = if (isSelected) Color.White else Color(0xFFD1D5DB),
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Contacto de Emergencia
                    OutlinedTextField(
                        value = emergencyContactName,
                        onValueChange = { emergencyContactName = it },
                        label = { Text("Nombre de Contacto de Emergencia") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFEF4444))
                        },
                        supportingText = {
                            Text(
                                if (!isEmergencyNameValid && emergencyContactName.isNotBlank()) "Mínimo 3 caracteres"
                                else "Familiar o persona de confianza",
                                color = if (!isEmergencyNameValid && emergencyContactName.isNotBlank()) Color(0xFFEF4444) else Color(0xFF6B7280)
                            )
                        },
                        isError = !isEmergencyNameValid && emergencyContactName.isNotBlank(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = HeritageCopper,
                            unfocusedBorderColor = Color(0xFF374151)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Teléfono de Emergencia
                    OutlinedTextField(
                        value = emergencyContactPhone,
                        onValueChange = { emergencyContactPhone = it },
                        label = { Text("Teléfono de Contacto") },
                        leadingIcon = {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = Color(0xFFEF4444))
                        },
                        supportingText = {
                            Text(
                                if (!isEmergencyPhoneValid && emergencyContactPhone.isNotBlank()) "Mínimo 7 dígitos"
                                else "Número directo para llamadas o WhatsApp de auxilio",
                                color = if (!isEmergencyPhoneValid && emergencyContactPhone.isNotBlank()) Color(0xFFEF4444) else Color(0xFF6B7280)
                            )
                        },
                        isError = !isEmergencyPhoneValid && emergencyContactPhone.isNotBlank(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = HeritageCopper,
                            unfocusedBorderColor = Color(0xFF374151)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // BOTÓN DE ACCIÓN PRIMARIA (BLOQUEANTE)
            Button(
                onClick = {
                    if (isFormValid) {
                        onSaveProfile(
                            aliasBiker.trim(),
                            motorcycleModel.trim(),
                            selectedColorHex,
                            emergencyContactName.trim(),
                            emergencyContactPhone.trim(),
                            selectedBloodType
                        )
                    }
                },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HeritageCopper,
                    disabledContainerColor = Color(0xFF2A2E3D)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "Guardar y Definir Propósito",
                    color = if (isFormValid) Color.White else Color(0xFF6B7280),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Enlace para cambiar de cuenta si ingresó con un Google equivocado
            Text(
                text = "Usar otra cuenta de Google",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { onCancelToOtherAccount() }
                    .padding(8.dp)
            )
        }
    }
}

