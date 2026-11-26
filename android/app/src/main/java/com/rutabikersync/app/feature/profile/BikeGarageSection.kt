package com.rutabikersync.app.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.feature.profile.garage.BikeGarageRepository
import com.rutabikersync.app.feature.profile.garage.BikeGarageState
import com.rutabikersync.app.feature.profile.garage.ComponentEvaluation
import com.rutabikersync.app.feature.profile.garage.CoolingType
import com.rutabikersync.app.feature.profile.garage.FaultStatus
import com.rutabikersync.app.feature.profile.garage.GarageStatus
import java.time.LocalDate
import java.util.Locale

@Composable
fun BikeGarageSection(
    state: BikeGarageState,
    repository: BikeGarageRepository,
    onStateChanged: (BikeGarageState) -> Unit
) {
    val evaluations = remember(state) { repository.evaluateAll(state) }
    val evalById = remember(evaluations) { evaluations.associateBy { it.id } }
    val today = remember { LocalDate.now().toEpochDay() }

    var showOdometerDialog by remember { mutableStateOf(false) }
    var showChainResetDialog by remember { mutableStateOf(false) }
    var showFaultDialog by remember { mutableStateOf(false) }
    var showExpenseDialog by remember { mutableStateOf(false) }
    var showOnlyOpenFaults by remember { mutableStateOf(false) }
    var editingComponentId by remember { mutableStateOf<String?>(null) }

    val okCount = evaluations.count { it.status == GarageStatus.OK }
    val warnCount = evaluations.count { it.status == GarageStatus.WARNING }
    val critCount = evaluations.count { it.status == GarageStatus.CRITICAL }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Odómetro y Resumen de Semáforos
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF161922).copy(alpha = 0.84f),
            border = BorderStroke(1.dp, Color(0xFFD4AF37).copy(alpha = 0.65f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ODÓMETRO DE LA MOTO",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${state.odometerKm} km",
                            color = Color(0xFFE5B869),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Surface(
                        onClick = { showOdometerDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1F2630).copy(alpha = 0.90f),
                        border = BorderStroke(1.dp, Color(0xFFD4AF37))
                    ) {
                        Text(
                            text = "Calibrar km",
                            color = Color(0xFFD4AF37),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SemaphorePill("🟢 $okCount Al día", Color(0xFF34D399), Modifier.weight(1f))
                    SemaphorePill("🟡 $warnCount Próximos", Color(0xFFF59E0B), Modifier.weight(1f))
                    SemaphorePill("🔴 $critCount Críticos", Color(0xFFEF4444), Modifier.weight(1f))
                }
            }
        }

        // 2. Seguridad Crítica (Neumáticos y Frenos)
        GarageGroupCard(title = "🛡️ Seguridad Crítica (Neumáticos y Frenos)") {
            evalById["tire_front"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Renovar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    frontTire = state.frontTire.copy(
                                        mountedEpochDay = today,
                                        mountedKm = state.odometerKm,
                                        treadDepthMm = 5.5
                                    )
                                )
                            )
                        )
                    }
                )
            }
            evalById["tire_rear"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Renovar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    rearTire = state.rearTire.copy(
                                        mountedEpochDay = today,
                                        mountedKm = state.odometerKm,
                                        treadDepthMm = 6.0
                                    )
                                )
                            )
                        )
                    }
                )
            }
            evalById["brake_front"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Cambiar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    frontBrakePad = state.frontBrakePad.copy(
                                        lastChangeKm = state.odometerKm,
                                        remainingLifePct = 100
                                    )
                                )
                            )
                        )
                    }
                )
            }
            evalById["brake_rear"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Cambiar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    rearBrakePad = state.rearBrakePad.copy(
                                        lastChangeKm = state.odometerKm,
                                        remainingLifePct = 100
                                    )
                                )
                            )
                        )
                    }
                )
            }
        }

        // 3. Tren Motriz y Transmisión (incluye Filtro de Aceite, Aire y Gasolina)
        GarageGroupCard(title = "⚙️ Tren Motriz, Filtros y Transmisión") {
            evalById["engine_oil"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Cambiar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    engineOil = state.engineOil.copy(
                                        lastChangeKm = state.odometerKm,
                                        lastChangeEpochDay = today
                                    )
                                )
                            )
                        )
                    }
                )
            }
            evalById["oil_filter"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Cambiar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    oilFilter = state.oilFilter.copy(
                                        lastChangeKm = state.odometerKm,
                                        lastChangeEpochDay = today
                                    )
                                )
                            )
                        )
                    }
                )
            }
            evalById["air_filter"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Servicio",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    airFilter = state.airFilter.copy(
                                        lastServiceKm = state.odometerKm,
                                        lastServiceEpochDay = today
                                    )
                                )
                            )
                        )
                    }
                )
            }
            evalById["fuel_filter"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Cambiar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    fuelFilter = state.fuelFilter.copy(
                                        lastChangeKm = state.odometerKm,
                                        lastChangeEpochDay = today
                                    )
                                )
                            )
                        )
                    }
                )
            }
            evalById["chain_lube"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Engrasar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = { onStateChanged(repository.recordChainLube(state)) }
                )
            }
            evalById["chain_kit"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Cambiar Kit",
                    onEdit = { editingComponentId = eval.id },
                    onAction = { showChainResetDialog = true }
                )
            }
            evalById["spark_plug"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Cambiar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    sparkPlug = state.sparkPlug.copy(lastChangeKm = state.odometerKm)
                                )
                            )
                        )
                    }
                )
            }
            evalById["bearings"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Cambiar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    bearings = state.bearings.copy(lastChangeKm = state.odometerKm)
                                )
                            )
                        )
                    }
                )
            }
        }

        // 4. Fluidos y Eléctrico
        GarageGroupCard(title = "🔋 Fluidos y Sistema Eléctrico") {
            evalById["brake_fluid"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Purgar",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    brakeFluid = state.brakeFluid.copy(lastChangeEpochDay = today)
                                )
                            )
                        )
                    }
                )
            }
            evalById["battery"]?.let { eval ->
                GarageComponentRow(
                    eval = eval,
                    actionLabel = "Nueva",
                    onEdit = { editingComponentId = eval.id },
                    onAction = {
                        onStateChanged(
                            repository.saveState(
                                state.copy(
                                    battery = state.battery.copy(installedEpochDay = today)
                                )
                            )
                        )
                    }
                )
            }
            if (state.coolingType == CoolingType.LIQUID) {
                evalById["coolant"]?.let { eval ->
                    GarageComponentRow(
                        eval = eval,
                        actionLabel = "Cambiar",
                        onAction = {
                            onStateChanged(
                                repository.saveState(
                                    state.copy(
                                        coolant = state.coolant.copy(
                                            lastChangeKm = state.odometerKm,
                                            lastChangeEpochDay = today
                                        )
                                    )
                                )
                            )
                        }
                    )
                }
            }
        }

        // 5. Bitácora de Averías y Gastos
        GarageGroupCard(title = "📒 Bitácora de Averías y Gastos") {
            val totalUsd = repository.totalExpensesUsd(state)
            val costPerKm = repository.costPerKmUsd(state)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Gasto total: $%.2f · Costo/km: $%.3f",
                        totalUsd,
                        costPerKm
                    ),
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = { showFaultDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                ) {
                    Text(
                        text = "+ Registrar Avería",
                        color = Color(0xFFFCA5A5),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp)
                    )
                }
                Surface(
                    onClick = { showExpenseDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF34D399).copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Color(0xFF34D399).copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "+ Registrar Gasto",
                        color = Color(0xFF6EE7B7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Averías (${state.faults.size})",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { showOnlyOpenFaults = !showOnlyOpenFaults }) {
                    Text(
                        text = if (showOnlyOpenFaults) "Ver todas" else "Solo abiertas",
                        color = Color(0xFFE5B869),
                        fontSize = 11.sp
                    )
                }
            }

            val displayedFaults = if (showOnlyOpenFaults) {
                state.faults.filter { it.status != FaultStatus.RESOLVED }
            } else {
                state.faults
            }

            if (displayedFaults.isEmpty()) {
                Text(
                    text = "Sin averías registradas.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            } else {
                displayedFaults.take(5).forEach { fault ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${fault.system.label} · ${fault.severity.label} (${fault.odometerKm} km)",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = fault.description,
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            val nextStatus = when (fault.status) {
                                FaultStatus.OPEN -> FaultStatus.IN_WORKSHOP
                                FaultStatus.IN_WORKSHOP -> FaultStatus.RESOLVED
                                FaultStatus.RESOLVED -> FaultStatus.OPEN
                            }
                            Surface(
                                onClick = {
                                    onStateChanged(
                                        repository.updateFaultStatus(state, fault.id, nextStatus)
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF1E293B),
                                border = BorderStroke(1.dp, Color(0xFF475569))
                            ) {
                                Text(
                                    text = fault.status.label,
                                    color = if (fault.status == FaultStatus.RESOLVED) Color(0xFF34D399) else Color(0xFFF59E0B),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOdometerDialog) {
        OdometerCalibrationDialog(
            currentKm = state.odometerKm,
            onDismiss = { showOdometerDialog = false },
            onConfirm = { newKm ->
                onStateChanged(repository.saveState(state.copy(odometerKm = newKm)))
                showOdometerDialog = false
            }
        )
    }

    if (showChainResetDialog) {
        ChainKitResetDialog(
            onDismiss = { showChainResetDialog = false },
            onConfirm = { resetToZero ->
                onStateChanged(repository.recordChainKitReplacement(state, resetToZero))
                showChainResetDialog = false
            }
        )
    }

    editingComponentId?.let { componentId ->
        GarageComponentEditDialog(
            componentId = componentId,
            state = state,
            onDismiss = { editingComponentId = null },
            onSave = { updated ->
                onStateChanged(repository.saveState(updated))
                editingComponentId = null
            }
        )
    }

    if (showFaultDialog) {
        AddFaultDialog(
            onDismiss = { showFaultDialog = false },
            onConfirm = { system, severity, desc ->
                onStateChanged(repository.addFault(state, system, severity, desc))
                showFaultDialog = false
            }
        )
    }

    if (showExpenseDialog) {
        AddExpenseDialog(
            onDismiss = { showExpenseDialog = false },
            onConfirm = { category, amount, note ->
                onStateChanged(repository.addExpense(state, category, amount, note))
                showExpenseDialog = false
            }
        )
    }
}

@Composable
private fun SemaphorePill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun GarageGroupCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF161922).copy(alpha = 0.82f),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                color = Color(0xFFE5B869),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun GarageComponentRow(
    eval: ComponentEvaluation,
    actionLabel: String,
    onEdit: () -> Unit = {},
    onAction: () -> Unit
) {
    val badgeColor = when (eval.status) {
        GarageStatus.OK -> Color(0xFF34D399)
        GarageStatus.WARNING -> Color(0xFFF59E0B)
        GarageStatus.CRITICAL -> Color(0xFFEF4444)
    }
    Surface(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.72f),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = eval.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "• ${eval.status.label}",
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = eval.summary,
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1F2937),
                border = BorderStroke(1.dp, Color(0xFF475569))
            ) {
                Text(
                    text = actionLabel,
                    color = Color(0xFFE5B869),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}
