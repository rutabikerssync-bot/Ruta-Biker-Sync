package com.rutabikersync.app.feature.profile

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.core.util.ImageCompressor
import kotlinx.coroutines.launch

/**
 * Pantalla de Soporte Técnico, Preguntas Frecuentes y Reporte de Bugs (D98).
 * Estética Dark Elite: Slate-950 con acentos dorados y esmeralda.
 */
@Composable
fun SupportScreen(
    isSuggestionMode: Boolean = false,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var subject by remember { mutableStateOf(if (isSuggestionMode) "Sugerencia de nueva función" else "") }
    var description by remember { mutableStateOf("") }
    var screenshotBase64 by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var isSentSuccess by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val result = ImageCompressor.compressFromUri(context, uri)
            result.onSuccess { (_, b64) ->
                screenshotBase64 = b64
                Toast.makeText(context, "Captura adjunta (< 2 MB)", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Error al procesar captura: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1117))
    ) {
        // TopBar Canónica
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161922))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver al Perfil",
                    tint = Color(0xFFE5B869)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = if (isSuggestionMode) "Buzón de Sugerencias" else "Soporte y Asistencia",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isSuggestionMode) "Comparte tus ideas para la app" else "Atención técnica al piloto de hermandad",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Tarjeta de Formulario de Reporte
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF181C26),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E3547))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = if (isSuggestionMode) "💡 Enviar Sugerencia de Mejora" else "Reportar una Incidencia o Mejora",
                        color = Color(0xFFE5B869),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text("Asunto", color = Color(0xFF94A3B8)) },
                        placeholder = { Text("Ej. Error de sincronización de malla", color = Color(0xFF475569)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFE5B869),
                            unfocusedBorderColor = Color(0xFF333B4F)
                        )
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Descripción del problema", color = Color(0xFF94A3B8)) },
                        placeholder = { Text("Indica los pasos para reproducir o lo que observaste...", color = Color(0xFF475569)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFE5B869),
                            unfocusedBorderColor = Color(0xFF333B4F)
                        )
                    )

                    // Adjuntar captura
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { photoPickerLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (screenshotBase64 != null) Color(0xFF1E3A2F) else Color(0xFF242A38),
                                contentColor = if (screenshotBase64 != null) Color(0xFF34D399) else Color(0xFFCBD5E1)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (screenshotBase64 != null) "✓ Captura Adjunta" else "📎 Adjuntar Captura",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (screenshotBase64 != null) {
                            Text(
                                text = "Quitar",
                                color = Color(0xFFEF4444),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { screenshotBase64 = null }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Botón Enviar
                    Button(
                        onClick = {
                            if (subject.trim().length < 3 || description.trim().length < 5) {
                                Toast.makeText(context, "Por favor completa el asunto y la descripción", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSending = true
                            scope.launch {
                                val res = ClubApiClient.getInstance().submitSupportReport(
                                    subject = subject.trim(),
                                    message = description.trim(),
                                    screenshotBase64 = screenshotBase64
                                )
                                isSending = false
                                if (res.isSuccess) {
                                    isSentSuccess = true
                                    subject = ""
                                    description = ""
                                    screenshotBase64 = null
                                    Toast.makeText(context, "Reporte enviado con éxito", Toast.LENGTH_LONG).show()
                                } else {
                                    val code = res.getOrNull()?.statusCode ?: 500
                                    Toast.makeText(context, "Error al enviar reporte: $code", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isSending && subject.isNotBlank() && description.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE5B869),
                            contentColor = Color(0xFF101217)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF101217),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enviando reporte...", fontWeight = FontWeight.Bold)
                        } else {
                            Text(
                                text = if (isSuggestionMode) "Enviar Sugerencia" else "Enviar Reporte",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isSentSuccess) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF142B20), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34D399))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "¡Reporte recibido! Nuestro equipo técnico lo revisará.",
                                color = Color(0xFF34D399),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Contacto Directo por Correo
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:soporte@rutabikersync.com")
                            putExtra(Intent.EXTRA_SUBJECT, "Consulta / Soporte Biker — Ruta Biker Sync")
                        }
                        runCatching { context.startActivity(emailIntent) }
                            .onFailure {
                                Toast.makeText(context, "Escribe a: soporte@rutabikersync.com", Toast.LENGTH_LONG).show()
                            }
                    },
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF161922),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3142))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF242C3D)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Email, contentDescription = null, tint = Color(0xFFE5B869))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Email de Soporte Oficial",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "soporte@rutabikersync.com",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Preguntas Frecuentes (FAQ)
            Text(
                text = "Preguntas Frecuentes (FAQ)",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            val faqs = listOf(
                "¿Cómo funciona la malla BLE sin internet?" to "La red mesh BLE permite enlazar los teléfonos de los pilotos de moto a moto en saltos de radio de corto alcance. Las posiciones, señales y alertas SOS se retransmiten de nodo a nodo sin requerir señal celular ni datos móviles.",
                "¿Mis datos médicos son privados?" to "Absolutamente. Tus datos médicos sensibles (tipo de sangre, alergias, donante) se sellan con cifrado de grado militar E2EE en tu dispositivo. Solo se transmiten de forma efímera durante una emergencia vial SOS real activa a los rescatistas autorizados.",
                "¿Cómo descargo mapas para rodar offline?" to "En la sección de Rodadas o Bóveda puedes acceder a Mapas Offline. Elige el corredor vial de tu ruta y descárgalo con antelación en formato vectorial PMTiles para disponer de cartografía 100% off-grid.",
                "¿Cómo invito a compañeros a mi club?" to "Desde la pestaña Mi Club, el Líder o Capitán de Ruta puede generar un código QR o enlace de invitación hermandad para que los integrantes lo escaneen y se unan al convoy.",
                "¿Qué hago si se interrumpe la conexión del convoy?" to "Verifica que el Bluetooth y la Ubicación estén encendidos. Acércate a menos de 50 metros del piloto más cercano para que la malla táctica vuelva a asociar tu nodo automáticamente."
            )

            faqs.forEach { (q, a) ->
                FaqCard(question = q, answer = a)
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun FaqCard(question: String, answer: String) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF161922),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262D3D))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = question,
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = answer,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
