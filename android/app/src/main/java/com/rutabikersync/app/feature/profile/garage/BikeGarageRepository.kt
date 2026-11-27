package com.rutabikersync.app.feature.profile.garage

import android.content.Context
import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.util.UUID
import kotlin.math.max

/**
 * Cerebro del módulo Garage: evalúa semáforos (OK / WARNING / CRITICAL) comparando
 * el odómetro actual y la fecha del sistema contra los intervalos de cada componente.
 */
class BikeGarageRepository(
    private val context: Context? = null,
    private val todayProvider: () -> Long = { LocalDate.now().toEpochDay() }
) {

    companion object {
        private const val PREFS_NAME = "rbs_bike_garage_prefs"
        private const val KEY_STATE_JSON = "garage_state_v1"

        const val TIRE_MAX_AGE_DAYS = 365L * 5L
        const val TIRE_WARNING_TREAD_MM = 2.0
        const val TIRE_CRITICAL_TREAD_MM = 1.0
        const val BRAKE_CRITICAL_REMAINING_PCT = 20
        const val BATTERY_WARNING_DAYS = 365L * 2L
        const val BATTERY_CRITICAL_DAYS = 365L * 4L

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    fun loadState(): BikeGarageState {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs?.getString(KEY_STATE_JSON, null)
        if (!raw.isNullOrBlank()) {
            runCatching { return json.decodeFromString<BikeGarageState>(raw) }
        }
        val today = todayProvider()
        return BikeGarageState(
            frontTire = TireSpec(TirePosition.FRONT, mountedEpochDay = today),
            rearTire = TireSpec(TirePosition.REAR, mountedEpochDay = today),
            engineOil = EngineOilSpec(lastChangeEpochDay = today),
            oilFilter = OilFilterSpec(lastChangeEpochDay = today),
            airFilter = AirFilterSpec(lastServiceEpochDay = today),
            fuelFilter = FuelFilterSpec(lastChangeEpochDay = today),
            brakeFluid = BrakeFluidSpec(lastChangeEpochDay = today),
            battery = BatterySpec(installedEpochDay = today),
            coolant = CoolantSpec(lastChangeEpochDay = today)
        )
    }

    fun saveState(state: BikeGarageState): BikeGarageState {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.putString(KEY_STATE_JSON, json.encodeToString(state))?.apply()
        return state
    }

    /**
     * Fase 3 Garage: descarga el respaldo remoto si existe y actualiza la caché local.
     * Offline-first: ante fallo de red retorna null y conserva el estado local intacto.
     */
    suspend fun syncFromBackend(apiClient: ClubApiClient): BikeGarageState? {
        val res = apiClient.fetchBikeGarage().getOrNull() ?: return null
        if (!res.isSuccess) return null
        val remoteObj = res.body?.get("garageState") as? JsonObject ?: return null
        return runCatching {
            val decoded = json.decodeFromString<BikeGarageState>(remoteObj.toString())
            val local = loadState()
            val merged = if (local.odometerKm > decoded.odometerKm) {
                decoded.copy(odometerKm = local.odometerKm)
            } else {
                decoded
            }
            saveState(merged)
        }.getOrNull()
    }

    /**
     * Fase 3 Garage: sube el estado actual del Garage al backend (best-effort offline-first).
     */
    suspend fun pushToBackend(apiClient: ClubApiClient, state: BikeGarageState): Boolean {
        val obj = runCatching {
            json.parseToJsonElement(json.encodeToString(state)).jsonObject
        }.getOrNull() ?: return false
        val res = apiClient.saveBikeGarage(obj).getOrNull() ?: return false
        return res.isSuccess
    }

    fun addRideDistanceKm(state: BikeGarageState, rideDistanceKm: Int): BikeGarageState {
        if (rideDistanceKm <= 0) return state
        return saveState(state.copy(odometerKm = state.odometerKm + rideDistanceKm))
    }

    fun recordChainKitReplacement(
        state: BikeGarageState,
        resetOdometerToZero: Boolean
    ): BikeGarageState {
        val updated = if (resetOdometerToZero) {
            state.copy(
                odometerKm = 0,
                odometerStartKm = 0,
                driveChain = state.driveChain.copy(
                    lastKitChangeKm = 0,
                    lastLubeKm = 0
                )
            )
        } else {
            state.copy(
                driveChain = state.driveChain.copy(
                    lastKitChangeKm = state.odometerKm,
                    lastLubeKm = state.odometerKm
                )
            )
        }
        return saveState(updated)
    }

    fun recordChainLube(state: BikeGarageState): BikeGarageState {
        return saveState(
            state.copy(
                driveChain = state.driveChain.copy(lastLubeKm = state.odometerKm)
            )
        )
    }

    fun addFault(
        state: BikeGarageState,
        system: FaultSystem,
        severity: FaultSeverity,
        description: String
    ): BikeGarageState {
        val entry = BikeFaultEntry(
            id = UUID.randomUUID().toString(),
            epochDay = todayProvider(),
            odometerKm = state.odometerKm,
            system = system,
            severity = severity,
            description = description.trim(),
            status = FaultStatus.OPEN
        )
        return saveState(state.copy(faults = listOf(entry) + state.faults))
    }

    fun updateFaultStatus(
        state: BikeGarageState,
        faultId: String,
        newStatus: FaultStatus
    ): BikeGarageState {
        return saveState(
            state.copy(
                faults = state.faults.map {
                    if (it.id == faultId) it.copy(status = newStatus) else it
                }
            )
        )
    }

    fun addExpense(
        state: BikeGarageState,
        category: ExpenseCategory,
        amountUsd: Double,
        note: String = ""
    ): BikeGarageState {
        val entry = BikeExpenseEntry(
            id = UUID.randomUUID().toString(),
            epochDay = todayProvider(),
            odometerKm = state.odometerKm,
            category = category,
            amountUsd = amountUsd.coerceAtLeast(0.0),
            note = note.trim()
        )
        return saveState(state.copy(expenses = listOf(entry) + state.expenses))
    }

    fun totalExpensesUsd(state: BikeGarageState): Double =
        state.expenses.sumOf { it.amountUsd }

    fun costPerKmUsd(state: BikeGarageState): Double {
        val trackedKm = max(0, state.odometerKm - state.odometerStartKm)
        if (trackedKm <= 0) return 0.0
        return totalExpensesUsd(state) / trackedKm.toDouble()
    }

    fun evaluateAll(state: BikeGarageState): List<ComponentEvaluation> {
        val today = todayProvider()
        val list = mutableListOf(
            evaluateTire("tire_front", "Neumático Delantero", state.frontTire, today),
            evaluateTire("tire_rear", "Neumático Trasero", state.rearTire, today),
            evaluateBrakePad("brake_front", "Pastillas Delanteras", state.frontBrakePad, state.odometerKm),
            evaluateBrakePad("brake_rear", "Pastillas Traseras", state.rearBrakePad, state.odometerKm),
            evaluateEngineOil(state.engineOil, state.odometerKm, today),
            evaluateOilFilter(state.oilFilter, state.odometerKm, today),
            evaluateAirFilter(state.airFilter, state.odometerKm, today),
            evaluateFuelFilter(state.fuelFilter, state.odometerKm, today),
            evaluateChainLube(state.driveChain, state.odometerKm),
            evaluateChainKit(state.driveChain, state.odometerKm),
            evaluateSparkPlug(state.sparkPlug, state.odometerKm),
            evaluateBearings(state.bearings, state.odometerKm),
            evaluateBrakeFluid(state.brakeFluid, today),
            evaluateBattery(state.battery, today)
        )
        if (state.coolingType == CoolingType.LIQUID) {
            list.add(evaluateCoolant(state.coolant, state.odometerKm, today))
        }
        return list
    }

    fun evaluateTire(
        id: String,
        title: String,
        tire: TireSpec,
        todayEpochDay: Long = todayProvider()
    ): ComponentEvaluation {
        val ageDays = if (tire.mountedEpochDay > 0L) (todayEpochDay - tire.mountedEpochDay).coerceAtLeast(0L) else 0L
        val daysRemaining = TIRE_MAX_AGE_DAYS - ageDays
        val ageStatus = when {
            ageDays > TIRE_MAX_AGE_DAYS -> GarageStatus.CRITICAL
            ageDays >= (TIRE_MAX_AGE_DAYS * 80L) / 100L -> GarageStatus.WARNING
            else -> GarageStatus.OK
        }
        val treadStatus = when {
            tire.treadDepthMm <= TIRE_CRITICAL_TREAD_MM -> GarageStatus.CRITICAL
            tire.treadDepthMm < TIRE_WARNING_TREAD_MM -> GarageStatus.WARNING
            else -> GarageStatus.OK
        }
        val status = maxSeverity(ageStatus, treadStatus)
        val summary = when {
            ageDays > TIRE_MAX_AGE_DAYS -> "Caucho caducado (>5 años) · ${tire.treadDepthMm} mm"
            tire.treadDepthMm < TIRE_WARNING_TREAD_MM -> "Dibujo bajo (${tire.treadDepthMm} mm) · ${tire.recommendedPsi} PSI"
            else -> "Dibujo ${tire.treadDepthMm} mm · ${tire.recommendedPsi} PSI"
        }
        return ComponentEvaluation(
            id = id,
            title = title,
            status = status,
            daysRemaining = daysRemaining,
            summary = summary
        )
    }

    fun evaluateBrakePad(
        id: String,
        title: String,
        pad: BrakePadSpec,
        odometerKm: Int
    ): ComponentEvaluation {
        val kmRemaining = (pad.lastChangeKm + pad.intervalKm) - odometerKm
        val kmStatus = evaluateKmInterval(odometerKm, pad.lastChangeKm, pad.intervalKm)
        val lifeStatus = when {
            pad.remainingLifePct < BRAKE_CRITICAL_REMAINING_PCT -> GarageStatus.CRITICAL
            pad.remainingLifePct <= 35 -> GarageStatus.WARNING
            else -> GarageStatus.OK
        }
        val status = maxSeverity(kmStatus, lifeStatus)
        return ComponentEvaluation(
            id = id,
            title = title,
            status = status,
            kmRemaining = kmRemaining,
            summary = "${pad.compound.label} · ${pad.remainingLifePct}% vida · ${formatKmRemaining(kmRemaining)}"
        )
    }

    fun evaluateEngineOil(
        oil: EngineOilSpec,
        odometerKm: Int,
        todayEpochDay: Long = todayProvider()
    ): ComponentEvaluation {
        val kmRemaining = (oil.lastChangeKm + oil.intervalKm) - odometerKm
        val daysRemaining = if (oil.lastChangeEpochDay > 0L) {
            (oil.lastChangeEpochDay + oil.intervalDays) - todayEpochDay
        } else null
        val kmStatus = evaluateKmInterval(odometerKm, oil.lastChangeKm, oil.intervalKm)
        val timeStatus = evaluateTimeInterval(todayEpochDay, oil.lastChangeEpochDay, oil.intervalDays.toLong())
        val status = maxSeverity(kmStatus, timeStatus)
        return ComponentEvaluation(
            id = "engine_oil",
            title = "Aceite de Motor",
            status = status,
            kmRemaining = kmRemaining,
            daysRemaining = daysRemaining,
            summary = "${oil.viscosity} ${oil.jasoSpec.label} · ${formatKmRemaining(kmRemaining)}"
        )
    }

    fun evaluateOilFilter(
        filter: OilFilterSpec,
        odometerKm: Int,
        todayEpochDay: Long = todayProvider()
    ): ComponentEvaluation {
        val kmRemaining = (filter.lastChangeKm + filter.intervalKm) - odometerKm
        val daysRemaining = if (filter.lastChangeEpochDay > 0L) {
            (filter.lastChangeEpochDay + filter.intervalDays) - todayEpochDay
        } else null
        val status = maxSeverity(
            evaluateKmInterval(odometerKm, filter.lastChangeKm, filter.intervalKm),
            evaluateTimeInterval(todayEpochDay, filter.lastChangeEpochDay, filter.intervalDays.toLong())
        )
        return ComponentEvaluation(
            id = "oil_filter",
            title = "Filtro de Aceite",
            status = status,
            kmRemaining = kmRemaining,
            daysRemaining = daysRemaining,
            summary = "${filter.filterType.label} · ${formatKmRemaining(kmRemaining)}"
        )
    }

    fun evaluateAirFilter(
        filter: AirFilterSpec,
        odometerKm: Int,
        todayEpochDay: Long = todayProvider()
    ): ComponentEvaluation {
        val kmRemaining = (filter.lastServiceKm + filter.intervalKm) - odometerKm
        val daysRemaining = if (filter.lastServiceEpochDay > 0L) {
            (filter.lastServiceEpochDay + filter.intervalDays) - todayEpochDay
        } else null
        val status = maxSeverity(
            evaluateKmInterval(odometerKm, filter.lastServiceKm, filter.intervalKm),
            evaluateTimeInterval(todayEpochDay, filter.lastServiceEpochDay, filter.intervalDays.toLong())
        )
        return ComponentEvaluation(
            id = "air_filter",
            title = "Filtro de Aire",
            status = status,
            kmRemaining = kmRemaining,
            daysRemaining = daysRemaining,
            summary = "${filter.filterType.label} · ${formatKmRemaining(kmRemaining)}"
        )
    }

    fun evaluateFuelFilter(
        filter: FuelFilterSpec,
        odometerKm: Int,
        todayEpochDay: Long = todayProvider()
    ): ComponentEvaluation {
        val kmRemaining = (filter.lastChangeKm + filter.intervalKm) - odometerKm
        val daysRemaining = if (filter.lastChangeEpochDay > 0L) {
            (filter.lastChangeEpochDay + filter.intervalDays) - todayEpochDay
        } else null
        val status = maxSeverity(
            evaluateKmInterval(odometerKm, filter.lastChangeKm, filter.intervalKm),
            evaluateTimeInterval(todayEpochDay, filter.lastChangeEpochDay, filter.intervalDays.toLong())
        )
        return ComponentEvaluation(
            id = "fuel_filter",
            title = "Filtro de Gasolina",
            status = status,
            kmRemaining = kmRemaining,
            daysRemaining = daysRemaining,
            summary = "${filter.filterType.label} · ${formatKmRemaining(kmRemaining)}"
        )
    }

    fun evaluateChainLube(chain: DriveChainSpec, odometerKm: Int): ComponentEvaluation {
        val kmRemaining = (chain.lastLubeKm + chain.lubeIntervalKm) - odometerKm
        val status = if (chain.chainType == ChainType.SEALED_LOW_MAINT) {
            GarageStatus.OK
        } else {
            evaluateKmInterval(odometerKm, chain.lastLubeKm, chain.lubeIntervalKm)
        }
        return ComponentEvaluation(
            id = "chain_lube",
            title = "Engrase de Cadena",
            status = status,
            kmRemaining = kmRemaining,
            summary = "${chain.chainType.label} · ${formatKmRemaining(kmRemaining)}"
        )
    }

    fun evaluateChainKit(chain: DriveChainSpec, odometerKm: Int): ComponentEvaluation {
        val kmRemaining = (chain.lastKitChangeKm + chain.kitIntervalKm) - odometerKm
        val status = evaluateKmInterval(odometerKm, chain.lastKitChangeKm, chain.kitIntervalKm)
        return ComponentEvaluation(
            id = "chain_kit",
            title = "Kit de Arrastre",
            status = status,
            kmRemaining = kmRemaining,
            summary = "${chain.chainType.label} · ${formatKmRemaining(kmRemaining)}"
        )
    }

    fun evaluateSparkPlug(plug: SparkPlugSpec, odometerKm: Int): ComponentEvaluation {
        val kmRemaining = (plug.lastChangeKm + plug.intervalKm) - odometerKm
        val status = evaluateKmInterval(odometerKm, plug.lastChangeKm, plug.intervalKm)
        return ComponentEvaluation(
            id = "spark_plug",
            title = "Bujías",
            status = status,
            kmRemaining = kmRemaining,
            summary = "${plug.plugType.label} · ${formatKmRemaining(kmRemaining)}"
        )
    }

    fun evaluateBearings(bearing: BearingSpec, odometerKm: Int): ComponentEvaluation {
        val kmRemaining = (bearing.lastChangeKm + bearing.intervalKm) - odometerKm
        val status = evaluateKmInterval(odometerKm, bearing.lastChangeKm, bearing.intervalKm)
        return ComponentEvaluation(
            id = "bearings",
            title = "Rodamientos (${bearing.location.label})",
            status = status,
            kmRemaining = kmRemaining,
            summary = formatKmRemaining(kmRemaining)
        )
    }

    fun evaluateBrakeFluid(
        fluid: BrakeFluidSpec,
        todayEpochDay: Long = todayProvider()
    ): ComponentEvaluation {
        val daysRemaining = if (fluid.lastChangeEpochDay > 0L) {
            (fluid.lastChangeEpochDay + fluid.intervalDays) - todayEpochDay
        } else null
        val status = evaluateTimeInterval(todayEpochDay, fluid.lastChangeEpochDay, fluid.intervalDays.toLong())
        return ComponentEvaluation(
            id = "brake_fluid",
            title = "Líquido de Frenos",
            status = status,
            daysRemaining = daysRemaining,
            summary = "${fluid.fluidType.label} · ${formatDaysRemaining(daysRemaining)}"
        )
    }

    fun evaluateBattery(
        battery: BatterySpec,
        todayEpochDay: Long = todayProvider()
    ): ComponentEvaluation {
        val ageDays = if (battery.installedEpochDay > 0L) (todayEpochDay - battery.installedEpochDay).coerceAtLeast(0L) else 0L
        val daysRemaining = BATTERY_CRITICAL_DAYS - ageDays
        val status = when {
            ageDays >= BATTERY_CRITICAL_DAYS -> GarageStatus.CRITICAL
            ageDays >= BATTERY_WARNING_DAYS -> GarageStatus.WARNING
            else -> GarageStatus.OK
        }
        return ComponentEvaluation(
            id = "battery",
            title = "Batería",
            status = status,
            daysRemaining = daysRemaining,
            summary = "${battery.batteryType.label} · ${battery.nominalVoltage}V · ${formatDaysRemaining(daysRemaining)}"
        )
    }

    fun evaluateCoolant(
        coolant: CoolantSpec,
        odometerKm: Int,
        todayEpochDay: Long = todayProvider()
    ): ComponentEvaluation {
        val kmRemaining = (coolant.lastChangeKm + coolant.intervalKm) - odometerKm
        val daysRemaining = if (coolant.lastChangeEpochDay > 0L) {
            (coolant.lastChangeEpochDay + coolant.intervalDays) - todayEpochDay
        } else null
        val status = maxSeverity(
            evaluateKmInterval(odometerKm, coolant.lastChangeKm, coolant.intervalKm),
            evaluateTimeInterval(todayEpochDay, coolant.lastChangeEpochDay, coolant.intervalDays.toLong())
        )
        return ComponentEvaluation(
            id = "coolant",
            title = "Líquido Refrigerante",
            status = status,
            kmRemaining = kmRemaining,
            daysRemaining = daysRemaining,
            summary = "${coolant.technology.label} · ${formatKmRemaining(kmRemaining)}"
        )
    }

    private fun evaluateKmInterval(odometerKm: Int, lastKm: Int, intervalKm: Int): GarageStatus {
        if (intervalKm <= 0) return GarageStatus.OK
        val elapsed = (odometerKm - lastKm).coerceAtLeast(0)
        return when {
            elapsed >= intervalKm -> GarageStatus.CRITICAL
            elapsed * 100 >= intervalKm * 80 -> GarageStatus.WARNING
            else -> GarageStatus.OK
        }
    }

    private fun evaluateTimeInterval(
        todayEpochDay: Long,
        lastEpochDay: Long,
        intervalDays: Long
    ): GarageStatus {
        if (lastEpochDay <= 0L || intervalDays <= 0L) return GarageStatus.OK
        val elapsed = (todayEpochDay - lastEpochDay).coerceAtLeast(0L)
        return when {
            elapsed >= intervalDays -> GarageStatus.CRITICAL
            elapsed * 100L >= intervalDays * 80L -> GarageStatus.WARNING
            else -> GarageStatus.OK
        }
    }

    private fun maxSeverity(a: GarageStatus, b: GarageStatus): GarageStatus =
        if (a.ordinal >= b.ordinal) a else b

    private fun formatKmRemaining(kmRemaining: Int): String =
        if (kmRemaining >= 0) "Faltan $kmRemaining km" else "Vencido por ${-kmRemaining} km"

    private fun formatDaysRemaining(daysRemaining: Long?): String = when {
        daysRemaining == null -> "Sin fecha"
        daysRemaining >= 0L -> "Faltan $daysRemaining días"
        else -> "Vencido por ${-daysRemaining} días"
    }
}
