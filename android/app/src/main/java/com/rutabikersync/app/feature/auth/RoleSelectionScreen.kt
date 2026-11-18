package com.rutabikersync.app.feature.auth

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.feature.groups.AccountType
import com.rutabikersync.app.ui.theme.HeritageCopper

/**
 * Pantalla de definición de propósito en la plataforma (Ley de Hick - Bloque B).
 *
 * Máxima simplicidad cognitiva: 2 opciones claras, excluyentes y autoexplicativas.
 */
@Composable
fun RoleSelectionScreen(
    userName: String,
    onRoleConfirmed: (AccountType) -> Unit,
    userEmail: String = ""
) {
    val isTecnoLeader = userEmail.trim().equals("omartinezpaz@gmail.com", ignoreCase = true)
    val isXiaomiMember = userEmail.trim().equals("ozmartinezpaz@gmail.com", ignoreCase = true)

    var selectedRole by remember {
        mutableStateOf<AccountType?>(
            when {
                isTecnoLeader -> AccountType.LEADER
                isXiaomiMember -> AccountType.MEMBER
                else -> null
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1117))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // SECCIÓN SUPERIOR: PROPÓSITO
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "¡Bienvenido, $userName!",
                    color = Color(0xFFE5B869),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "¿Cuál es tu rol principal en Ruta Biker Sync?",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 30.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Tu experiencia, herramientas de convoy y permisos se adaptan a lo que elijas.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            // SECCIÓN CENTRAL: LAS 2 TARJETAS (LEY DE HICK + PLANES DE SUSCRIPCIÓN)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // OPCIÓN 1: LÍDER DE CLUB ($15 USD / mes · 14 días gratis)
                RoleCard(
                    title = "Líder de Club",
                    badge = if (isTecnoLeader) "Líder Asignado · TECNO" else "Fundador / Directiva",
                    price = "$15 USD / mes",
                    trialBadge = "14 DÍAS DE PRUEBA GRATIS",
                    description = "Crea o administra tu propio Moto Club. Convoca rodadas oficiales, gestiona rutas y emite códigos QR de admisión para nuevos miembros.",
                    features = listOf(
                        "Creación y gobierno de 1 Moto Club oficial",
                        "Generación ilimitada de pases QR de admisión",
                        "Convocatoria de rodadas en vivo y radar convoy",
                        "Telemetría táctica y baliza SOS de rescate"
                    ),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFFE5B869),
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    iconBgColor = Color(0xFF78350F).copy(alpha = 0.35f),
                    borderColor = if (selectedRole == AccountType.LEADER) Color(0xFFE5B869) else Color(0xFF2A2E3D),
                    isSelected = selectedRole == AccountType.LEADER,
                    onClick = { selectedRole = AccountType.LEADER }
                )

                // OPCIÓN 2: MIEMBRO DE CONVOY ($2.50 USD / mes · 14 días gratis)
                RoleCard(
                    title = "Miembro de Convoy",
                    badge = if (isXiaomiMember) "Miembro Asignado · Redmi Note 13" else "Rider / Piloto",
                    price = "$2.50 USD / mes",
                    trialBadge = "14 DÍAS DE PRUEBA GRATIS",
                    description = "Únete a un Club existente escaneando el código QR del Líder o ingresando el código de pase. Rueda protegido en convoy con telemetría y SOS.",
                    features = listOf(
                        "Ingreso a Club mediante escaneo QR",
                        "Radar de convoy en tiempo real",
                        "Canal directo y chat táctico off-grid",
                        "Ficha médica de rescate SOS encriptada"
                    ),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    iconBgColor = Color(0xFF0369A1).copy(alpha = 0.35f),
                    borderColor = if (selectedRole == AccountType.MEMBER) Color(0xFF38BDF8) else Color(0xFF2A2E3D),
                    isSelected = selectedRole == AccountType.MEMBER,
                    onClick = { selectedRole = AccountType.MEMBER }
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            // SECCIÓN INFERIOR: BOTÓN CONTINUAR / ACTIVAR PRUEBA
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val role = selectedRole ?: return@Button
                        onRoleConfirmed(role)
                    },
                    enabled = selectedRole != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedRole == AccountType.LEADER) Color(0xFFE5B869) else Color(0xFF0284C7),
                        disabledContainerColor = Color(0xFF2A2E3D)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(
                        text = when (selectedRole) {
                            AccountType.LEADER -> "Activar 14 Días Gratis · Plan Líder ($15/mes)"
                            AccountType.MEMBER -> "Activar 14 Días Gratis · Plan Miembro ($2.50/mes)"
                            null -> "Selecciona un plan para continuar"
                        },
                        color = if (selectedRole == AccountType.LEADER) Color(0xFF161A23) else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Text(
                    text = "🔒 14 días de prueba gratuita. Cancela en cualquier momento sin penalizaciones desde Ajustes de Cuenta.",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun RoleCard(
    title: String,
    badge: String,
    price: String,
    trialBadge: String,
    description: String,
    features: List<String>,
    icon: @Composable () -> Unit,
    iconBgColor: Color,
    borderColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) Color(0xFF1A1F2C) else Color(0xFF141720),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(iconBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        icon()
                    }

                    Column {
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = badge,
                                color = if (badge.contains("Asignado")) Color(0xFF34D399) else Color(0xFF9CA3AF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) borderColor else Color.Transparent)
                        .border(1.5.dp, if (isSelected) borderColor else Color(0xFF4B5563), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Seleccionado",
                            tint = if (borderColor == Color(0xFFE5B869)) Color(0xFF161A23) else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Precio y Badge de Prueba Gratuita
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = price,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF064E3B).copy(alpha = 0.6f))
                        .border(1.dp, Color(0xFF10B981), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = trialBadge,
                        color = Color(0xFF34D399),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Text(
                text = description,
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            // Beneficios del Plan
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                features.forEach { feat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "✓",
                            color = borderColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = feat,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
