package com.rutabikersync.app.feature.profile

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.feature.auth.UserProfile
import com.rutabikersync.app.feature.profile.garage.CoolingType

@Composable
fun MotorcycleProfileSection(
    profile: UserProfile,
    motoBitmap: ImageBitmap?,
    coolingType: CoolingType,
    odometerKm: Int,
    onCoolingTypeChange: (CoolingType) -> Unit,
    onEditProfile: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF161922).copy(alpha = 0.84f),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🏍️ Perfil de Motocicleta",
                        color = Color(0xFFE5B869),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.78f),
                        border = BorderStroke(1.dp, Color(0xFF475569))
                    ) {
                        Text(
                            text = profile.motorcycleVisibility.label,
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                if (motoBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF3B475C), RoundedCornerShape(12.dp))
                    ) {
                        Image(
                            bitmap = motoBitmap,
                            contentDescription = "Foto de la motocicleta",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(68.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color(0xE60B0F17))
                                    )
                                )
                        )
                        Text(
                            text = "${profile.motorcycleBrand} ${profile.motorcycleModel}".trim()
                                .ifBlank { "Mi Motocicleta" },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clickable { onEditProfile() },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Toca \"Editar Perfil\" para subir la foto de tu moto",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MotoDataField("Marca / Modelo", "${profile.motorcycleBrand} ${profile.motorcycleModel}".trim().ifBlank { "BMW R1250GS" })
                    MotoDataField("Cilindrada", if (profile.motorcycleDisplacement > 0) "${profile.motorcycleDisplacement} cc" else "—")
                    MotoDataField("Año", if (profile.motorcycleYear > 0) "${profile.motorcycleYear}" else "—")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MotoDataField("Color", profile.motorcycleColor.ifBlank { "—" })
                    MotoDataField("Serial / VIN", profile.motorcycleSerial.ifBlank { "—" })
                    MotoDataField("Odómetro", "$odometerKm km")
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Sistema de Refrigeración (controla módulo Refrigerante en Garage)",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CoolingType.entries.forEach { option ->
                            val selected = option == coolingType
                            Surface(
                                onClick = { onCoolingTypeChange(option) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) Color(0xFFE5B869).copy(alpha = 0.20f) else Color(0xFF0F172A).copy(alpha = 0.72f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) Color(0xFFE5B869) else Color(0xFF334155)
                                )
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = option.label,
                                        color = if (selected) Color(0xFFE5B869) else Color(0xFFCBD5E1),
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = if (coolingType == CoolingType.LIQUID) {
                            "✅ Módulo «Líquido Refrigerante» visible en Garage → Fluidos"
                        } else {
                            "ℹ️ Módulo «Líquido Refrigerante» oculto en Garage (motor Aire / Aceite)"
                        },
                        color = if (coolingType == CoolingType.LIQUID) Color(0xFF34D399) else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Surface(
            onClick = onEditProfile,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1F2630).copy(alpha = 0.86f),
            border = BorderStroke(1.2.dp, Color(0xFFD4AF37))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = Color(0xFFD4AF37),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Editar Perfil (Moto y Pin)",
                    color = Color(0xFFD4AF37),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MotoDataField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 11.sp)
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
