package com.rutabikersync.app.feature.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.core.clubroutes.RideRoster
import com.rutabikersync.app.core.clubroutes.RideRosterParticipant
import com.rutabikersync.app.feature.groups.ClubRsvpUi
import com.rutabikersync.app.ui.theme.BikerColors

/** Tarjeta desplazable de participantes reales de una rodada del club. */
@Composable
fun RideParticipantsScreen(
    roster: RideRoster,
    currentUserId: String,
    currentUserIsLeader: Boolean,
    pendingCount: Int,
    isStarting: Boolean,
    errorMessage: String?,
    onRsvp: (routeId: String, userId: String, status: String) -> Unit,
    onArrive: (routeId: String, userId: String) -> Unit,
    onStart: (routeId: String) -> Unit,
    onArriveDestination: ((routeId: String) -> Unit)? = null,
    onStartReturn: ((routeId: String) -> Unit)? = null
) {
    val gate = RideParticipantsUi.startGate(roster.participants)
    val alreadyStarted = roster.outboundStartedAt != null
    val pendingBanner = ClubRsvpUi.bannerMessage(null, pendingCount)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, BikerColors.PrimaryAmber.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "PARTICIPANTES (${roster.participants.size})",
                color = BikerColors.PrimaryAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Ruta ${roster.routeId.take(8)}… · Salida: ${ClubRsvpUi.formatRouteDateTime(roster.startsAt, full = false)}",
                color = BikerColors.TextSecondary,
                fontSize = 11.sp
            )
            Text(
                text = "Solo tu fila es interactiva; las demás muestran el estado real del club.",
                color = BikerColors.TextSecondary,
                fontSize = 11.sp
            )
            pendingBanner?.let { message ->
                Text(
                    text = message,
                    color = BikerColors.StatusYellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF241C15), RoundedCornerShape(8.dp))
                        .border(1.dp, BikerColors.StatusYellow.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(roster.participants, key = { it.userId }) { participant ->
                    ParticipantRow(
                        participant = participant,
                        isOwn = participant.userId == currentUserId,
                        startsAt = roster.startsAt,
                        returnStartedAt = roster.returnStartedAt,
                        onRsvp = { status -> onRsvp(roster.routeId, participant.userId, status) },
                        onArrive = { onArrive(roster.routeId, participant.userId) }
                    )
                }
            }

            if (currentUserIsLeader || alreadyStarted) {
                val isReturnActive = roster.returnStartedAt != null
                val isDestinationReached = roster.destinationReachedAt != null
                if (currentUserIsLeader && !alreadyStarted && !gate.enabled) {
                    Text(
                        text = gate.blockedMessage,
                        color = BikerColors.StatusYellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isDestinationReached && !isReturnActive) {
                    if (currentUserIsLeader) {
                        Button(
                            onClick = { onStartReturn?.invoke(roster.routeId) ?: onStart(roster.routeId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Iniciar Retorno", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { onStart(roster.routeId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF38BDF8),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("En Destino · Abrir mapa", fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (alreadyStarted && !isDestinationReached) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onStart(roster.routeId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BikerColors.PrimaryAmber,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Abrir mapa", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onArriveDestination?.invoke(roster.routeId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF38BDF8),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Llegamos", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Button(
                        onClick = { onStart(roster.routeId) },
                        enabled = !isStarting && (alreadyStarted || gate.enabled),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BikerColors.PrimaryAmber,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                        Text(
                            when {
                                isStarting -> "Iniciando…"
                                isReturnActive -> "Abrir mapa (Retorno)"
                                alreadyStarted -> "Abrir mapa"
                                else -> "Iniciar Ruta"
                            }
                        )
                    }
                }
            }
            errorMessage?.let { error ->
                Text(text = error, color = BikerColors.StatusRed, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ParticipantRow(
    participant: RideRosterParticipant,
    isOwn: Boolean,
    startsAt: String?,
    returnStartedAt: String? = null,
    onRsvp: (String) -> Unit,
    onArrive: () -> Unit
) {
    val action = RideParticipantsUi.rowAction(participant, if (isOwn) participant.userId else "", startsAt, returnStartedAt)
    val accent = actionColor(participant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171E2B), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowAvatar(participant, accent)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = participant.displayName,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = participant.clubRole?.takeIf { it.isNotBlank() }
                    ?: if (participant.isLeader) "Líder" else "Miembro",
                color = BikerColors.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isOwn && action.kind == RideParticipantActionKind.DECISIONS) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RowActionButton(
                    label = "Asistiré",
                    icon = Icons.Default.Check,
                    color = BikerColors.StatusGreen,
                    enabled = true,
                    onClick = { onRsvp("confirmed") }
                )
                RowActionButton(
                    label = "No asistiré",
                    icon = Icons.Default.Close,
                    color = BikerColors.StatusRed,
                    enabled = true,
                    onClick = { onRsvp("no_go") }
                )
            }
        } else {
            RowActionButton(
                label = action.label,
                icon = when (action.kind) {
                    RideParticipantActionKind.ON_SITE,
                    RideParticipantActionKind.CONFIRM,
                    RideParticipantActionKind.HOME -> Icons.Default.Check
                    else -> null
                },
                color = accent,
                enabled = isOwn && action.enabled,
                onClick = {
                    when (action.kind) {
                        RideParticipantActionKind.ON_SITE -> onArrive()
                        RideParticipantActionKind.CONFIRM -> onRsvp("confirmed")
                        RideParticipantActionKind.HOME -> onRsvp("home")
                        else -> Unit
                    }
                }
            )
        }
    }
}

@Composable
private fun RowAvatar(participant: RideRosterParticipant, accent: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0xFF242D3C))
            .border(2.dp, accent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = RideParticipantsUi.initials(participant.displayName),
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun RowActionButton(
    label: String,
    icon: ImageVector?,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.18f),
            contentColor = color,
            disabledContainerColor = color.copy(alpha = 0.10f),
            disabledContentColor = color.copy(alpha = 0.85f)
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.heightIn(min = 34.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable
private fun actionColor(participant: RideRosterParticipant): Color = when {
    participant.homeAt != null || participant.status == "home" -> BikerColors.StatusGreen
    participant.onSiteAt != null -> BikerColors.StatusGreen
    participant.status == "confirmed" -> BikerColors.StatusGreen
    participant.status == "no_go" -> BikerColors.StatusRed
    else -> BikerColors.StatusYellow
}
