package com.rutabikersync.app.feature.routes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rutabikersync.app.ui.theme.HeritageSand
import com.rutabikersync.app.ui.theme.HeritageCard
import com.rutabikersync.app.ui.theme.HeritageCardWarm
import com.rutabikersync.app.ui.theme.HeritageBorder
import com.rutabikersync.app.ui.theme.HeritageLeatherBrown
import com.rutabikersync.app.ui.theme.HeritageLeatherMuted
import com.rutabikersync.app.ui.theme.HeritageCopper
import com.rutabikersync.app.ui.theme.HeritageCopperDark
import com.rutabikersync.app.ui.theme.HeritageGreen
import com.rutabikersync.app.ui.theme.HeritageRustRed

/**
 * Pantalla E3: Modal de Descarga de Corredor Offline (PMTiles / MBTiles)
 * Permite seleccionar el ancho del buffer de cobertura (±5 km, ±10 km, ±15 km),
 * ver el tamaño estimado en disco, pausar/reanudar y eliminar teselas locales.
 */
@Composable
fun CorridorDownloadDialog(
    route: RouteAlternative,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val progressMap by CorridorOfflineManager.downloadProgressMap.collectAsState()
    val routeProgress = progressMap[route.id] ?: CorridorOfflineManager.getProgressFor(route.id, route.name, route.distanceKm)

    var selectedBufferKm by remember { mutableIntStateOf(routeProgress.bufferKm) }
    val estimatedMb = remember(selectedBufferKm, route.distanceKm) {
        CorridorOfflineManager.estimateCorridorSizeMb(route.distanceKm, selectedBufferKm)
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(20.dp))
                .background(HeritageSand)
                .border(1.dp, HeritageBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Encabezado
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(HeritageCardWarm)
                                .border(1.dp, HeritageCopper, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = HeritageCopper, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text("Mapa del Corredor Offline", color = HeritageLeatherBrown, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Navegación 100% sin señal celular", color = HeritageLeatherMuted, fontSize = 11.sp)
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = HeritageLeatherMuted)
                    }
                }

                // Resumen de la ruta
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HeritageCard)
                        .border(1.dp, HeritageBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(route.name, color = HeritageLeatherBrown, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            text = "Distancia: ${route.distanceKm} km · ${route.waypoints.size} waypoints trazados",
                            color = HeritageCopperDark,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Selector de ancho del buffer de corredor
                Text("Ancho de Cobertura Lateral del Corredor:", color = HeritageLeatherBrown, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 10, 15).forEach { buffer ->
                        val isSelected = selectedBufferKm == buffer
                        val estForBuffer = CorridorOfflineManager.estimateCorridorSizeMb(route.distanceKm, buffer)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) HeritageCardWarm else HeritageCard)
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) HeritageCopper else HeritageBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable(enabled = routeProgress.state != CorridorDownloadState.DOWNLOADING) {
                                    selectedBufferKm = buffer
                                }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "±$buffer km",
                                    color = if (isSelected) HeritageCopperDark else HeritageLeatherBrown,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text("~$estForBuffer MB", color = HeritageLeatherMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }

                // Barra de progreso y estado
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HeritageCard)
                        .border(1.dp, HeritageBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (routeProgress.state) {
                                CorridorDownloadState.NOT_DOWNLOADED -> "No descargado en este dispositivo"
                                CorridorDownloadState.CALCULATING -> "Calculando teselas vectoriales..."
                                CorridorDownloadState.DOWNLOADING -> "Descargando corredor (${routeProgress.progressPercent}%)"
                                CorridorDownloadState.PAUSED -> "Descarga en pausa"
                                CorridorDownloadState.COMPLETED -> "✓ Listo para uso offline"
                                CorridorDownloadState.ERROR -> "Error de descarga"
                            },
                            color = when (routeProgress.state) {
                                CorridorDownloadState.COMPLETED -> HeritageGreen
                                CorridorDownloadState.DOWNLOADING -> HeritageCopperDark
                                CorridorDownloadState.PAUSED -> HeritageCopper
                                CorridorDownloadState.ERROR -> HeritageRustRed
                                else -> HeritageLeatherMuted
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )

                        Text(
                            text = if (routeProgress.state == CorridorDownloadState.COMPLETED) {
                                "${routeProgress.downloadedMb} MB"
                            } else {
                                "${routeProgress.downloadedMb} / $estimatedMb MB"
                            },
                            color = HeritageCopperDark,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    LinearProgressIndicator(
                        progress = { routeProgress.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = when (routeProgress.state) {
                            CorridorDownloadState.COMPLETED -> HeritageGreen
                            CorridorDownloadState.PAUSED -> HeritageCopper
                            else -> HeritageCopper
                        },
                        trackColor = HeritageCardWarm
                    )

                    Text(
                        text = "Formato PMTiles compatible con MapLibre. Se descargan zooms 0 a 15 a lo largo de la polilínea.",
                        color = HeritageLeatherMuted,
                        fontSize = 10.sp
                    )
                }

                // Botones de acción
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (routeProgress.state) {
                        CorridorDownloadState.NOT_DOWNLOADED, CorridorDownloadState.ERROR -> {
                            Button(
                                onClick = {
                                    if (route.waypoints.isNotEmpty()) {
                                        CorridorOfflineManager.registerCustomRoute(
                                            routeId = route.id,
                                            routeName = route.name,
                                            distanceKm = route.distanceKm,
                                            waypoints = route.waypoints,
                                            bufferKm = selectedBufferKm,
                                            context = context
                                        )
                                    }
                                    CorridorOfflineManager.startOrResumeDownload(
                                        context = context,
                                        routeId = route.id,
                                        routeName = route.name,
                                        distanceKm = route.distanceKm,
                                        bufferKm = selectedBufferKm
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Descargar Corredor ($estimatedMb MB)", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        CorridorDownloadState.DOWNLOADING -> {
                            Button(
                                onClick = { CorridorOfflineManager.pauseDownload(route.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = HeritageCardWarm),
                                border = BorderStroke(1.dp, HeritageCopper),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null, tint = HeritageLeatherBrown)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pausar Descarga", color = HeritageLeatherBrown, fontWeight = FontWeight.Bold)
                            }
                        }
                        CorridorDownloadState.PAUSED -> {
                            Button(
                                onClick = {
                                    CorridorOfflineManager.startOrResumeDownload(
                                        context = context,
                                        routeId = route.id,
                                        routeName = route.name,
                                        distanceKm = route.distanceKm,
                                        bufferKm = selectedBufferKm
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reanudar", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { CorridorOfflineManager.deleteCorridor(context, route.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = HeritageCardWarm),
                                border = BorderStroke(1.dp, HeritageBorder),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = HeritageRustRed)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cancelar", color = HeritageRustRed, fontWeight = FontWeight.Bold)
                            }
                        }
                        CorridorDownloadState.COMPLETED -> {
                            Button(
                                onClick = { CorridorOfflineManager.deleteCorridor(context, route.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = HeritageCardWarm),
                                border = BorderStroke(1.dp, HeritageBorder),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = HeritageRustRed)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Liberar Espacio", color = HeritageRustRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cerrar", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
