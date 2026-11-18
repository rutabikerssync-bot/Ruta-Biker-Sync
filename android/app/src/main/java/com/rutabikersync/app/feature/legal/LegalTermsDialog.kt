package com.rutabikersync.app.feature.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Diálogo interactivo para visualizar los Términos de Servicio, Reglas de Convivencia Biker
 * y Política de Privacidad de Ruta Biker Sync (Dark Elite Aesthetic).
 */
@Composable
fun LegalTermsDialog(
    onDismissRequest: () -> Unit,
    initialTab: Int = 0,
    showAcceptButton: Boolean = false,
    onAccept: (() -> Unit)? = null
) {
    var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0F121A))
                .border(1.dp, Color(0xFF262A36), RoundedCornerShape(20.dp))
                .padding(18.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Cabecera
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E2330))
                                .border(1.dp, Color(0xFFE5B869).copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Gavel,
                                contentDescription = "Legal",
                                tint = Color(0xFFE5B869),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Marco Legal y Convivencia",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ruta Biker Sync · Versión 1.1",
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color(0xFF9CA3AF)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Selector de Pestañas
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF141824),
                    contentColor = Color(0xFFE5B869),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFFE5B869),
                            height = 2.dp
                        )
                    },
                    divider = {
                        HorizontalDivider(color = Color(0xFF262A36))
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = "Convivencia",
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 0) Color(0xFFE5B869) else Color(0xFF9CA3AF)
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = "Términos",
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 1) Color(0xFFE5B869) else Color(0xFF9CA3AF)
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                text = "Privacidad",
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 2) Color(0xFFE5B869) else Color(0xFF9CA3AF)
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Contenido Scrolleable según Pestaña
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141824).copy(alpha = 0.5f))
                        .border(1.dp, Color(0xFF262A36).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    val scrollState = rememberScrollState()
                    Column(modifier = Modifier.verticalScroll(scrollState)) {
                        when (selectedTab) {
                            0 -> RulesOfConductContent()
                            1 -> TermsOfServiceContent()
                            2 -> PrivacyPolicyContent()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Barra Inferior de Acción
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showAcceptButton && onAccept != null) {
                        Button(
                            onClick = {
                                onAccept()
                                onDismissRequest()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE5B869),
                                contentColor = Color(0xFF0A0B0E)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Acepto las Reglas y Términos", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = onDismissRequest,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E2330),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Entendido", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesOfConductContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LegalSectionHeader(
            title = "Código de Convivencia y Respeto en Ruta (RULES §13)",
            icon = Icons.Default.Security,
            color = Color(0xFFE5B869)
        )

        LegalParagraph(
            title = "1. Conducción Segura y No Manipulación",
            body = "La app está diseñada con Modo Guantes (botones ≥72 dp) y voz sintética. Queda terminantemente prohibido manipular la pantalla o escribir mientras la motocicleta se encuentra en movimiento. El teléfono debe usarse en soporte seguro o comunicarse vía intercomunicador Bluetooth."
        )

        LegalParagraph(
            title = "2. Voz Efímera y No Grabación (R13.1)",
            body = "Los clips de audio Push-To-Talk viajan de forma efímera en la malla para alertas operativas (baches, frenadas, cruces). No se graban ni se conservan en ningún servidor central. Al concluir la rodada, se purgan de memoria."
        )

        LegalParagraph(
            title = "3. Silenciado Local Inmediato (R13.3)",
            body = "Cualquier rider puede silenciar localmente al instante a un compañero si considera su tono ofensivo o innecesario, sin interrumpir la recepción del resto del grupo."
        )

        LegalParagraph(
            title = "4. Reportes y Moderación Democrática (R13.2 / R14)",
            body = "Cualquier participante puede reportar conductas peligrosas, insultos o acoso durante o después de la salida. Los reportes llegan exclusivamente a los líderes del grupo (sin exponer ubicación ni audios) y se resuelven por mayoría calificada de 2 de 3 líderes."
        )

        LegalParagraph(
            title = "5. Sanciones Progresivas (R13.4)",
            body = "Las sanciones incluyen: advertencia formal, silenciado en la rodada, expulsión de la salida y retiro del grupo. Infracciones legales graves causarán la suspensión permanente de la cuenta por la plataforma."
        )
    }
}

@Composable
private fun TermsOfServiceContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LegalSectionHeader(
            title = "Términos de Servicio y Operación (RULES §1 - §7)",
            icon = Icons.Default.Gavel,
            color = Color(0xFF3B82F6)
        )

        LegalParagraph(
            title = "1. Registro y Membresías de Club",
            body = "La descarga de la aplicación y la cuenta de Miembro son gratuitas. La cuenta de Líder de Club requiere suscripción de $15 USD/mes gestionada vía Google Play. Cada líder administra y responde únicamente por su propio grupo (un líder = un solo grupo, D42)."
        )

        LegalParagraph(
            title = "2. Prohibición de Membresías Cruzadas (R2.2)",
            body = "Para preservar la identidad y hermandad de los clubes tradicionales, ningún usuario puede pertenecer a más de un grupo oficial a la vez. La participación con otros clubes se realiza exclusivamente como Invitado Temporal a una rodada autorizada."
        )

        LegalParagraph(
            title = "3. Invitaciones y Manifiestos de Rodada",
            body = "El ingreso a una rodada se rige por un Manifiesto Criptográfico firmado digitalmente por la autoridad del club con claves Ed25519. Solo los participantes registrados y validados en el manifiesto pueden interactuar en la malla cifrada."
        )

        LegalParagraph(
            title = "4. Exención de Responsabilidad en Carretera",
            body = "Ruta Biker Sync es una herramienta de auxilio, telemetría y coordinación en convoy. La aplicación no sustituye las leyes de tráfico locales, las pólizas de seguro vial ni el juicio prudente de cada piloto. Cada usuario es plenamente responsable de su propia conducción, su vehículo y sus actos en la vía pública."
        )
    }
}

