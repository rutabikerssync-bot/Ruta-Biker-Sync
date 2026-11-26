package com.rutabikersync.app.feature.vault

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.rutabikersync.app.BuildConfig
import com.rutabikersync.app.R
import com.rutabikersync.app.ui.theme.HeritageBorder
import com.rutabikersync.app.ui.theme.HeritageBrassGold
import com.rutabikersync.app.ui.theme.HeritageCard
import com.rutabikersync.app.ui.theme.HeritageCardWarm
import com.rutabikersync.app.ui.theme.HeritageCopper
import com.rutabikersync.app.ui.theme.HeritageLeatherBrown
import com.rutabikersync.app.ui.theme.HeritageLeatherMuted
import com.rutabikersync.app.ui.theme.HeritageSand

/**
 * Puerta biométrica para la Bóveda (Bloque 5.2 — Fase 5).
 *
 * Muestra una pantalla bloqueada con botón "Desbloquear con huella / PIN" hasta que
 * [BiometricPrompt] confirme la identidad. Solo entonces muestra el [content].
 *
 * En `UX_TEST_MODE` (debug build) aparece además un botón "Simular autenticación OK" para
 * que el moderador pueda acceder a la bóveda sin hardware biométrico.
 *
 * REGLA: nunca saltar la autenticación silenciosamente. El botón de simulación es
 * exclusivo de builds con `BuildConfig.UX_TEST_MODE == true`.
 */
@Composable
fun VaultBiometricGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isUnlocked by remember { mutableStateOf(false) }
    val isForceUnlocked by VaultTestHook.isForceUnlocked.collectAsState()
    val context = LocalContext.current

    if (isUnlocked || (com.rutabikersync.app.BuildConfig.DEBUG && isForceUnlocked)) {
        content()
        return
    }

    // Pantalla de bloqueo con tema Heritage Light
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HeritageSand),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            // Tarjeta visual de Bóveda protegida (mismo estándar full-bleed + degradado)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(HeritageCardWarm)
                    .border(1.2.dp, HeritageBorder, RoundedCornerShape(18.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.card_boveda_protegida),
                    contentDescription = "Bóveda protegida Ruta Biker Sync",
                    modifier = Modifier.matchParentSize(),
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    HeritageSand.copy(alpha = 0.35f),
                                    HeritageSand.copy(alpha = 0.90f)
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(HeritageCardWarm.copy(alpha = 0.85f))
                        .border(1.5.dp, HeritageBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Bóveda bloqueada",
                        tint = HeritageBrassGold,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Bóveda protegida",
                color = HeritageLeatherBrown,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tus documentos y ficha médica están cifrados.\nVerifica tu identidad para continuar.",
                color = HeritageLeatherMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Botón principal: huella / PIN
            Button(
                onClick = {
                    launchBiometricPrompt(
                        context = context,
                        onSuccess = { isUnlocked = true },
                        onError = { /* permanece bloqueado */ }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HeritageBrassGold,
                    contentColor = HeritageLeatherBrown
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "  Desbloquear con huella / PIN",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            if (com.rutabikersync.app.BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { isUnlocked = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Desbloquear (Modo Prueba)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Disclaimer: no sustituye a servicios de emergencia (CHECKLIST H)
            Text(
                text = "⚠️ Esta ficha no sustituye a los servicios de emergencia. En caso de accidente, llama al número de emergencias de tu país.",
                color = HeritageLeatherMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HeritageCardWarm.copy(alpha = 0.8f))
                    .padding(12.dp)
            )
        }
    }
}

/**
 * Lanza [BiometricPrompt] con huella o PIN de respaldo.
 * Compatible con Android 6.0+ (API 23+).
 */
private fun findFragmentActivity(context: android.content.Context): FragmentActivity? {
    var ctx = context
    while (ctx is android.content.ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun launchBiometricPrompt(
    context: android.content.Context,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val activity = findFragmentActivity(context) ?: run {
        onError("No se pudo iniciar el servicio de seguridad del sistema.")
        return
    }

    val biometricManager = BiometricManager.from(context)
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val canAuthenticate = biometricManager.canAuthenticate(authenticators)

    if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS &&
        canAuthenticate != BiometricManager.BIOMETRIC_STATUS_UNKNOWN) {
        // Si no hay biométrico ni credencial configurada en el teléfono
        android.widget.Toast.makeText(
            context,
            "Configura un PIN, patrón o huella en Ajustes de tu teléfono para proteger tu Bóveda.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        onError("Dispositivo sin bloqueo de pantalla configurado.")
        return
    }

    val executor = ContextCompat.getMainExecutor(context)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onError(errString.toString())
        }

        override fun onAuthenticationFailed() {
            // Intento fallido — no hacer nada; el prompt se mantiene abierto
        }
    }

    val prompt = BiometricPrompt(activity, executor, callback)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Desbloquear Bóveda")
        .setSubtitle("Verifica tu identidad para ver tus documentos")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()

    prompt.authenticate(promptInfo)
}

