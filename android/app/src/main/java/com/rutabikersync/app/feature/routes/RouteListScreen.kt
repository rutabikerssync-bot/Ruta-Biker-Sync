package com.rutabikersync.app.feature.routes

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import com.rutabikersync.app.ui.components.HeritageBevelCard
import com.rutabikersync.app.ui.components.HeritageBrassButton
import com.rutabikersync.app.ui.theme.HeritageTheme
import com.rutabikersync.app.ui.theme.*

/**
 * Pantalla E1: Catálogo y Lista de Rutas Oficiales
 * Muestra las rutas guardadas, su tamaño estimado en disco, si están descargadas offline
 * para navegación sin señal y permite importar archivos GPX externos o planificar con BRouter.
 */
@Composable
fun RouteListScreen(
    onDismiss: () -> Unit,
    onPlanNewRouteClick: () -> Unit,
    onRouteSelected: (RouteAlternative) -> Unit
) {
    val context = LocalContext.current
    var routesList by remember {
        mutableStateOf(emptyList<RouteAlternative>())
    }

    var selectedRouteForDetail by remember { mutableStateOf<RouteAlternative?>(null) }
    var selectedRouteForDownload by remember { mutableStateOf<RouteAlternative?>(null) }

    // Launcher para importar archivos GPX locales
    val gpxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val importedRoute = GpxManager.parseGpxStream(inputStream, "Ruta GPX Importada")
                    routesList = listOf(importedRoute) + routesList
                    Toast.makeText(context, "Ruta GPX importada: ${importedRoute.name} (${importedRoute.distanceKm} km)", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                Toast.makeText(context, "Error al importar archivo GPX", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HeritageTheme.colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))


            // 1. Barra de Encabezado Superior
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(HeritageCard)
                        .border(1.dp, HeritageBorder, CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Atrás",
                        tint = HeritageLeatherBrown
                    )
                }

                Text(
                    text = "Rutas y Corredores Offline",
                    color = HeritageLeatherBrown,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )

                // Botón importar GPX
                IconButton(
                    onClick = { gpxPickerLauncher.launch("*/*") },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(HeritageCardWarm)
                        .border(1.dp, HeritageBorder, CircleShape)
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = "Importar GPX",
                        tint = HeritageCopper,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Banner de Acciones Rápidas (Trazar con BRouter / Importar GPX)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPlanNewRouteClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HeritageCopper,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Route, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Trazar Ruta", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = { gpxPickerLauncher.launch("*/*") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HeritageCardWarm,
                        contentColor = HeritageLeatherBrown
                    ),
                    border = BorderStroke(1.dp, HeritageBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = HeritageCopper,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Importar GPX",
                        color = HeritageLeatherBrown,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Lista de Rutas Disponibles o Estado Vacío
            if (routesList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    HeritageBevelCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Route,
                                contentDescription = null,
                                tint = HeritageCopper,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "Sin Rutas Offline Guardadas",
                                color = HeritageLeatherBrown,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "No tienes rutas offline guardadas. Traza una ruta o importa un archivo GPX para comenzar.",
                                color = HeritageLeatherMuted,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(routesList) { routeItem ->
                        val isDownloaded = CorridorOfflineManager.isCorridorDownloaded(context, routeItem.id)
                        val estimatedMb = CorridorOfflineManager.estimateCorridorSizeMb(routeItem.distanceKm, 10)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRouteForDetail = routeItem },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = HeritageCard
                            ),
                            border = BorderStroke(1.dp, HeritageBorder),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = routeItem.name,
                                        color = HeritageLeatherBrown,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Badge de estado offline
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isDownloaded) HeritageGreenLight else HeritageCardWarm
                                            )
                                            .border(
                                                1.dp,
                                                if (isDownloaded) HeritageGreen.copy(alpha = 0.5f) else HeritageBorder,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isDownloaded) "✓ Offline Listo" else "📡 $estimatedMb MB",
                                            color = if (isDownloaded) HeritageGreen else HeritageLeatherMuted,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                Text(
                                    text = routeItem.subtitle,
                                    color = HeritageLeatherMuted,
                                    fontSize = 12.sp,
                                    maxLines = 2
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "⚡ ${routeItem.distanceKm} km",
                                            color = HeritageCopperDark,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            "⏱️ ~${routeItem.estimatedMinutes} min",
                                            color = HeritageLeatherMuted,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        IconButton(
                                            onClick = { selectedRouteForDownload = routeItem },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.CloudDownload,
                                                contentDescription = "Descargar Corredor",
                                                tint = HeritageCopper,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                selectedRouteForDetail = routeItem
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = HeritageCardWarm,
                                                contentColor = HeritageLeatherBrown
                                            ),
                                            border = BorderStroke(1.dp, HeritageBorder),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                "Ver Detalle",
                                                color = HeritageLeatherBrown,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Modal de detalle de ruta
        selectedRouteForDetail?.let { route ->
            RouteDetailScreen(
                route = route,
                onDismiss = { selectedRouteForDetail = null },
                onActivateInRide = { activatedRoute ->
                    onRouteSelected(activatedRoute)
                    selectedRouteForDetail = null
                    onDismiss()
                }
            )
        }

        // Modal de descarga de corredor
        selectedRouteForDownload?.let { route ->
            CorridorDownloadDialog(
                route = route,
                onDismiss = { selectedRouteForDownload = null }
            )
        }
    }
}