@Composable
private fun PrivacyPolicyContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LegalSectionHeader(
            title = "Política de Privacidad y Protección de Datos (RULES §15)",
            icon = Icons.Default.Lock,
            color = Color(0xFF10B981)
        )

        LegalParagraph(
            title = "1. Arquitectura Cero-Rastreo en Nube",
            body = "El servidor central NO recopila ni almacena ubicaciones GPS en tiempo real ni telemetría de ruta. Las coordenadas viajan de forma P2P exclusivamente entre los dispositivos conectados a la malla Bluetooth/BLE durante la rodada."
        )

        LegalParagraph(
            title = "2. Ubicación en Segundo Plano (ACCESS_BACKGROUND_LOCATION)",
            body = "Se solicita permiso de ubicación en segundo plano con justificación exclusiva para mantener la conectividad del convoy, alertar pérdidas de distancia y despachar el SOS automático de colisión cuando la pantalla está apagada o el móvil bloqueado en el bolsillo. Dicho servicio Foreground muestra una notificación persistente y cesa inmediatamente al culminar la rodada."
        )

        LegalParagraph(
            title = "3. Bóveda Médica y de Emergencia Cifrada (AES-256-GCM)",
            body = "Los datos médicos (tipo de sangre, alergias, contactos de rescate) y pólizas se cifran localmente en el dispositivo utilizando el Android Keystore y derivación PBKDF2. El servidor central nunca tiene acceso a las llaves privadas ni a los documentos escaneados."
        )

        LegalParagraph(
            title = "4. Consentimientos Granulares por Grupo (R15.1)",
            body = "El usuario otorga y revoca consentimientos de forma granular: compartir ubicación, ficha de emergencia y notificaciones. Revocar un consentimiento surte efecto inmediato en la base de datos y la malla."
        )

        LegalParagraph(
            title = "5. Exportación y Derecho al Olvido (RGPD / CCPA R15.3)",
            body = "Desde la pestaña de Perfil, cualquier usuario puede exportar todo su expediente en formato JSON portable o solicitar el borrado permanente e irreversible de su cuenta y registros personales en un solo clic."
        )
    }
}

@Composable
private fun LegalSectionHeader(
    title: String,
    icon: ImageVector,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LegalParagraph(
    title: String,
    body: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = Color(0xFFF3F4F6),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = body,
            color = Color(0xFF9CA3AF),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}
