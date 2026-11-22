package com.rutabikersync.app.feature.splash

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.BuildConfig
import com.rutabikersync.app.R
import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Splash Screen oficial de inicio con verificación de versiones y carga de cabina segura (D98).
 * Estética Dark Elite: Slate-950 con destellos oro y esmeralda.
 */
@Composable
fun SplashScreen(
    onNavigateToApp: () -> Unit
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Verificando actualizaciones...") }
    var showBlockingUpdateDialog by remember { mutableStateOf(false) }
    var updateUrl by remember { mutableStateOf("https://play.google.com/store/apps/details?id=com.rutabikersync.app") }
    var releaseNotes by remember { mutableStateOf("Existe una actualización obligatoria para continuar rodando con seguridad.") }

    val progressAnim = remember { Animatable(0.1f) }

    LaunchedEffect(Unit) {
        progressAnim.animateTo(
            targetValue = 0.5f,
            animationSpec = tween(durationMillis = 600, easing = LinearEasing)
        )

        val api = ClubApiClient.getInstance()
        val versionRes = runCatching { api.checkAppVersion() }.getOrNull()

        var mustForceUpdate = false
        if (versionRes?.isSuccess == true) {
            val body = versionRes.getOrNull()?.body
            val minVersionCode = runCatching { body?.get("minVersionCode")?.jsonPrimitive?.intOrNull }.getOrNull() ?: 1
            val forceUpdate = runCatching { body?.get("forceUpdate")?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
            val remoteUpdateUrl = runCatching { body?.get("updateUrl")?.jsonPrimitive?.content }.getOrNull()
            val notes = runCatching { body?.get("releaseNotes")?.jsonPrimitive?.content }.getOrNull()

            if (!remoteUpdateUrl.isNullOrBlank()) {
                updateUrl = remoteUpdateUrl
            }
            if (!notes.isNullOrBlank()) {
                releaseNotes = notes
            }

            val currentCode = BuildConfig.VERSION_CODE
            if (forceUpdate && currentCode < minVersionCode) {
                mustForceUpdate = true
            }
        }

        if (mustForceUpdate) {
            progressAnim.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 300)
            )
            statusText = "Actualización obligatoria disponible"
            showBlockingUpdateDialog = true
        } else {
            statusText = "Cabina táctica lista · Conectando..."
            progressAnim.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 400, easing = LinearEasing)
            )
            delay(250)
            onNavigateToApp()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0B0E14), Color(0xFF121620), Color(0xFF090A0F))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Logo Oficial Estampado
            Box(
                modifier = Modifier
                    .size(136.dp)
                    .shadow(24.dp, shape = CircleShape, spotColor = Color(0xFFE5B869))
                    .clip(CircleShape)
                    .background(Color(0xFF161922))
                    .border(2.dp, Color(0xFFE5B869), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_logo_ruta_biker_sync),
                    contentDescription = "Logo oficial Ruta Biker Sync",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(118.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "RUTA BIKER SYNC",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )

            Text(
                text = "HERMANDAD Y MALLA TÁCTICA OFF-GRID",
                color = Color(0xFFE5B869),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Barra de Progreso Dark Elite
            LinearProgressIndicator(
                progress = { progressAnim.value },
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(0xFFE5B869),
                trackColor = Color(0xFF262C3A),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = statusText,
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                color = Color(0xFF475569),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Diálogo Bloqueante de Actualización Obligatoria
        if (showBlockingUpdateDialog) {
            AlertDialog(
                onDismissRequest = { /* Bloqueante: no permite descartar */ },
                containerColor = Color(0xFF161922),
                shape = RoundedCornerShape(20.dp),
                title = {
                    Text(
                        text = "🚀 Actualización Requerida",
                        color = Color(0xFFE5B869),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = releaseNotes,
                            color = Color(0xFFCBD5E1),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Para garantizar la integridad del cifrado de bóveda, la malla BLE y los protocolos de emergencia vial, actualiza la aplicación.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE5B869),
                            contentColor = Color(0xFF0F1117)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Actualizar en Play Store", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}
