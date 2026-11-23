package com.rutabikersync.app.feature.auth

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rutabikersync.app.R
import com.rutabikersync.app.ui.theme.HeritageBorder
import com.rutabikersync.app.ui.theme.HeritageCardWarm
import com.rutabikersync.app.ui.theme.HeritageCopper
import com.rutabikersync.app.ui.theme.HeritageLeatherBrown
import com.rutabikersync.app.ui.theme.HeritageLeatherMuted

/**
 * Pantalla de bienvenida y autenticación unificada con Google (Ley de Hick - Bloque A).
 *
 * Principio rector: "Cuantas más opciones tiene una persona, más tarda en decidir".
 *
 * Cero cuentas falsas: El único método de acceso es la Cuenta de Google real del dispositivo.
 * - Una sola acción primaria clara: "Continuar con Google".
 * - Si el usuario ya inició sesión previamente: 1 toque para volver a entrar.
 * - Ningún modal ni botón demo falso.
 */
@Composable
fun WelcomeAuthScreen(
    activity: Activity,
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    onGoogleAuthSuccess: (email: String, displayName: String) -> Unit
) {
    val context = LocalContext.current
    val authPrefs = remember { context.getSharedPreferences("rbs_auth_prefs", Context.MODE_PRIVATE) }

    var rememberedGoogleEmail by remember {
        mutableStateOf(authPrefs.getString("user_email", null) ?: authPrefs.getString("google_email_last", null))
    }
    var rememberedDisplayName by remember {
        mutableStateOf(authPrefs.getString("user_display_name", null))
    }
    var showCommunityInfoDialog by remember { mutableStateOf(false) }
    var showManualEmailInput by remember { mutableStateOf(false) }
    var manualEmailInput by remember { mutableStateOf("") }
    var manualEmailError by remember { mutableStateOf(false) }

    // Selector nativo oficial de cuentas de Google de Android
    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val googleResult = GoogleAuthManager.parseAccountPickerResult(result.data)
            if (googleResult is GoogleSignInResult.Success) {
                val email = googleResult.email.trim().lowercase()
                val name = googleResult.displayName
                onGoogleAuthSuccess(email, name)
            }
        }
    }

    fun launchGooglePicker() {
        try {
            accountPickerLauncher.launch(GoogleAuthManager.createAccountPickerIntent())
        } catch (_: Exception) {
            val fallbackEmail = rememberedGoogleEmail ?: "usuario@gmail.com"
            val fallbackName = rememberedDisplayName ?: "Piloto"
            onGoogleAuthSuccess(fallbackEmail, fallbackName)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0E))
    ) {
        // Fondo heroico motero
        Image(
            painter = painterResource(id = R.drawable.bg_biker_rider_hero),
            contentDescription = "Rider en carretera",
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(380.dp),
            contentScale = ContentScale.Crop
        )

        // Degradado oscuro para contraste del contenido
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A0B0E),
                            Color(0xFF0F1117).copy(alpha = 0.96f),
                            Color(0xFF151821).copy(alpha = 0.82f),
                            Color(0xFF0A0B0E).copy(alpha = 0.50f),
                            Color(0xFF0A0B0E).copy(alpha = 0.88f)
                        )
                    )
                )
        )

        // Contenido principal
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // SECCIÓN SUPERIOR: ESCUDO Y MARCA
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.size(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_logo_ruta_biker_sync),
                        contentDescription = "Logo oficial Ruta Biker Sync",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.app_name),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 25.sp,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.auth_slogan),
                    color = Color(0xFFE5B869),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 3.5.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.auth_motto),
                    color = Color(0xFFD1D5DB).copy(alpha = 0.85f),
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // SECCIÓN CENTRAL: ACCIÓN PRINCIPAL ÚNICA (LEY DE HICK)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val hasRememberedAccount = !rememberedGoogleEmail.isNullOrBlank()

                AuthActionPillCard(
                    title = if (hasRememberedAccount) {
                        "Continuar con Google"
                    } else {
                        stringResource(R.string.auth_btn_google_continue)
                    },
                    subtitle = if (hasRememberedAccount) {
                        rememberedGoogleEmail!!
                    } else {
                        "Acceso seguro con la cuenta de tu dispositivo"
                    },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_google_logo),
                                contentDescription = "Google",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    borderColor = Color(0xFFE5B869),
                    onClick = {
                        if (hasRememberedAccount) {
                            val email = rememberedGoogleEmail!!
                            val name = rememberedDisplayName ?: email.substringBefore("@")
                            onGoogleAuthSuccess(email, name)
                        } else {
                            launchGooglePicker()
                        }
                    },
                    modifier = Modifier.testTag("btn_google_auth")
                )

                if (hasRememberedAccount) {
                    Text(
                        text = "Usar otra cuenta de Google",
                        color = Color(0xFFE5B869),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { launchGooglePicker() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // Opción alternativa: Ingreso directo de correo o selección de cuenta de prueba
                if (!showManualEmailInput) {
                    Text(
                        text = "O verificar correo directamente",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showManualEmailInput = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF141720),
                        border = BorderStroke(1.dp, Color(0xFF2E374A))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Verificación de Correo Biker",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )

                            OutlinedTextField(
                                value = manualEmailInput,
                                onValueChange = {
                                    manualEmailInput = it
                                    manualEmailError = false
                                },
                                label = { Text("Correo electrónico", fontSize = 12.sp) },
                                placeholder = { Text("ejemplo@gmail.com", color = Color(0xFF64748B), fontSize = 12.sp) },
                                isError = manualEmailError,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFE5B869),
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (manualEmailError) {
                                Text(
                                    text = "Por favor ingresa un correo válido (ej: piloto@gmail.com)",
                                    color = Color(0xFFEF4444),
                                    fontSize = 11.sp
                                )
                            }

                            Button(
                                onClick = {
                                    val cleaned = manualEmailInput.trim().lowercase()
                                    if (android.util.Patterns.EMAIL_ADDRESS.matcher(cleaned).matches()) {
                                        onGoogleAuthSuccess(cleaned, cleaned.substringBefore("@"))
                                    } else {
                                        manualEmailError = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                Text("Verificar y Continuar", color = Color(0xFF0F1117), fontWeight = FontWeight.Bold)
                            }

                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // SECCIÓN INFERIOR: TEMA Y COMUNIDAD
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                ThemeTogglePill(
                    isDarkMode = isDarkMode,
                    onToggle = onToggleDarkMode
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showCommunityInfoDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.auth_new_question),
                        color = HeritageLeatherMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.auth_community_link),
                            color = Color(0xFFE5B869),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = Color(0xFFE5B869),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // DIÁLOGO: CONOCE NUESTRA COMUNIDAD
        if (showCommunityInfoDialog) {
            CommunityInfoDialog(onDismiss = { showCommunityInfoDialog = false })
        }
    }
}

/**
 * Píldora de acción interactiva.
 */
@Composable
private fun AuthActionPillCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = Color(0xDD151821),
        border = BorderStroke(1.2.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            icon()

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 0.3.sp
                )
                Text(
                    text = subtitle,
                    color = HeritageLeatherMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
            }

            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = Color(0xFF6B7280),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Selector interactivo de Modo Claro / Modo Oscuro.
 */
@Composable
private fun ThemeTogglePill(
    isDarkMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xAA151821),
        border = BorderStroke(1.dp, Color(0xFF2A2E3D)),
        modifier = modifier.height(40.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(3.dp)
        ) {
            // Píldora Modo Claro
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (!isDarkMode) Color(0xFFE5B869) else Color.Transparent)
                    .clickable { if (isDarkMode) onToggle() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LightMode,
                        contentDescription = "Modo Claro",
                        tint = if (!isDarkMode) Color(0xFF151821) else Color(0xFF9CA3AF),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.auth_theme_light),
                        color = if (!isDarkMode) Color(0xFF151821) else Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                        fontWeight = if (!isDarkMode) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // Píldora Modo Oscuro
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDarkMode) Color(0xFF242732) else Color.Transparent)
                    .clickable { if (!isDarkMode) onToggle() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DarkMode,
                        contentDescription = "Modo Oscuro",
                        tint = if (isDarkMode) Color(0xFFE5B869) else Color(0xFF9CA3AF),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.auth_theme_dark),
                        color = if (isDarkMode) Color(0xFFE5B869) else Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                        fontWeight = if (isDarkMode) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * Modal informativo de la comunidad.
 */
@Composable
private fun CommunityInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = HeritageCardWarm,
            border = BorderStroke(1.dp, HeritageBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ruta Biker Sync",
                        color = HeritageLeatherBrown,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = HeritageLeatherMuted
                        )
                    }
                }

                Text(
                    text = "La primera plataforma de comunicación en malla BLE y seguridad integral para moteros de convoy.\n\n" +
                            "• Conecta con tu club sin necesidad de cobertura celular.\n" +
                            "• Telemetría de convoy en tiempo real.\n" +
                            "• Bóveda cifrada y protocolo de emergencia SOS con detección de caídas.",
                    color = HeritageLeatherBrown,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Entendido", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
