package com.rutabikersync.app.feature.routes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.*

@Composable
fun OfflineMapsScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val progressMap by CorridorOfflineManager.downloadProgressMap.collectAsState()
    val customRegions by CorridorOfflineManager.customRegions.collectAsState()

    var storageUsedMb by remember { mutableStateOf(CorridorOfflineManager.getTotalStorageUsedMb(context)) }
    val storageLimitMb = CorridorOfflineManager.getStorageLimitMb()
    val storagePercent = ((storageUsedMb.toFloat() / storageLimitMb.toFloat().coerceAtLeast(1f)) * 100f).coerceIn(0f, 100f)

    var regionToDelete by remember { mutableStateOf<OfflineMapRegion?>(null) }
    var quotaErrorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshStorage() {
        storageUsedMb = CorridorOfflineManager.getTotalStorageUsedMb(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSlate950)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Barra superior
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkCardElevated)
                            .border(1.dp, DarkBorderSubtle, RoundedCornerShape(12.dp))
                            .clickable { onNavigateBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("←", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "MAPAS OFFLINE",
                            color = HeritageBrassGold,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Cartografía vectorial sin internet",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(EmeraldMesh.copy(alpha = 0.15f))
                        .border(1.dp, EmeraldMesh.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "PMTiles v3",
                        color = EmeraldMesh,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Tarjeta de resumen de almacenamiento (Límite 2 GB)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkCardElevated)
                    .border(1.2.dp, DarkBorderSubtle, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ALMACENAMIENTO OFFLINE",
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "$storageUsedMb MB / ${storageLimitMb.toInt()} MB",
                            color = if (storagePercent > 90f) RescueRed else HeritageBrassGold,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { storagePercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = if (storagePercent > 90f) RescueRed else EmeraldMesh,
                        trackColor = DarkCardBg
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Límite de seguridad: 2 GB. Las teselas descargadas permiten navegación continua en curvas y sierras sin señal.",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Lista dinámica de regiones reales del usuario
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (customRegions.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = DarkCardBg,
                            border = BorderStroke(1.dp, DarkBorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "🗺️",
                                    fontSize = 40.sp,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                Text(
                                    text = "Sin mapas o corredores guardados",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = TextWhite,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Traza una ruta en el mapa o programa una rodada para habilitar la descarga de su cartografía offline.",
                                    fontSize = 13.sp,
                                    color = TextMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "MIS CORREDORES Y RUTAS OFFLINE",
                            color = HeritageBrassGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(customRegions) { region ->
                        OfflineRegionCard(
                            region = region,
                            progress = progressMap[region.id],
                            isDownloaded = CorridorOfflineManager.isRegionDownloaded(context, region.id),
                            onDelete = { regionToDelete = region },
                            onDownload = {
                                if (!CorridorOfflineManager.canDownloadRegion(context, region.estimatedSizeMb)) {
                                    quotaErrorMessage = CorridorOfflineManager.QUOTA_EXCEEDED_MESSAGE
                                } else {
                                    CorridorOfflineManager.startDownloadRegion(context, region) {
                                        refreshStorage()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // Diálogo de confirmación para eliminar
        regionToDelete?.let { reg ->
            AlertDialog(
                onDismissRequest = { regionToDelete = null },
                containerColor = DarkCardElevated,
                shape = RoundedCornerShape(18.dp),
                title = {
                    Text(
                        text = "🗑️ ¿Eliminar mapa offline?",
                        color = TextWhite,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "¿Deseas eliminar las teselas del corredor ${reg.name}? Se liberarán ${reg.estimatedSizeMb} MB de almacenamiento interno.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            CorridorOfflineManager.deleteRegion(context, reg.id)
                            refreshStorage()
                            regionToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RescueRed),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("SÍ, ELIMINAR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { regionToDelete = null }) {
                        Text("CANCELAR", color = TextMuted, fontSize = 12.sp)
                    }
                }
            )
        }

        // Diálogo de error por cuota de 2 GB
        quotaErrorMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { quotaErrorMessage = null },
                containerColor = DarkCardElevated,
                shape = RoundedCornerShape(18.dp),
                title = {
                    Text(
                        text = "⚠️ Límite de Almacenamiento",
                        color = HeritageBrassGold,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = msg,
                        color = TextMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { quotaErrorMessage = null },
                        colors = ButtonDefaults.buttonColors(containerColor = HeritageBrassGold),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("ENTENDIDO", color = DarkSlate950, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            )
        }
    }
}

@Composable
private fun OfflineRegionCard(
    region: OfflineMapRegion,
    progress: CorridorDownloadProgress?,
    isDownloaded: Boolean,
    onDelete: () -> Unit,
    onDownload: () -> Unit
) {
    val isDownloading = progress?.state == CorridorDownloadState.DOWNLOADING
    val downloadPercent = progress?.progressPercent ?: 0
    val downloadedMb = progress?.downloadedMb ?: 0.0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkCardElevated)
            .border(
                width = if (isDownloaded) 1.5.dp else 1.dp,
                color = if (isDownloaded) EmeraldMesh.copy(alpha = 0.6f) else DarkBorderSubtle,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = region.name,
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = region.description,
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isDownloaded -> EmeraldMesh.copy(alpha = 0.2f)
                                isDownloading -> HeritageBrassGold.copy(alpha = 0.2f)
                                else -> DarkCardBg
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = when {
                            isDownloaded -> "LISTO ✅"
                            isDownloading -> "$downloadPercent%"
                            else -> "~${region.estimatedSizeMb} MB"
                        },
                        color = when {
                            isDownloaded -> EmeraldMesh
                            isDownloading -> HeritageBrassGold
                            else -> TextTertiary
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { downloadPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = EmeraldMesh,
                    trackColor = DarkCardBg
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Descargando teselas: $downloadPercent% ($downloadedMb MB de ${region.estimatedSizeMb} MB)",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Botón de acción táctil de alta ergonomía
            if (isDownloaded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🛡️", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Mapa descargado en dispositivo",
                            color = EmeraldMesh,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = RescueRed.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.border(1.dp, RescueRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Text("🗑️ ELIMINAR", color = RescueRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Button(
                    onClick = onDownload,
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HeritageBrassGold,
                        disabledContainerColor = DarkCardBg
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = if (isDownloading) "DESCARGANDO TESELAS..." else "📥 DESCARGAR (${region.estimatedSizeMb} MB)",
                        color = if (isDownloading) TextTertiary else DarkSlate950,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }
    }
}
