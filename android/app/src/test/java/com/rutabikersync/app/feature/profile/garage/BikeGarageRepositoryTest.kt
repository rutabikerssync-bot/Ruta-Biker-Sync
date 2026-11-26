package com.rutabikersync.app.feature.profile.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BikeGarageRepositoryTest {

    private val today = LocalDate.of(2026, 11, 26).toEpochDay()
    private val repo = BikeGarageRepository(context = null, todayProvider = { today })

    @Test
    fun `neumatico dispara CRITICAL si supera 5 anos aunque tenga buen dibujo y WARNING si dibujo menor a 2mm`() {
        val expiredTire = TireSpec(
            position = TirePosition.FRONT,
            mountedEpochDay = today - (365L * 5L + 10L),
            treadDepthMm = 4.5
        )
        val expiredEval = repo.evaluateTire("t1", "Delantero", expiredTire, today)
        assertEquals(GarageStatus.CRITICAL, expiredEval.status)

        val lowTreadTire = TireSpec(
            position = TirePosition.REAR,
            mountedEpochDay = today - 100L,
            treadDepthMm = 1.7
        )
        val lowTreadEval = repo.evaluateTire("t2", "Trasero", lowTreadTire, today)
        assertEquals(GarageStatus.WARNING, lowTreadEval.status)
    }

    @Test
    fun `frenos disparan WARNING al 80 porciento de intervalo y CRITICAL si vida menor a 20 porciento`() {
        val pad80Pct = BrakePadSpec(
            position = BrakePosition.FRONT,
            lastChangeKm = 0,
            intervalKm = 15_000,
            remainingLifePct = 40
        )
        assertEquals(
            GarageStatus.WARNING,
            repo.evaluateBrakePad("b1", "Del.", pad80Pct, odometerKm = 12_000).status
        )

        val padLowLife = BrakePadSpec(
            position = BrakePosition.REAR,
            lastChangeKm = 10_000,
            intervalKm = 15_000,
            remainingLifePct = 18
        )
        assertEquals(
            GarageStatus.CRITICAL,
            repo.evaluateBrakePad("b2", "Tras.", padLowLife, odometerKm = 11_000).status
        )
    }

    @Test
    fun `aceite y filtros evaluan el criterio mas severo entre km y fecha`() {
        val oilByDate = EngineOilSpec(
            lastChangeKm = 1_000,
            lastChangeEpochDay = today - 200L,
            intervalKm = 3_000,
            intervalDays = 180
        )
        val oilEval = repo.evaluateEngineOil(oilByDate, odometerKm = 1_500, todayEpochDay = today)
        assertEquals(GarageStatus.CRITICAL, oilEval.status)

        val oilFilterWarn = OilFilterSpec(
            lastChangeKm = 10_000,
            lastChangeEpochDay = today - 30L,
            intervalKm = 3_000,
            intervalDays = 180
        )
        assertEquals(
            GarageStatus.WARNING,
            repo.evaluateOilFilter(oilFilterWarn, odometerKm = 12_500, todayEpochDay = today).status
        )

        val airFilterCrit = AirFilterSpec(
            filterType = AirFilterType.OILED_FOAM,
            lastServiceKm = 0,
            lastServiceEpochDay = today - 10L,
            intervalKm = 5_000
        )
        assertEquals(
            GarageStatus.CRITICAL,
            repo.evaluateAirFilter(airFilterCrit, odometerKm = 5_200, todayEpochDay = today).status
        )

        val fuelFilterWarn = FuelFilterSpec(
            filterType = FuelFilterType.INLINE_EXTERNAL,
            lastChangeKm = 0,
            lastChangeEpochDay = today - 20L,
            intervalKm = 10_000
        )
        assertEquals(
            GarageStatus.WARNING,
            repo.evaluateFuelFilter(fuelFilterWarn, odometerKm = 8_500, todayEpochDay = today).status
        )
    }

    @Test
    fun `kit de arrastre recuerda engrase cada 500 km y permite resetear odometro o mantener historico`() {
        val state = BikeGarageState(
            odometerKm = 20_500,
            driveChain = DriveChainSpec(
                chainType = ChainType.X_RING,
                lastKitChangeKm = 0,
                kitIntervalKm = 20_000,
                lastLubeKm = 19_950,
                lubeIntervalKm = 500
            )
        )
        assertEquals(
            GarageStatus.CRITICAL,
            repo.evaluateChainLube(state.driveChain, state.odometerKm).status
        )

        val kept = repo.recordChainKitReplacement(state, resetOdometerToZero = false)
        assertEquals(20_500, kept.odometerKm)
        assertEquals(20_500, kept.driveChain.lastKitChangeKm)

        val reset = repo.recordChainKitReplacement(state, resetOdometerToZero = true)
        assertEquals(0, reset.odometerKm)
        assertEquals(0, reset.driveChain.lastKitChangeKm)
        assertEquals(0, reset.driveChain.lastLubeKm)
    }

    @Test
    fun `liquido de frenos vence a 2 anos bateria a 2 y 4 anos y refrigerante es condicional`() {
        val brakeFluid = BrakeFluidSpec(lastChangeEpochDay = today - 730L)
        assertEquals(GarageStatus.CRITICAL, repo.evaluateBrakeFluid(brakeFluid, today).status)

        val batteryWarn = BatterySpec(installedEpochDay = today - 750L)
        assertEquals(GarageStatus.WARNING, repo.evaluateBattery(batteryWarn, today).status)

        val batteryCrit = BatterySpec(installedEpochDay = today - 1465L)
        assertEquals(GarageStatus.CRITICAL, repo.evaluateBattery(batteryCrit, today).status)

        val airCooledState = BikeGarageState(coolingType = CoolingType.AIR_OIL)
        val airCooledEvals = repo.evaluateAll(airCooledState)
        assertFalse(airCooledEvals.any { it.id == "coolant" })

        val liquidCooledState = BikeGarageState(coolingType = CoolingType.LIQUID)
        val liquidCooledEvals = repo.evaluateAll(liquidCooledState)
        assertTrue(liquidCooledEvals.any { it.id == "coolant" })
    }

    @Test
    fun `averias cambian de estado y gastos calculan costo por km`() {
        var state = BikeGarageState(odometerKm = 1_000, odometerStartKm = 0)
        state = repo.addFault(state, FaultSystem.ELECTRICAL, FaultSeverity.MODERATE, "Falla en regulador")
        assertEquals(1, state.faults.size)
        assertEquals(FaultStatus.OPEN, state.faults.first().status)

        state = repo.updateFaultStatus(state, state.faults.first().id, FaultStatus.RESOLVED)
        assertEquals(FaultStatus.RESOLVED, state.faults.first().status)

        state = repo.addExpense(state, ExpenseCategory.MAINTENANCE, 50.0, "Cambio aceite y filtro")
        assertEquals(0.05, repo.costPerKmUsd(state), 0.0001)
    }

    @Test
    fun `addRideDistanceKm suma km de rodada al odometro y actualiza semaforos y subcampos editados`() {
        var state = BikeGarageState(
            odometerKm = 2_400,
            engineOil = EngineOilSpec(
                brand = "Motul",
                viscosity = "10W-50",
                baseType = OilBaseType.FULL_SYNTHETIC,
                jasoSpec = OilJasoSpec.JASO_MA2,
                lastChangeKm = 0,
                lastChangeEpochDay = today,
                intervalKm = 3_000
            ),
            frontTire = TireSpec(
                position = TirePosition.FRONT,
                brand = "Pirelli",
                model = "Angel GT",
                construction = TireConstruction.TUBELESS,
                dotCode = "3424",
                recommendedPsi = 33,
                treadDepthMm = 4.2,
                mountedEpochDay = today
            )
        )

        // Al sumar 200 km de rodada, llega a 2,600 km (>80% de 3,000 km -> WARNING)
        state = repo.addRideDistanceKm(state, 200)
        assertEquals(2_600, state.odometerKm)
        val oilEval = repo.evaluateEngineOil(state.engineOil, state.odometerKm, today)
        assertEquals(GarageStatus.WARNING, oilEval.status)
        assertTrue(oilEval.summary.contains("10W-50"))
        assertTrue(oilEval.summary.contains("JASO MA2"))

        // Distancia cero o negativa no altera el odómetro
        val unchanged = repo.addRideDistanceKm(state, 0)
        assertEquals(2_600, unchanged.odometerKm)
        assertEquals("3424", state.frontTire.dotCode)
        assertEquals(33, state.frontTire.recommendedPsi)
    }
}
