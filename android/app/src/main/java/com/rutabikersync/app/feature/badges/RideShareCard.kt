package com.rutabikersync.app.feature.badges

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2. TARJETA DE RODADA COMPARTIBLE (GAMIFICATION.md, SCREENS.md).
 * Formato póster para redes sociales con estadísticas de convoy, rol y XP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideShareCardScreen(
    rideTitle: String = "Ruta de los Volcanes & Cañón del Silencio",
    riderName: String = "Ozmar 'Shadow' Rider",
    clubName: String = "Águilas del Asfalto MG",
    distanceKm: Double = 148.5,
    durationText: String = "2h 45m",
    avgSpeedKmh: Double = 54.0,
    elevationGainM: Int = 820,
    roleTitle: String = "Capitán de Ruta",
    xpEarned: Int = 28,
    featuredBadgeRes: Int = R.drawable.ic_achieve_aventurero,
    featuredBadgeName: String = "Aventurero del Cañón",
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Tarjeta Oficial de Rodada",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 17.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F1118))
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF131722),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFFE5B869).copy(alpha = 0.5f), Color(0xFF1F2436))
                    ),
                    width = 1.dp
                )
            ) {
                Button(
                    onClick = {
                        shareRideSummary(
                            context = context,
                            rideTitle = rideTitle,
                            riderName = riderName,
                            clubName = clubName,
                            distanceKm = distanceKm,
                            durationText = durationText,
                            xpEarned = xpEarned,
                            roleTitle = roleTitle
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869))
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = Color(0xFF0B0E14),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Compartir en Redes Sociales",
                        color = Color(0xFF0B0E14),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        },
        containerColor = Color(0xFF0B0E14)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TARJETA PÓSTER (Visual Share Card)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFFE5B869), Color(0xFF38BDF8), Color(0xFF1E2436))
                    ),
                    width = 1.5.dp
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF181D2D), Color(0xFF0F121C))
                            )
                        )
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Marca y Fecha
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_logo_ruta_biker_sync),
                                contentDescription = "Logo",
                                modifier = Modifier.size(26.dp)
                            )
                            Text(
                                text = "RUTA BIKER SYNC",
                                color = Color(0xFFE5B869),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                letterSpacing = 1.sp
                            )
                        }

                        val dateFormatted = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
                        Text(
                            text = dateFormatted,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // 2. Avatar / Escudo y Datos del Rider
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22283A))
                            .border(2.dp, Color(0xFFE5B869), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = featuredBadgeRes),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = riderName,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp,
                        textAlign = TextAlign.Center
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF1E2538),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = "$clubName · $roleTitle",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Titulo de la rodada
                    Text(
                        text = "« $rideTitle »",
                        color = Color(0xFFCBD5E1),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 3. Grid de Estadísticas de la Rodada
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatBox(modifier = Modifier.weight(1f), label = "Distancia", value = "$distanceKm km", icon = "🛣️")
                        StatBox(modifier = Modifier.weight(1f), label = "Tiempo", value = durationText, icon = "⏱️")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatBox(modifier = Modifier.weight(1f), label = "Vel. Media", value = "$avgSpeedKmh km/h", icon = "⚡")
                        StatBox(modifier = Modifier.weight(1f), label = "Desnivel", value = "+$elevationGainM m", icon = "⛰️")
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // 4. Condecoración y Mérito Obtenido
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1B2030),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = Brush.horizontalGradient(
                                listOf(Color(0xFF10B981).copy(alpha = 0.6f), Color(0xFF202638))
                            ),
                            width = 1.dp
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(text = "🏆", fontSize = 24.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Logro de Rodada: $featuredBadgeName",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "+$xpEarned XP acumulados para ascenso de rango",
                                    color = Color(0xFF10B981),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Footer del póster
                    Text(
                        text = "Conexión de convoy respaldada por Mesh BLE Offline · 100% Cifrado",
                        color = Color(0xFF64748B),
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F2E)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF2B3248), Color(0xFF151926))
            ),
            width = 1.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp
            )
            Text(
                text = label,
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun shareRideSummary(
    context: Context,
    rideTitle: String,
    riderName: String,
    clubName: String,
    distanceKm: Double,
    durationText: String,
    xpEarned: Int,
    roleTitle: String
) {
    val shareText = """
        🏍️ ¡Rodada Oficial Concluida con Éxito!
        
        Ruta: $rideTitle
        Rider: $riderName ($clubName)
        Rol de Convoy: $roleTitle
        
        📊 Estadísticas:
        • Distancia: $distanceKm km
        • Tiempo en carretera: $durationText
        • Mérito ganado: +$xpEarned XP de trayectoria
        
        Respaldado por Ruta Biker Sync: Navegación, Mesh BLE y Seguridad Biker.
        #RutaBikerSync #HermandadBiker #MotorcycleClub #RutaBiker
    """.trimIndent()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Rodada Biker: $rideTitle")
        putExtra(Intent.EXTRA_TEXT, shareText)
    }

    context.startActivity(Intent.createChooser(intent, "Compartir resumen de rodada"))
}
