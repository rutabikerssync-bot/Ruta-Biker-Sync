package com.rutabikersync.app.feature.livemap

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttCabinaGateTest {

    @Test
    fun `PTT muestra mensaje correcto cuando RideService no esta activo`() {
        var started = false
        val message = PttCabinaGate.resolveAction(
            isRecording = false,
            isRideMeshActive = false,
            onStop = {},
            onStart = { started = true }
        )

        assertFalse(started)
        assertEquals("Inicia la rodada para activar el PTT", message)
    }

    @Test
    fun `PTT inicia transmision cuando RideService esta activo`() {
        var started = false
        val message = PttCabinaGate.resolveAction(
            isRecording = false,
            isRideMeshActive = true,
            onStop = {},
            onStart = { started = true }
        )

        assertTrue(started)
        assertNull(message)
    }

    @Test
    fun `orientacion por defecto mantiene norte fijo 0 grados en ambos telefonos`() = runTest {
        val controllerA = LiveMapController(scope = backgroundScope)
        val controllerB = LiveMapController(scope = backgroundScope)

        controllerA.updateLocalRiderPose(10.34, -67.04, speedKmh = 35, bearingDegrees = 42f, batteryPercent = 90)
        controllerB.updateLocalRiderPose(10.34, -67.04, speedKmh = 35, bearingDegrees = 180f, batteryPercent = 90)

        assertTrue(controllerA.uiState.value.isNorthLocked)
        assertTrue(controllerB.uiState.value.isNorthLocked)
        assertEquals(0f, controllerA.uiState.value.navigationBearingDegrees, 0.01f)
        assertEquals(0f, controllerB.uiState.value.navigationBearingDegrees, 0.01f)

        controllerB.toggleNorthLock(false)
        controllerB.updateLocalRiderPose(10.34, -67.04, speedKmh = 35, bearingDegrees = 180f, batteryPercent = 90)
        assertFalse(controllerB.uiState.value.isNorthLocked)
        assertEquals(180f, controllerB.uiState.value.navigationBearingDegrees, 0.01f)
    }
}
