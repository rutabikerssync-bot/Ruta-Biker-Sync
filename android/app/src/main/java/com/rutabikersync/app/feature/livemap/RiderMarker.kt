package com.rutabikersync.app.feature.livemap

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.theme.BikerColors
import com.rutabikersync.app.ui.theme.BikerDimens

/**
 * Relación social entre el usuario local y otro rider de la rodada (RULES R4.7 y R4.8).
 * - `SAME_GROUP` o `MUTUAL_FRIEND`: permite ver nombre/alias real y avatar personalizado.
 * - `ALLIED_STRANGER`: miembro de un grupo aliado sin amistad mutua; muestra avatar genérico e ID corto (`R#X`).
 */
enum class RiderRelation {
    SAME_GROUP,
    MUTUAL_FRIEND,
    ALLIED_STRANGER
}

/**
 * Rol operativo dentro de la rodada activa (ICONS.md §5).
 */
enum class RiderRideRole(val badgeLabel: String) {
    NONE(""),
    LEADER("Líder"),
    CAPTAIN("Capitán"),
    SCOUT("Scout"),
    SWEEP("Sweep")
}

/**
 * Estado del rider en el mapa en vivo (ICONS.md §1 Regla 2 y §5: color nunca es la única señal;
 * cada estado incluye símbolo/icono + texto).
 */
enum class RiderRouteStatus(val symbolAndLabel: String) {
    ON_ROUTE("✓ En ruta"),
    OFF_ROUTE_DEVIATED("⚠ Desviado"),
    OFF_MESH_LOST("✕ Sin señal"),
    SOS_ACTIVE("SOS ACTIVO")
}

/**
 * Estado visual e interpolado de un rider sobre el mapa en vivo (C1).
 */
data class RiderMarkerUiModel(
    val shortId: UShort,
    val fullName: String,
    val nickname: String?,
    val relation: RiderRelation,
    val rideRole: RiderRideRole,
    val routeStatus: RiderRouteStatus,
    val markerColorHex: Long,
    val prevLatitude: Double,
    val prevLongitude: Double,
    val targetLatitude: Double,
    val targetLongitude: Double,
    val displayLatitude: Double,
    val displayLongitude: Double,
    val speedKmh: Int,
    val bearingDegrees: Float,
    val batteryPercent: Int,
    val distanceToLeaderMeters: Int,
    val lastHops: Int,
    val isSelf: Boolean = false,
    val avatarUrl: String? = null,
    val avatarBase64: String? = null,
    val roleLabel: String = "Desconocido"
) {
    /**
     * Decisión 3.2 (C): Nombre abreviado siempre visible junto al avatar (ej. "J.M."),
     * respetando R4.7/R4.8 para riders desconocidos en rodadas aliadas ("R#5").
     */
    val abbreviatedLabel: String
        get() = abbreviateRiderName(fullName = fullName, relation = relation, shortId = shortId)

    /**
     * Nombre completo mostrado al tocar el marcador (expandido), respetando privacidad R4.7/R4.8.
     */
    val expandedDisplayName: String
        get() = when (relation) {
            RiderRelation.SAME_GROUP, RiderRelation.MUTUAL_FRIEND -> {
                if (!nickname.isNullOrBlank()) "$fullName ($nickname)" else fullName
            }
            RiderRelation.ALLIED_STRANGER -> "Piloto #${shortId.toInt()}"
        }
}

/**
 * Convierte un nombre completo en iniciales abreviadas siempre visibles ("Juan Martínez" -> "J.M.").
 * Si la relación es `ALLIED_STRANGER` (R4.7/R4.8), devuelve un identificador genérico `"R#<shortId>"`.
 */
fun abbreviateRiderName(
    fullName: String,
    relation: RiderRelation,
    shortId: UShort
): String {
    if (relation == RiderRelation.ALLIED_STRANGER) {
        return "R#${shortId.toInt()}"
    }
    val parts = fullName.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "R#${shortId.toInt()}"
    if (parts.size == 1) {
        return "${parts[0].first().uppercaseChar()}."
    }
    val firstInitial = parts.first().first().uppercaseChar()
    val secondInitial = parts[1].first().uppercaseChar()
    return "$firstInitial.$secondInitial."
}

/**
 * Componente visual `RiderMarker` (ICONS.md §5 y SCREENS.md C1):
 * - Avatar circular de 48 dp con borde del color del rider (o gris si está fuera de la mesh, o anillo rojo si SOS).
 * - Etiqueta abreviada siempre visible (`abbreviatedLabel`, ej. "J.M.") + insignia de estado con icono y texto.
 * - Al tocar (`onClick`), expande el detalle con el nombre completo, velocidad, batería y distancia al líder.
 */
