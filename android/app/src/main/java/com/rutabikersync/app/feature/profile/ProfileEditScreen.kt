package com.rutabikersync.app.feature.profile

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.core.util.ImageCompressor
import com.rutabikersync.app.feature.auth.ProfileRepository
import com.rutabikersync.app.feature.auth.UserProfile
import com.rutabikersync.app.feature.auth.Visibility
import kotlinx.coroutines.launch

/**
 * Pantalla completa de Edición Enriquecida de Perfil de Piloto y Motocicleta (D98).
 * Soporta selección de avatar, foto de la moto, especificaciones mecánicas y privacidad.
 */
@Composable
fun ProfileEditScreen(
    currentProfile: UserProfile,
    onNavigateBack: () -> Unit,
    onProfileSaved: (UserProfile) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ProfileRepository(context) }

    var aliasBiker by remember { mutableStateOf(currentProfile.aliasBiker) }
    var pinColorHex by remember { mutableStateOf(currentProfile.pinColorHex) }

    var photoBase64 by remember { mutableStateOf<String?>(currentProfile.photoBase64) }
    var photoUrl by remember { mutableStateOf<String?>(currentProfile.photoUrl) }

    var motorcycleBrand by remember { mutableStateOf(currentProfile.motorcycleBrand) }
    var motorcycleModel by remember { mutableStateOf(currentProfile.motorcycleModel) }
    var motorcycleDisplacement by remember { mutableStateOf(if (currentProfile.motorcycleDisplacement > 0) currentProfile.motorcycleDisplacement.toString() else "") }
    var motorcycleYear by remember { mutableStateOf(if (currentProfile.motorcycleYear > 0) currentProfile.motorcycleYear.toString() else "") }
    var motorcycleColor by remember { mutableStateOf(currentProfile.motorcycleColor) }
    var motorcycleSerial by remember { mutableStateOf(currentProfile.motorcycleSerial) }

    var motorcyclePhotoBase64 by remember { mutableStateOf<String?>(currentProfile.motorcyclePhotoBase64) }
    var motorcyclePhotoUrl by remember { mutableStateOf<String?>(currentProfile.motorcyclePhotoUrl) }

    var profileVisibility by remember { mutableStateOf(currentProfile.profileVisibility) }
    var motorcycleVisibility by remember { mutableStateOf(currentProfile.motorcycleVisibility) }

    var isSaving by remember { mutableStateOf(false) }

    val avatarBitmap = remember(photoBase64) {
        if (!photoBase64.isNullOrBlank()) {
            runCatching {
                val bytes = Base64.decode(photoBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    val motoBitmap = remember(motorcyclePhotoBase64) {
        if (!motorcyclePhotoBase64.isNullOrBlank()) {
            runCatching {
                val bytes = Base64.decode(motorcyclePhotoBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val res = ImageCompressor.compressFromUri(context, uri)
            res.onSuccess { (_, b64) ->
                photoBase64 = b64
                Toast.makeText(context, "Foto de piloto comprimida y lista", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Error al procesar foto: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val motoPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val res = ImageCompressor.compressFromUri(context, uri)
            res.onSuccess { (_, b64) ->
                motorcyclePhotoBase64 = b64
                Toast.makeText(context, "Foto de moto comprimida y lista", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Error al procesar foto: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1117))
    ) {
        // Barra Superior
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
                    contentDescription = "Volver",
                    tint = Color(0xFFE5B869)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Editar Perfil y Motocicleta",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Identidad y ficha técnica del piloto",
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
            // 1. Identidad del Piloto (Avatar y Apodo)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF181C26),
                border = BorderStroke(1.dp, Color(0xFF2E3547))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF242C3D))
                            .border(2.5.dp, Color(0xFFE5B869), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap,
                                contentDescription = "Foto de perfil",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color(0xFFE5B869),
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }

                    Button(
                        onClick = { avatarPickerLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (photoBase64 != null) Color(0xFF1E3A2F) else Color(0xFF262D3D),
                            contentColor = if (photoBase64 != null) Color(0xFF34D399) else Color(0xFFCBD5E1)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (photoBase64 != null) "✓ Foto Seleccionada" else "📷 Cambiar Foto de Piloto",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedTextField(
                        value = aliasBiker,
                        onValueChange = { aliasBiker = it },
                        label = { Text("Apodo de rider", color = Color(0xFF94A3B8)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFE5B869),
                            unfocusedBorderColor = Color(0xFF333B4F)
                        )
                    )

                    // Selector de Color de Pin
                    Text(
                        text = "Color de tu pin en el mapa:",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        UserProfile.ALLOWED_PIN_COLORS.forEach { hex ->
                            val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Yellow)
                            val isSelected = pinColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color(0xFF3E4455),
                                        shape = CircleShape
                                    )
                                    .clickable { pinColorHex = hex }
                            )
                        }
                    }

                    // Visibilidad del Perfil
                    VisibilitySelector(
                        title = "Privacidad del Perfil",
                        selected = profileVisibility,
                        onSelect = { profileVisibility = it }
                    )
                }
            }

            // 2. Ficha Técnica de la Motocicleta
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF181C26),
                border = BorderStroke(1.dp, Color(0xFF2E3547))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Datos de la Motocicleta",
                        color = Color(0xFFE5B869),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = motorcycleBrand,
                            onValueChange = { motorcycleBrand = it },
                            label = { Text("Marca", color = Color(0xFF94A3B8)) },
                            placeholder = { Text("Ej. BMW", color = Color(0xFF475569)) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFE5B869),
                                unfocusedBorderColor = Color(0xFF333B4F)
                            )
                        )

                        OutlinedTextField(
                            value = motorcycleModel,
                            onValueChange = { motorcycleModel = it },
                            label = { Text("Modelo", color = Color(0xFF94A3B8)) },
                            placeholder = { Text("Ej. R1250GS", color = Color(0xFF475569)) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFE5B869),
                                unfocusedBorderColor = Color(0xFF333B4F)
                            )
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = motorcycleDisplacement,
                            onValueChange = { motorcycleDisplacement = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Cilindrada (cc)", color = Color(0xFF94A3B8)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFE5B869),
                                unfocusedBorderColor = Color(0xFF333B4F)
                            )
                        )

                        OutlinedTextField(
                            value = motorcycleYear,
                            onValueChange = { if (it.length <= 4) motorcycleYear = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Año", color = Color(0xFF94A3B8)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFE5B869),
                                unfocusedBorderColor = Color(0xFF333B4F)
                            )
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = motorcycleColor,
                            onValueChange = { motorcycleColor = it },
                            label = { Text("Color", color = Color(0xFF94A3B8)) },
                            placeholder = { Text("Ej. Negro", color = Color(0xFF475569)) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFE5B869),
                                unfocusedBorderColor = Color(0xFF333B4F)
                            )
                        )

                        OutlinedTextField(
                            value = motorcycleSerial,
                            onValueChange = { motorcycleSerial = it.uppercase() },
                            label = { Text("Serial / VIN", color = Color(0xFF94A3B8)) },
                            placeholder = { Text("Nº motor/VIN", color = Color(0xFF475569)) },
                            modifier = Modifier.weight(1.2f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFE5B869),
                                unfocusedBorderColor = Color(0xFF333B4F)
                            )
                        )
                    }

                    // Foto de la Motocicleta
                    if (motoBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.5.dp, Color(0xFFE5B869), RoundedCornerShape(12.dp))
                        ) {
                            Image(
                                bitmap = motoBitmap,
                                contentDescription = "Foto de la moto",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Button(
                        onClick = { motoPhotoPickerLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (motorcyclePhotoBase64 != null) Color(0xFF1E3A2F) else Color(0xFF262D3D),
                            contentColor = if (motorcyclePhotoBase64 != null) Color(0xFF34D399) else Color(0xFFCBD5E1)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (motorcyclePhotoBase64 != null) "✓ Cambiar Foto de la Moto" else "🏍️ Adjuntar Foto de la Moto",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Visibilidad de la Moto
                    VisibilitySelector(
                        title = "Privacidad de la Moto",
                        selected = motorcycleVisibility,
                        onSelect = { motorcycleVisibility = it }
                    )
                }
            }

            // Botón Guardar Perfil
            Button(
                onClick = {
                    if (aliasBiker.trim().length < 3) {
                        Toast.makeText(context, "El apodo de rider debe tener al menos 3 caracteres", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSaving = true
                    val updated = currentProfile.copy(
                        aliasBiker = aliasBiker.trim(),
                        pinColorHex = pinColorHex,
                        photoBase64 = photoBase64,
                        motorcycleBrand = motorcycleBrand.trim(),
                        motorcycleModel = motorcycleModel.trim(),
                        motorcycleDisplacement = motorcycleDisplacement.toIntOrNull() ?: 0,
                        motorcycleYear = motorcycleYear.toIntOrNull() ?: 0,
                        motorcycleColor = motorcycleColor.trim(),
                        motorcycleSerial = motorcycleSerial.trim(),
                        motorcyclePhotoBase64 = motorcyclePhotoBase64,
                        profileVisibility = profileVisibility,
                        motorcycleVisibility = motorcycleVisibility
                    )

                    repository.saveUserProfile(updated)

                    scope.launch {
                        val res = ClubApiClient.getInstance().updateEnrichedProfile(updated)
                        isSaving = false
                        if (res.isSuccess) {
                            Toast.makeText(context, "Perfil y motocicleta actualizados con éxito", Toast.LENGTH_SHORT).show()
                            onProfileSaved(updated)
                            onNavigateBack()
                        } else {
                            val code = res.getOrNull()?.statusCode ?: 0
                            Toast.makeText(context, "Guardado localmente (servidor: $code)", Toast.LENGTH_SHORT).show()
                            onProfileSaved(updated)
                            onNavigateBack()
                        }
                    }
                },
                enabled = !isSaving && aliasBiker.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE5B869),
                    contentColor = Color(0xFF101217)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color(0xFF101217),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Guardando...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Guardar Cambios", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun VisibilitySelector(
    title: String,
    selected: Visibility,
    onSelect: (Visibility) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Visibility.entries.forEach { option ->
                val isSelected = selected == option
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(option) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0xFF2D3548) else Color(0xFF181C26),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) Color(0xFFE5B869) else Color(0xFF2E3547)
                    )
                ) {
                    Text(
                        text = option.label,
                        color = if (isSelected) Color(0xFFE5B869) else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}
