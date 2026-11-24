package com.rutabikersync.app.feature.livemap

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
    STALE_GRACE("⚠ Sin señal temporal"),
    OFF_MESH_LOST("✕ Fuera de la malla"),
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
    /** `null` cuando no hay líder válido o no hay posión fiable: la UI oculta el campo. */
    val distanceToLeaderMeters: Int?,
    val lastHops: Int,
    val isSelf: Boolean = false,
    val avatarUrl: String? = null,
    val avatarBase64: String? = null,
    val roleLabel: String = "Desconocido",
    /** groupId real del rider; `null` cuando no se conoce. */
    val groupId: String? = null,
    /** true cuando el perfil real se oculta por club distinto o desconocido. */
    val profileRedacted: Boolean = false,
    /**
     * Edad en segundos de una posición conservada porque el peer dejó de enviar un fix
     * válido (centinela 0,0). `null` cuando la posición es actual: no se muestra el aviso.
     */
    val positionAgeSeconds: Long? = null
) {
    /**
     * Decisión 3.2 (C): Nombre abreviado siempre visible junto al avatar (ej. "J.M."),
     * respetando R4.7/R4.8 para riders desconocidos en rodadas aliadas ("R#5").
     */
    val abbreviatedLabel: String
        get() = PeerProfilePrivacy.visibleAbbreviation(
            shortId = shortId,
            realAbbreviation = abbreviateRiderName(fullName = fullName, relation = relation, shortId = shortId),
            showRealProfile = !profileRedacted
        )

    /**
     * Nombre completo mostrado al tocar el marcador (expandido), respetando privacidad R4.7/R4.8.
     */
    val expandedDisplayName: String
        get() = if (profileRedacted) {
            "Piloto #${shortId.toInt()}"
        } else {
            when (relation) {
                RiderRelation.SAME_GROUP, RiderRelation.MUTUAL_FRIEND -> {
                    if (!nickname.isNullOrBlank()) "$fullName ($nickname)" else fullName
                }
                RiderRelation.ALLIED_STRANGER -> "Piloto #${shortId.toInt()}"
            }
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
    modifier: Modifier = Modifier,
    headingDegrees: Float? = null,
    /** Texto ya validado por [LeaderDistanceFormatter]; `null` oculta el campo. */
    leaderDistanceText: String? = null
) {
    val ringColor = when (marker.routeStatus) {
        RiderRouteStatus.SOS_ACTIVE -> BikerColors.StatusRed
        RiderRouteStatus.OFF_MESH_LOST -> BikerColors.TextSecondary
        RiderRouteStatus.STALE_GRACE -> BikerColors.StatusYellow
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

    // Detalle bajo demanda: selección (toque) u hover (ratón). Por defecto sólo el avatar.
    var hovered by remember { mutableStateOf(false) }
    val showDetail = RiderDetailVisibility.shouldShow(isSelected = isSelected, isHovered = hovered)

    val avatarFill = if (marker.routeStatus == RiderRouteStatus.OFF_MESH_LOST) {
        Color(0xFF2C313A)
    } else {
        BikerColors.SurfaceDark
    }

    val a11yDescription = buildString {
        append(if (marker.profileRedacted) "Avatar genérico" else "Avatar de ${marker.fullName}")
        append(", ")
        append(marker.routeStatus.symbolAndLabel)
        if (showDetail) {
            if (marker.rideRole != RiderRideRole.NONE) {
                append(", rol ")
                append(marker.rideRole.badgeLabel)
            }
            append(", rol en convoy ")
            append(marker.roleLabel)
        }
        append(", ${marker.speedKmh} km/h")
        marker.positionAgeSeconds?.let { ageSeconds ->
            append(", posición de hace $ageSeconds segundos")
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .semantics { contentDescription = a11yDescription }
            .clickable(onClick = onClick)
    ) {
        // Detalle bajo demanda: al tocar el avatar o al pasar el ratón, con fade in/out.
        AnimatedVisibility(
            visible = showDetail,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(BikerColors.SurfaceDark, RoundedCornerShape(8.dp))
                    .border(1.dp, ringColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        marker.profileRedacted -> Icon(
                            imageVector = AppIcons.Bike,
                            contentDescription = "Avatar genérico",
                            tint = BikerColors.TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                        avatarBitmap != null -> Image(
                            bitmap = avatarBitmap,
                            contentDescription = "Avatar de ${marker.fullName}",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                        )
                        else -> Text(
                            text = marker.abbreviatedLabel,
                            color = BikerColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Column {
                        Text(
                            text = marker.expandedDisplayName,
                            color = BikerColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = buildString {
                                append("Rol: ").append(marker.roleLabel)
                                if (marker.rideRole != RiderRideRole.NONE) {
                                    append(" · ").append(marker.rideRole.badgeLabel)
                                }
                            },
                            color = BikerColors.PrimaryAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(marker.routeStatus.symbolAndLabel)
                        append(" · ${marker.speedKmh} km/h · Bat ${marker.batteryPercent}%")
                        marker.positionAgeSeconds?.let { ageSeconds ->
                            append(" · Posición de hace $ageSeconds s")
                        }
                        if (!leaderDistanceText.isNullOrBlank()) {
                            append(" · Líder a ").append(leaderDistanceText)
                        }
                    },
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
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                when (awaitPointerEvent().type) {
                                    PointerEventType.Enter -> hovered = true
                                    PointerEventType.Exit -> hovered = false
                                    else -> Unit
                                }
                            }
                        }
                    }
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
                    marker.routeStatus == RiderRouteStatus.STALE_GRACE -> {
                        Icon(
                            imageVector = AppIcons.RiderOffMesh,
                            contentDescription = "Sin señal temporal",
                            tint = BikerColors.StatusYellow,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    // FIX privacidad: club distinto/desconocido -> avatar genérico de moto.
                    marker.profileRedacted -> {
                        Icon(
                            imageVector = AppIcons.Bike,
                            contentDescription = "Avatar genérico",
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

            // El rol operativo se muestra únicamente dentro de la tarjeta de detalle.

            // Indicador de dirección: rumbo real del peer o, si no lo reporta,
            // rumbo del tramo de polilínea sobre el que se ha proyectado.
            headingDegrees?.let { heading ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(BikerColors.BackgroundDark)
                        .border(1.dp, ringColor, CircleShape)
                ) {
                    Icon(
                        imageVector = AppIcons.SelfHeading,
                        contentDescription = "Dirección de avance",
                        tint = ringColor,
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer(rotationZ = heading)
                    )
                }
            }
        }

        // Aviso honesto siempre visible cuando el pin corresponde a la última posición
        // válida retenida tras caducar el GPS del peer (Fix A). Sin fix propio no se inventa
        // una coordenada: solo se envejece la última real con su antigüedad.
        marker.positionAgeSeconds?.let { ageSeconds ->
            Text(
                text = "⏱ hace ${ageSeconds}s",
                color = BikerColors.PrimaryAmber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

    }
}