@Composable
fun RiderMarker(
    marker: RiderMarkerUiModel,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ringColor = when (marker.routeStatus) {
        RiderRouteStatus.SOS_ACTIVE -> BikerColors.StatusRed
        RiderRouteStatus.OFF_MESH_LOST -> BikerColors.TextSecondary
        RiderRouteStatus.OFF_ROUTE_DEVIATED -> BikerColors.StatusYellow
        RiderRouteStatus.ON_ROUTE -> Color(marker.markerColorHex)
    }

    // Foto real del perfil cacheado (base64). Si no hay foto, se muestran iniciales.
    val avatarBitmap = remember(marker.avatarBase64) {
        val raw = marker.avatarBase64 ?: return@remember null
        runCatching {
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    val avatarFill = if (marker.routeStatus == RiderRouteStatus.OFF_MESH_LOST) {
        Color(0xFF2C313A)
    } else {
        BikerColors.SurfaceDark
    }

    val a11yDescription = buildString {
        append(marker.abbreviatedLabel)
        append(", ")
        append(marker.routeStatus.symbolAndLabel)
        if (marker.rideRole != RiderRideRole.NONE) {
            append(", rol ")
            append(marker.rideRole.badgeLabel)
        }
        append(", rol en convoy ")
        append(marker.roleLabel)
        append(", ${marker.speedKmh} km/h")
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .semantics { contentDescription = a11yDescription }
            .clickable(onClick = onClick)
    ) {
        // Si está seleccionado (al tocar), muestra tarjeta con nombre completo, velocidad y distancia al líder
        if (isSelected) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(BikerColors.SurfaceDark, RoundedCornerShape(8.dp))
                    .border(1.dp, ringColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = marker.expandedDisplayName,
                    color = BikerColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Rol: ${marker.roleLabel}",
                    color = BikerColors.PrimaryAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${marker.speedKmh} km/h · Bat ${marker.batteryPercent}% · Líder a ${marker.distanceToLeaderMeters} m",
                    color = BikerColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // Contenedor del avatar circular 48 dp + anillo SOS + insignia de rol
        Box(contentAlignment = Alignment.Center) {
            if (marker.routeStatus == RiderRouteStatus.SOS_ACTIVE) {
                Box(
                    modifier = Modifier
                        .size(BikerDimens.AvatarListSize + 12.dp)
                        .clip(CircleShape)
                        .border(3.dp, BikerColors.StatusRed, CircleShape)
                        .background(BikerColors.StatusRed.copy(alpha = 0.22f))
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(BikerDimens.AvatarListSize)
                    .clip(CircleShape)
                    .background(avatarFill)
                    .border(3.dp, ringColor, CircleShape)
            ) {
                when {
                    marker.routeStatus == RiderRouteStatus.SOS_ACTIVE -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = AppIcons.SosAlert,
                                contentDescription = "SOS",
                                tint = BikerColors.StatusRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "SOS",
                                color = BikerColors.StatusRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                    marker.routeStatus == RiderRouteStatus.OFF_MESH_LOST -> {
                        Icon(
                            imageVector = AppIcons.RiderOffMesh,
                            contentDescription = "Fuera de mesh",
                            tint = BikerColors.TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    else -> {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap,
                                contentDescription = "Avatar de ${marker.fullName}",
                                modifier = Modifier
                                    .size(BikerDimens.AvatarListSize - 6.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = marker.abbreviatedLabel,
                                color = BikerColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // Insignia superior derecha para el rol de rodada (Líder, Capitán, Scout, Sweep)
            if (marker.rideRole != RiderRideRole.NONE) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(BikerColors.BackgroundDark)
                        .border(1.dp, BikerColors.PrimaryAmber, CircleShape)
                ) {
                    when (marker.rideRole) {
                        RiderRideRole.LEADER -> Icon(
                            painter = painterResource(id = AppIcons.LeaderCrownShieldRes),
                            contentDescription = "Líder",
                            tint = BikerColors.PrimaryAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        RiderRideRole.CAPTAIN -> Icon(
                            imageVector = AppIcons.RoleCaptain,
                            contentDescription = "Capitán",
                            tint = BikerColors.PrimaryAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        RiderRideRole.SCOUT -> Icon(
                            imageVector = AppIcons.RoleScout,
                            contentDescription = "Scout",
                            tint = BikerColors.PrimaryAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        RiderRideRole.SWEEP -> Icon(
                            imageVector = AppIcons.RoleSweep,
                            contentDescription = "Sweep",
                            tint = BikerColors.PrimaryAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        RiderRideRole.NONE -> Unit
                    }
                }
            }
        }

        // Etiqueta inferior siempre visible: Nombre abreviado (C) + símbolo/texto de estado (ICONS.md §1 Regla 2)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .background(BikerColors.BackgroundDark.copy(alpha = 0.90f), RoundedCornerShape(6.dp))
                .border(1.dp, ringColor.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            val statusIcon = when (marker.routeStatus) {
                RiderRouteStatus.ON_ROUTE -> AppIcons.PaymentGreen
                RiderRouteStatus.OFF_ROUTE_DEVIATED -> AppIcons.PaymentYellow
                RiderRouteStatus.OFF_MESH_LOST -> AppIcons.RiderOffMesh
                RiderRouteStatus.SOS_ACTIVE -> AppIcons.SosAlert
            }
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = ringColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = buildString {
                    append(marker.abbreviatedLabel)
                    if (marker.isSelf) append(" (Yo)")
                    append(" · ")
                    append(marker.roleLabel)
                    append(" · ")
                    append(marker.routeStatus.symbolAndLabel)
                    append('\n')
                    append(marker.fullName)
                },
                color = BikerColors.TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
        }
    }
}
