package com.rutabikersync.app.feature.profile.garage

import kotlinx.serialization.Serializable

@Serializable
enum class CoolingType(val label: String) {
    AIR_OIL("Aire / Aceite"),
    LIQUID("Líquida")
}

@Serializable
enum class GarageStatus(val label: String) {
    OK("Al día"),
    WARNING("Próximo"),
    CRITICAL("Crítico")
}

@Serializable
enum class TirePosition(val label: String) {
    FRONT("Delantero"),
    REAR("Trasero")
}

@Serializable
enum class TireConstruction(val label: String) {
    TUBELESS("Tubeless (Sin cámara)"),
    TUBE_TYPE("Con cámara")
}

@Serializable
enum class BrakePosition(val label: String) {
    FRONT("Delantero"),
    REAR("Trasero")
}

@Serializable
enum class BrakePadCompound(val label: String) {
    ORGANIC("Orgánicas (Resina)"),
    SEMI_METALLIC("Semimetálicas"),
    SINTERED("Sinterizadas (HH)"),
    CERAMIC("Cerámicas")
}

@Serializable
enum class OilBaseType(val label: String) {
    MINERAL("Mineral"),
    SEMI_SYNTHETIC("Semisintético"),
    FULL_SYNTHETIC("100% Sintético")
}

@Serializable
enum class OilJasoSpec(val label: String) {
    JASO_MA("JASO MA"),
    JASO_MA2("JASO MA2"),
    JASO_MB("JASO MB")
}

@Serializable
enum class OilFilterType(val label: String) {
    CARTRIDGE("Cartucho / Papel"),
    SPIN_ON("Blindado / Rosca"),
    WASHABLE_MESH("Malla Lavable")
}

@Serializable
enum class AirFilterType(val label: String) {
    PAPER_DRY("Papel / Seco"),
    OILED_FOAM("Espuma Aceitada"),
    HIGH_FLOW_WASHABLE("Alto Flujo Lavable")
}

@Serializable
enum class FuelFilterType(val label: String) {
    INLINE_EXTERNAL("En línea / Externo"),
    IN_TANK_PUMP("Sumergido / Bomba"),
    WASHABLE("Lavable")
}

@Serializable
enum class ChainType(val label: String) {
    O_RING("O-Ring"),
    X_RING("X-Ring"),
    SEALED_LOW_MAINT("Sin mantenimiento")
}

@Serializable
enum class SparkPlugType(val label: String, val defaultIntervalKm: Int) {
    COPPER("Cobre", 5_000),
    PLATINUM("Platino", 8_000),
    IRIDIUM("Iridio", 10_000)
}

@Serializable
enum class BrakeFluidType(val label: String) {
    DOT_3("DOT 3"),
    DOT_4("DOT 4"),
    DOT_5_1("DOT 5.1")
}

@Serializable
enum class BatteryType(val label: String) {
    LEAD_ACID("Plomo-Ácido"),
    AGM("AGM"),
    LITHIUM("Litio")
}

@Serializable
enum class CoolantTechnology(val label: String) {
    OAT("Orgánico (OAT)"),
    HOAT("Híbrido (HOAT)"),
    IAT("Inorgánico (IAT)")
}

@Serializable
enum class BearingLocation(val label: String) {
    FRONT_WHEEL("Rueda Delantera"),
    REAR_WHEEL("Rueda Trasera"),
    STEERING_HEAD("Dirección")
}

@Serializable
enum class FaultSystem(val label: String) {
    ENGINE("Motor"),
    ELECTRICAL("Eléctrico"),
    BRAKES("Frenos"),
    TRANSMISSION("Transmisión"),
    SUSPENSION("Suspensión"),
    TIRES("Neumáticos"),
    OTHER("Otro")
}

@Serializable
enum class FaultSeverity(val label: String) {
    LOW("Leve"),
    MODERATE("Moderada"),
    CRITICAL("Crítica")
}

@Serializable
enum class FaultStatus(val label: String) {
    OPEN("Abierta"),
    IN_WORKSHOP("En taller"),
    RESOLVED("Resuelta")
}

@Serializable
enum class ExpenseCategory(val label: String) {
    MAINTENANCE("Mantenimiento"),
    PARTS("Repuestos"),
    TIRES("Neumáticos"),
    REPAIR("Avería / Taller"),
    OTHER("Otro")
}

@Serializable
data class TireSpec(
    val position: TirePosition,
    val brand: String = "",
    val model: String = "",
    val construction: TireConstruction = TireConstruction.TUBELESS,
    val dotCode: String = "",
    val mountedEpochDay: Long = 0L,
    val mountedKm: Int = 0,
    val recommendedPsi: Int = if (position == TirePosition.FRONT) 32 else 36,
    val treadDepthMm: Double = 5.0
)

@Serializable
data class BrakePadSpec(
    val position: BrakePosition,
    val compound: BrakePadCompound = BrakePadCompound.SINTERED,
    val remainingLifePct: Int = 100,
    val lastChangeKm: Int = 0,
    val intervalKm: Int = 15_000
)

