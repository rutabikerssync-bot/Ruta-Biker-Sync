package com.rutabikersync.app.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.feature.profile.garage.ExpenseCategory
import com.rutabikersync.app.feature.profile.garage.FaultSeverity
import com.rutabikersync.app.feature.profile.garage.FaultSystem

@Composable
internal fun OdometerCalibrationDialog(
    currentKm: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var kmText by remember { mutableStateOf(currentKm.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161922),
        title = { Text("Calibrar Odómetro", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = kmText,
                onValueChange = { kmText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Lectura actual (km)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(kmText.toIntOrNull() ?: currentKm) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869), contentColor = Color.Black)
            ) {
                Text("Guardar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = Color(0xFF94A3B8)) }
        }
    )
}

@Composable
internal fun ChainKitResetDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161922),
        title = { Text("Cambio de Kit de Arrastre", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = "¿Deseas resetear el odómetro de la moto a 0 o mantener el histórico?",
                color = Color(0xFFCBD5E1),
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(false) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869), contentColor = Color.Black)
            ) {
                Text("Mantener histórico", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onConfirm(true) }) {
                    Text("Resetear a 0", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar", color = Color(0xFF94A3B8))
                }
            }
        }
    )
}

@Composable
internal fun AddFaultDialog(
    onDismiss: () -> Unit,
    onConfirm: (FaultSystem, FaultSeverity, String) -> Unit
) {
    var selectedSystem by remember { mutableStateOf(FaultSystem.ENGINE) }
    var selectedSeverity by remember { mutableStateOf(FaultSeverity.MODERATE) }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161922),
        title = { Text("Registrar Avería / Falla", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sistema", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(FaultSystem.ENGINE, FaultSystem.ELECTRICAL, FaultSystem.BRAKES, FaultSystem.TRANSMISSION)
                        .forEach { sys ->
                            val selected = sys == selectedSystem
                            Surface(
                                onClick = { selectedSystem = sys },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) Color(0xFFE5B869).copy(alpha = 0.2f) else Color(0xFF0F172A),
                                border = BorderStroke(1.dp, if (selected) Color(0xFFE5B869) else Color(0xFF334155))
                            ) {
                                Text(
                                    text = sys.label,
                                    color = if (selected) Color(0xFFE5B869) else Color(0xFFCBD5E1),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                                )
                            }
                        }
                }
                Text("Severidad", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FaultSeverity.entries.forEach { sev ->
                        val selected = sev == selectedSeverity
                        Surface(
                            onClick = { selectedSeverity = sev },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) Color(0xFFEF4444).copy(alpha = 0.2f) else Color(0xFF0F172A),
                            border = BorderStroke(1.dp, if (selected) Color(0xFFEF4444) else Color(0xFF334155))
                        ) {
                            Text(
                                text = sev.label,
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 6.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción del síntoma") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (description.isNotBlank()) {
                        onConfirm(selectedSystem, selectedSeverity, description)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869), contentColor = Color.Black)
            ) {
                Text("Guardar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = Color(0xFF94A3B8)) }
        }
    )
}

@Composable
internal fun AddExpenseDialog(
    onDismiss: () -> Unit,
    onConfirm: (ExpenseCategory, Double, String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(ExpenseCategory.MAINTENANCE) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161922),
        title = { Text("Registrar Gasto", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(ExpenseCategory.MAINTENANCE, ExpenseCategory.PARTS, ExpenseCategory.TIRES, ExpenseCategory.REPAIR)
                        .forEach { cat ->
                            val selected = cat == selectedCategory
                            Surface(
                                onClick = { selectedCategory = cat },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) Color(0xFF34D399).copy(alpha = 0.2f) else Color(0xFF0F172A),
                                border = BorderStroke(1.dp, if (selected) Color(0xFF34D399) else Color(0xFF334155))
                            ) {
                                Text(
                                    text = cat.label,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                                )
                            }
                        }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Monto (USD)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (opcional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = amountText.toDoubleOrNull()
                    if (parsed != null && parsed > 0.0) {
                        onConfirm(selectedCategory, parsed, note)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34D399), contentColor = Color.Black)
            ) {
                Text("Guardar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = Color(0xFF94A3B8)) }
        }
    )
}
