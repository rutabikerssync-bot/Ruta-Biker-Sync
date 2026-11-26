package com.rutabikersync.app.feature.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CreateCustomRoleDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, canCreateRide: Boolean, canModerate: Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var canCreateRide by remember { mutableStateOf(false) }
    var canModerate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161922),
        title = {
            Text(
                text = "Nuevo Cargo en Mi Club",
                color = Color(0xFFE5B869),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nombre del cargo (ej. Fotógrafo, Mecánico)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = canCreateRide,
                        onCheckedChange = { canCreateRide = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE5B869))
                    )
                    Text("Puede convocar rodadas", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = canModerate,
                        onCheckedChange = { canModerate = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE5B869))
                    )
                    Text("Puede moderar convivencia", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.trim().length >= 2) {
                        onCreate(title.trim(), canCreateRide, canModerate)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869), contentColor = Color.Black)
            ) {
                Text("Crear Cargo", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = Color(0xFF94A3B8)) }
        }
    )
}

@Composable
fun AssignCustomRoleDialog(
    role: CustomClubRole,
    members: List<ClubMemberModel>,
    onDismiss: () -> Unit,
    onAssign: (ClubMemberModel) -> Unit
) {
    val eligibleMembers = remember(members) {
        members.filter { it.statusKind != MemberStatusKind.GUEST }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161922),
        title = {
            Text("Asignar «${role.title}»", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Solo Full Members y Prospectos (P7 excluye invitados).",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                eligibleMembers.forEach { member ->
                    Surface(
                        onClick = { onAssign(member) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(member.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(member.statusKind.name, color = Color(0xFFE5B869), fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar", color = Color(0xFF94A3B8)) }
        }
    )
}

@Composable
fun ClubDecisionsDialog(
    group: ClubGroupModel,
    currentUserId: String,
    isLeader: Boolean,
    decisions: List<ClubDecision>,
    coordinator: ClubDecisionsCoordinator,
    onDismiss: () -> Unit,
    onCreateDecision: (
        title: String,
        description: String,
        category: DecisionCategory,
        options: List<String>,
        audiences: Set<DecisionAudience>,
        minQuorumPercent: Int
    ) -> Unit,
    onCastVote: (decisionId: String, optionIndex: Int) -> Unit,
    onCloseDecision: (decisionId: String) -> Unit
) {
    var showCreateForm by remember { mutableStateOf(false) }

    if (showCreateForm) {
        CreateDecisionDialog(
            onDismiss = { showCreateForm = false },
            onCreate = { title, desc, cat, opts, aud, quorum ->
                onCreateDecision(title, desc, cat, opts, aud, quorum)
                showCreateForm = false
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141822),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("🗳️ Decisiones del Club", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Voto secreto · Audiencia controlada", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                if (isLeader) {
                    Surface(
                        onClick = { showCreateForm = true },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE5B869).copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, Color(0xFFE5B869))
                    ) {
                        Text(
                            text = "+ Nueva",
                            color = Color(0xFFE5B869),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (decisions.isEmpty()) {
                    Text(
                        text = "No hay votaciones activas o cerradas para tu rol en este club.",
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp
                    )
                } else {
                    decisions.forEach { decision ->
                        val eligible = coordinator.isUserEligibleForDecision(
                            userId = currentUserId,
                            group = group,
                            allowedAudiences = decision.allowedAudiences
                        )
                        DecisionCard(
                            decision = decision,
                            isLeader = isLeader,
                            isEligible = eligible,
                            onCastVote = { idx -> onCastVote(decision.id, idx) },
                            onClose = { onCloseDecision(decision.id) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold) }
        }
    )
}

@Composable
private fun DecisionCard(
    decision: ClubDecision,
    isLeader: Boolean,
    isEligible: Boolean,
    onCastVote: (Int) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1C2434), Color(0xFF121722))))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = decision.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (decision.isOpen) "ABIERTA" else "CERRADA",
                    color = if (decision.isOpen) Color(0xFF34D399) else Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (decision.description.isNotBlank()) {
                Text(decision.description, color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }
            Text(
                text = "Votan: ${decision.allowedAudiences.joinToString(" · ") { it.label }} · ${decision.totalVotes}/${decision.eligibleCount} votos",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
            if (decision.isOpen) {
                Text(
                    text = "🔒 Voto secreto: nadie puede ver tu elección.",
                    color = Color(0xFFE5B869),
                    fontSize = 11.sp
                )
                if (decision.hasVoted) {
                    Text("✅ Tu voto secreto ya fue registrado.", color = Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else if (isEligible) {
                    decision.options.forEachIndexed { idx, opt ->
                        Surface(
                            onClick = { onCastVote(idx) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0F172A),
                            border = BorderStroke(1.dp, Color(0xFFE5B869).copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = opt,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                if (isLeader) {
                    TextButton(onClick = onClose) {
                        Text("Cerrar votación y publicar resultado", color = Color(0xFFF59E0B), fontSize = 11.sp)
                    }
                }
            } else {
                val tallies = decision.tallies ?: emptyList()
                val total = tallies.sum().coerceAtLeast(1)
                decision.options.forEachIndexed { idx, opt ->
                    val count = tallies.getOrElse(idx) { 0 }
                    val isWinner = decision.winningOptionIndex == idx
                    val pct = (count * 100) / total
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = if (isWinner) "🏆 $opt" else opt,
                                color = if (isWinner) Color(0xFF34D399) else Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal
                            )
                            Text("$count ($pct%)", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF0F172A))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((count.toFloat() / total.toFloat()).coerceIn(0.03f, 1f))
                                    .height(4.dp)
                                    .background(if (isWinner) Color(0xFF34D399) else Color(0xFF475569))
                            )
                        }
                    }
                }
                if (!decision.quorumMet) {
                    Text("⚠️ No alcanzó el quórum mínimo (${decision.minQuorumPercent}%).", color = Color(0xFFF59E0B), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CreateDecisionDialog(
    onDismiss: () -> Unit,
    onCreate: (
        title: String,
        description: String,
        category: DecisionCategory,
        options: List<String>,
        audiences: Set<DecisionAudience>,
        minQuorumPercent: Int
    ) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(DecisionCategory.ROUTE) }
    var selectedAudiences by remember { mutableStateOf(setOf(DecisionAudience.FULL_MEMBERS, DecisionAudience.PROSPECTS)) }
    var optionsText by remember { mutableStateOf("A favor, En contra, Abstención") }
    var minQuorum by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161922),
        title = { Text("Nueva Decisión Secreta", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Plantillas rápidas:", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OFFICIAL_DECISION_TEMPLATES.forEach { tpl ->
                        Surface(
                            onClick = {
                                category = tpl.category
                                selectedAudiences = tpl.defaultAudiences
                                optionsText = tpl.defaultOptions.joinToString(", ")
                                minQuorum = tpl.defaultMinQuorumPercent
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0F172A),
                            border = BorderStroke(1.dp, Color(0xFFE5B869).copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = tpl.label,
                                color = Color(0xFFE5B869),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título de la decisión") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Contexto (opcional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = optionsText,
                    onValueChange = { optionsText = it },
                    label = { Text("Opciones separadas por coma (2 a 4)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("¿Quién vota? (oculto para no elegibles):", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                DecisionAudience.entries.forEach { aud ->
                    val checked = aud in selectedAudiences
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selectedAudiences = if (it) selectedAudiences + aud else selectedAudiences - aud
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE5B869))
                        )
                        Text(aud.label, color = Color.White, fontSize = 12.sp)
                    }
                }
                Text("Quórum mínimo opcional:", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Mayoría simple", 50 to "50%", 66 to "66%").forEach { (q, lbl) ->
                        val sel = minQuorum == q
                        Surface(
                            onClick = { minQuorum = q },
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) Color(0xFFE5B869).copy(alpha = 0.2f) else Color(0xFF0F172A),
                            border = BorderStroke(1.dp, if (sel) Color(0xFFE5B869) else Color(0xFF334155))
                        ) {
                            Text(lbl, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val opts = optionsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(4)
                    if (title.trim().length >= 3 && opts.size >= 2 && selectedAudiences.isNotEmpty()) {
                        onCreate(title.trim(), description.trim(), category, opts, selectedAudiences, minQuorum)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869), contentColor = Color.Black)
            ) {
                Text("Publicar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = Color(0xFF94A3B8)) }
        }
    )
}