@Serializable
data class EngineOilSpec(
    val brand: String = "",
    val viscosity: String = "10W-40",
    val baseType: OilBaseType = OilBaseType.SEMI_SYNTHETIC,
    val jasoSpec: OilJasoSpec = OilJasoSpec.JASO_MA2,
    val lastChangeKm: Int = 0,
    val lastChangeEpochDay: Long = 0L,
    val intervalKm: Int = 3_000,
    val intervalDays: Int = 180
)

@Serializable
data class OilFilterSpec(
    val brandOrRef: String = "",
    val filterType: OilFilterType = OilFilterType.CARTRIDGE,
    val lastChangeKm: Int = 0,
    val lastChangeEpochDay: Long = 0L,
    val intervalKm: Int = 3_000,
    val intervalDays: Int = 180
)

@Serializable
data class AirFilterSpec(
    val brandOrRef: String = "",
    val filterType: AirFilterType = AirFilterType.PAPER_DRY,
    val lastServiceKm: Int = 0,
    val lastServiceEpochDay: Long = 0L,
    val intervalKm: Int = 8_000,
    val intervalDays: Int = 365
)

@Serializable
data class FuelFilterSpec(
    val brandOrRef: String = "",
    val filterType: FuelFilterType = FuelFilterType.INLINE_EXTERNAL,
    val lastChangeKm: Int = 0,
    val lastChangeEpochDay: Long = 0L,
    val intervalKm: Int = 12_000,
    val intervalDays: Int = 730
)

@Serializable
data class DriveChainSpec(
    val chainType: ChainType = ChainType.O_RING,
    val lastKitChangeKm: Int = 0,
    val kitIntervalKm: Int = 20_000,
    val lastLubeKm: Int = 0,
    val lubeIntervalKm: Int = 500
)

@Serializable
data class SparkPlugSpec(
    val plugType: SparkPlugType = SparkPlugType.IRIDIUM,
    val lastChangeKm: Int = 0,
    val intervalKm: Int = plugType.defaultIntervalKm
)

@Serializable
data class BrakeFluidSpec(
    val fluidType: BrakeFluidType = BrakeFluidType.DOT_4,
    val lastChangeEpochDay: Long = 0L,
    val intervalDays: Int = 730
)

@Serializable
data class BatterySpec(
    val batteryType: BatteryType = BatteryType.AGM,
    val installedEpochDay: Long = 0L,
    val nominalVoltage: Double = 12.6
)

@Serializable
data class CoolantSpec(
    val brandOrType: String = "",
    val technology: CoolantTechnology = CoolantTechnology.OAT,
    val lastChangeKm: Int = 0,
    val lastChangeEpochDay: Long = 0L,
    val intervalKm: Int = 20_000,
    val intervalDays: Int = 730
)

@Serializable
data class BearingSpec(
    val location: BearingLocation = BearingLocation.FRONT_WHEEL,
    val lastChangeKm: Int = 0,
    val intervalKm: Int = 18_000
)

@Serializable
data class BikeFaultEntry(
    val id: String,
    val epochDay: Long,
    val odometerKm: Int,
    val system: FaultSystem,
    val severity: FaultSeverity,
    val description: String,
    val status: FaultStatus = FaultStatus.OPEN
)

@Serializable
data class BikeExpenseEntry(
    val id: String,
    val epochDay: Long,
    val odometerKm: Int,
    val category: ExpenseCategory,
    val amountUsd: Double,
    val note: String = ""
)

@Serializable
data class BikeGarageState(
    val odometerKm: Int = 0,
    val odometerStartKm: Int = 0,
    val coolingType: CoolingType = CoolingType.AIR_OIL,
    val frontTire: TireSpec = TireSpec(TirePosition.FRONT),
    val rearTire: TireSpec = TireSpec(TirePosition.REAR),
    val frontBrakePad: BrakePadSpec = BrakePadSpec(BrakePosition.FRONT),
    val rearBrakePad: BrakePadSpec = BrakePadSpec(BrakePosition.REAR),
    val engineOil: EngineOilSpec = EngineOilSpec(),
    val oilFilter: OilFilterSpec = OilFilterSpec(),
    val airFilter: AirFilterSpec = AirFilterSpec(),
    val fuelFilter: FuelFilterSpec = FuelFilterSpec(),
    val driveChain: DriveChainSpec = DriveChainSpec(),
    val sparkPlug: SparkPlugSpec = SparkPlugSpec(),
    val brakeFluid: BrakeFluidSpec = BrakeFluidSpec(),
    val battery: BatterySpec = BatterySpec(),
    val coolant: CoolantSpec = CoolantSpec(),
    val bearings: BearingSpec = BearingSpec(),
    val faults: List<BikeFaultEntry> = emptyList(),
    val expenses: List<BikeExpenseEntry> = emptyList()
)

data class ComponentEvaluation(
    val id: String,
    val title: String,
    val status: GarageStatus,
    val kmRemaining: Int? = null,
    val daysRemaining: Long? = null,
    val summary: String,
    val visible: Boolean = true
)
