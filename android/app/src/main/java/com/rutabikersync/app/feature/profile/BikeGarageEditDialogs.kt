package com.rutabikersync.app.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.rutabikersync.app.feature.profile.garage.AirFilterType
import com.rutabikersync.app.feature.profile.garage.BatteryType
import com.rutabikersync.app.feature.profile.garage.BearingLocation
import com.rutabikersync.app.feature.profile.garage.BikeGarageState
import com.rutabikersync.app.feature.profile.garage.BrakeFluidType
import com.rutabikersync.app.feature.profile.garage.BrakePadCompound
import com.rutabikersync.app.feature.profile.garage.ChainType
import com.rutabikersync.app.feature.profile.garage.CoolantTechnology
import com.rutabikersync.app.feature.profile.garage.FuelFilterType
import com.rutabikersync.app.feature.profile.garage.OilBaseType
import com.rutabikersync.app.feature.profile.garage.OilFilterType
import com.rutabikersync.app.feature.profile.garage.OilJasoSpec
import com.rutabikersync.app.feature.profile.garage.SparkPlugType
import com.rutabikersync.app.feature.profile.garage.TireConstruction

@Composable
internal fun GarageComponentEditDialog(
    componentId: String,
    state: BikeGarageState,
    onDismiss: () -> Unit,
    onSave: (BikeGarageState) -> Unit
) {
    when (componentId) {
        "tire_front", "tire_rear" -> {
            val isFront = componentId == "tire_front"
            val initial = if (isFront) state.frontTire else state.rearTire
            var brand by remember { mutableStateOf(initial.brand) }
            var model by remember { mutableStateOf(initial.model) }
            var construction by remember { mutableStateOf(initial.construction) }
            var dotCode by remember { mutableStateOf(initial.dotCode) }
            var psiText by remember { mutableStateOf(initial.recommendedPsi.toString()) }
            var treadText by remember { mutableStateOf(initial.treadDepthMm.toString()) }

            EditShellDialog(
                title = if (isFront) "Neumático Delantero" else "Neumático Trasero",
                onDismiss = onDismiss,
                onConfirm = {
                    val updated = initial.copy(
                        brand = brand.trim(),
                        model = model.trim(),
                        construction = construction,
                        dotCode = dotCode.trim().uppercase(),
                        recommendedPsi = psiText.toIntOrNull() ?: initial.recommendedPsi,
                        treadDepthMm = treadText.toDoubleOrNull() ?: initial.treadDepthMm
                    )
                    onSave(if (isFront) state.copy(frontTire = updated) else state.copy(rearTire = updated))
                }
            ) {
                OutlinedTextField(value = brand, onValueChange = { brand = it }, label = { Text("Marca") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Modelo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                EnumChipRow("Construcción", TireConstruction.entries, construction, { it.label }) { construction = it }
                OutlinedTextField(value = dotCode, onValueChange = { dotCode = it }, label = { Text("Código DOT (ej. 3424)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = psiText, onValueChange = { psiText = it.filter(Char::isDigit) }, label = { Text("Presión recomendada (PSI)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = treadText, onValueChange = { treadText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Profundidad actual (mm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "brake_front", "brake_rear" -> {
            val isFront = componentId == "brake_front"
            val initial = if (isFront) state.frontBrakePad else state.rearBrakePad
            var compound by remember { mutableStateOf(initial.compound) }
            var lifeText by remember { mutableStateOf(initial.remainingLifePct.toString()) }
            var intervalText by remember { mutableStateOf(initial.intervalKm.toString()) }

            EditShellDialog(
                title = if (isFront) "Pastillas Delanteras" else "Pastillas Traseras",
                onDismiss = onDismiss,
                onConfirm = {
                    val updated = initial.copy(
                        compound = compound,
                        remainingLifePct = (lifeText.toIntOrNull() ?: initial.remainingLifePct).coerceIn(0, 100),
                        intervalKm = (intervalText.toIntOrNull() ?: initial.intervalKm).coerceAtLeast(1_000)
                    )
                    onSave(if (isFront) state.copy(frontBrakePad = updated) else state.copy(rearBrakePad = updated))
                }
            ) {
                EnumChipRow("Compuesto", BrakePadCompound.entries, compound, { it.label }) { compound = it }
                OutlinedTextField(value = lifeText, onValueChange = { lifeText = it.filter(Char::isDigit) }, label = { Text("% vida útil restante (0-100)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = intervalText, onValueChange = { intervalText = it.filter(Char::isDigit) }, label = { Text("Intervalo estimado (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "engine_oil" -> {
            val initial = state.engineOil
            var brand by remember { mutableStateOf(initial.brand) }
            var viscosity by remember { mutableStateOf(initial.viscosity) }
            var baseType by remember { mutableStateOf(initial.baseType) }
            var jasoSpec by remember { mutableStateOf(initial.jasoSpec) }
            var intervalKmText by remember { mutableStateOf(initial.intervalKm.toString()) }
            var intervalDaysText by remember { mutableStateOf(initial.intervalDays.toString()) }

            EditShellDialog(
                title = "Aceite de Motor",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            engineOil = initial.copy(
                                brand = brand.trim(),
                                viscosity = viscosity.trim().ifBlank { initial.viscosity },
                                baseType = baseType,
                                jasoSpec = jasoSpec,
                                intervalKm = (intervalKmText.toIntOrNull() ?: initial.intervalKm).coerceAtLeast(500),
                                intervalDays = (intervalDaysText.toIntOrNull() ?: initial.intervalDays).coerceAtLeast(30)
                            )
                        )
                    )
                }
            ) {
                OutlinedTextField(value = brand, onValueChange = { brand = it }, label = { Text("Marca (ej. Motul, Yamalube)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                EnumChipRow("Viscosidad", listOf("10W-30", "10W-40", "10W-50", "15W-50", "20W-50"), viscosity, { it }) { viscosity = it }
                EnumChipRow("Base", OilBaseType.entries, baseType, { it.label }) { baseType = it }
                EnumChipRow("Norma JASO", OilJasoSpec.entries, jasoSpec, { it.label }) { jasoSpec = it }
                OutlinedTextField(value = intervalKmText, onValueChange = { intervalKmText = it.filter(Char::isDigit) }, label = { Text("Intervalo (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = intervalDaysText, onValueChange = { intervalDaysText = it.filter(Char::isDigit) }, label = { Text("Intervalo (días)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "oil_filter" -> {
            val initial = state.oilFilter
            var ref by remember { mutableStateOf(initial.brandOrRef) }
            var type by remember { mutableStateOf(initial.filterType) }
            var intervalKmText by remember { mutableStateOf(initial.intervalKm.toString()) }

            EditShellDialog(
                title = "Filtro de Aceite",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            oilFilter = initial.copy(
                                brandOrRef = ref.trim(),
                                filterType = type,
                                intervalKm = (intervalKmText.toIntOrNull() ?: initial.intervalKm).coerceAtLeast(500)
                            )
                        )
                    )
                }
            ) {
                EnumChipRow("Tipo de filtro", OilFilterType.entries, type, { it.label }) { type = it }
                OutlinedTextField(value = ref, onValueChange = { ref = it }, label = { Text("Marca / Referencia (ej. HF204)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = intervalKmText, onValueChange = { intervalKmText = it.filter(Char::isDigit) }, label = { Text("Intervalo (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "air_filter" -> {
            val initial = state.airFilter
            var ref by remember { mutableStateOf(initial.brandOrRef) }
            var type by remember { mutableStateOf(initial.filterType) }
            var intervalKmText by remember { mutableStateOf(initial.intervalKm.toString()) }

            EditShellDialog(
                title = "Filtro de Aire",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            airFilter = initial.copy(
                                brandOrRef = ref.trim(),
                                filterType = type,
                                intervalKm = (intervalKmText.toIntOrNull() ?: initial.intervalKm).coerceAtLeast(500)
                            )
                        )
                    )
                }
            ) {
                EnumChipRow("Tipo de filtro", AirFilterType.entries, type, { it.label }) { type = it }
                OutlinedTextField(value = ref, onValueChange = { ref = it }, label = { Text("Marca / Referencia") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = intervalKmText, onValueChange = { intervalKmText = it.filter(Char::isDigit) }, label = { Text("Intervalo limpieza/cambio (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "fuel_filter" -> {
            val initial = state.fuelFilter
            var ref by remember { mutableStateOf(initial.brandOrRef) }
            var type by remember { mutableStateOf(initial.filterType) }
            var intervalKmText by remember { mutableStateOf(initial.intervalKm.toString()) }

            EditShellDialog(
                title = "Filtro de Gasolina",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            fuelFilter = initial.copy(
                                brandOrRef = ref.trim(),
                                filterType = type,
                                intervalKm = (intervalKmText.toIntOrNull() ?: initial.intervalKm).coerceAtLeast(500)
                            )
                        )
                    )
                }
            ) {
                EnumChipRow("Tipo de filtro", FuelFilterType.entries, type, { it.label }) { type = it }
                OutlinedTextField(value = ref, onValueChange = { ref = it }, label = { Text("Marca / Referencia") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = intervalKmText, onValueChange = { intervalKmText = it.filter(Char::isDigit) }, label = { Text("Intervalo (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "chain_lube", "chain_kit" -> {
            val initial = state.driveChain
            var chainType by remember { mutableStateOf(initial.chainType) }
            var lubeIntervalText by remember { mutableStateOf(initial.lubeIntervalKm.toString()) }
            var kitIntervalText by remember { mutableStateOf(initial.kitIntervalKm.toString()) }

            EditShellDialog(
                title = "Kit de Arrastre y Cadena",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            driveChain = initial.copy(
                                chainType = chainType,
                                lubeIntervalKm = (lubeIntervalText.toIntOrNull() ?: initial.lubeIntervalKm).coerceAtLeast(100),
                                kitIntervalKm = (kitIntervalText.toIntOrNull() ?: initial.kitIntervalKm).coerceAtLeast(2_000)
                            )
                        )
                    )
                }
            ) {
                EnumChipRow("Tipo de cadena", ChainType.entries, chainType, { it.label }) { chainType = it }
                OutlinedTextField(value = lubeIntervalText, onValueChange = { lubeIntervalText = it.filter(Char::isDigit) }, label = { Text("Recordatorio engrase (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = kitIntervalText, onValueChange = { kitIntervalText = it.filter(Char::isDigit) }, label = { Text("Intervalo cambio kit (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "spark_plug" -> {
            val initial = state.sparkPlug
            var plugType by remember { mutableStateOf(initial.plugType) }
            var intervalText by remember { mutableStateOf(initial.intervalKm.toString()) }

            EditShellDialog(
                title = "Bujías",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            sparkPlug = initial.copy(
                                plugType = plugType,
                                intervalKm = (intervalText.toIntOrNull() ?: plugType.defaultIntervalKm).coerceAtLeast(1_000)
                            )
                        )
                    )
                }
            ) {
                EnumChipRow("Tipo de electrodo", SparkPlugType.entries, plugType, { it.label }) {
                    plugType = it
                    intervalText = it.defaultIntervalKm.toString()
                }
                OutlinedTextField(value = intervalText, onValueChange = { intervalText = it.filter(Char::isDigit) }, label = { Text("Intervalo (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "bearings" -> {
            val initial = state.bearings
            var location by remember { mutableStateOf(initial.location) }
            var intervalText by remember { mutableStateOf(initial.intervalKm.toString()) }

            EditShellDialog(
                title = "Rodamientos",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            bearings = initial.copy(
                                location = location,
                                intervalKm = (intervalText.toIntOrNull() ?: initial.intervalKm).coerceAtLeast(2_000)
                            )
                        )
                    )
                }
            ) {
                EnumChipRow("Ubicación", BearingLocation.entries, location, { it.label }) { location = it }
                OutlinedTextField(value = intervalText, onValueChange = { intervalText = it.filter(Char::isDigit) }, label = { Text("Intervalo (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "brake_fluid" -> {
            val initial = state.brakeFluid
            var fluidType by remember { mutableStateOf(initial.fluidType) }
            var intervalDaysText by remember { mutableStateOf(initial.intervalDays.toString()) }

            EditShellDialog(
                title = "Líquido de Frenos",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            brakeFluid = initial.copy(
                                fluidType = fluidType,
                                intervalDays = (intervalDaysText.toIntOrNull() ?: initial.intervalDays).coerceAtLeast(90)
                            )
                        )
                    )
                }
            ) {
                EnumChipRow("Especificación", BrakeFluidType.entries, fluidType, { it.label }) { fluidType = it }
                OutlinedTextField(value = intervalDaysText, onValueChange = { intervalDaysText = it.filter(Char::isDigit) }, label = { Text("Intervalo caducidad (días)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "battery" -> {
            val initial = state.battery
            var batteryType by remember { mutableStateOf(initial.batteryType) }
            var voltageText by remember { mutableStateOf(initial.nominalVoltage.toString()) }

            EditShellDialog(
                title = "Batería",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            battery = initial.copy(
                                batteryType = batteryType,
                                nominalVoltage = voltageText.toDoubleOrNull() ?: initial.nominalVoltage
                            )
                        )
                    )
                }
            ) {
                EnumChipRow("Tecnología", BatteryType.entries, batteryType, { it.label }) { batteryType = it }
                OutlinedTextField(value = voltageText, onValueChange = { voltageText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Voltaje nominal (V)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        "coolant" -> {
            val initial = state.coolant
            var brand by remember { mutableStateOf(initial.brandOrType) }
            var tech by remember { mutableStateOf(initial.technology) }
            var intervalKmText by remember { mutableStateOf(initial.intervalKm.toString()) }

            EditShellDialog(
                title = "Líquido Refrigerante",
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        state.copy(
                            coolant = initial.copy(
                                brandOrType = brand.trim(),
                                technology = tech,
                                intervalKm = (intervalKmText.toIntOrNull() ?: initial.intervalKm).coerceAtLeast(2_000)
                            )
                        )
                    )
                }
            ) {
                EnumChipRow("Tecnología", CoolantTechnology.entries, tech, { it.label }) { tech = it }
                OutlinedTextField(value = brand, onValueChange = { brand = it }, label = { Text("Marca / Concentración (ej. 50/50)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = intervalKmText, onValueChange = { intervalKmText = it.filter(Char::isDigit) }, label = { Text("Intervalo (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EditShellDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161922),
        title = { Text("Editar $title", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content()
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
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
private fun <T> EnumChipRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0xFFE5B869).copy(alpha = 0.22f) else Color(0xFF0F172A),
                    border = BorderStroke(1.dp, if (isSelected) Color(0xFFE5B869) else Color(0xFF334155))
                ) {
                    Text(
                        text = labelOf(option),
                        color = if (isSelected) Color(0xFFE5B869) else Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
