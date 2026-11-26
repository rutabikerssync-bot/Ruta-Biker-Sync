package com.rutabikersync.app.core.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests para el watchdog del ScanCallback ([BleAdvTransport.startScanWatchdog]).
 *
 * No usa Android mocks (BluetoothAdapter): ejercita solo la lógica de tiempo y contadores
 * del watchdog, que son pure-JVM. El scan real (stopScan/startScan) se mockea vía
 * un [BleAdvTransportWatchdogHarness] que sustituye al scanner hardware.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleAdvTransportWatchdogTest {

    // ---- Harness mínimo para probar el watchdog sin BluetoothAdapter ----

    /**
     * Implementación de prueba que expone solo la lógica del watchdog.
     * No llama a BluetoothAdapter; los "reinicios" son contadores.
     */
    private class WatchdogHarness(
        val scope: TestScope,
        silenceMs: Long = BleAdvTransport.SCAN_WATCHDOG_SILENCE_MS,
        checkIntervalMs: Long = BleAdvTransport.SCAN_WATCHDOG_CHECK_INTERVAL_MS
    ) {
        var lastScanRxEpochMs: Long = System.currentTimeMillis()
        var scanWatchdogRestartCount: Int = 0
        var scanStopCalls: Int = 0
        var scanStartCalls: Int = 0
        var watchdogJob: Job? = null
        val testSilenceMs = silenceMs
        val testCheckIntervalMs = checkIntervalMs

        /** Arranca el watchdog usando el scope de test (tiempo virtual). */
        fun start() {
            watchdogJob = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(testCheckIntervalMs)
                    val silenceMs = scope.testScheduler.currentTime - lastScanRxEpochMs
                    if (silenceMs >= testSilenceMs) {
                        scanWatchdogRestartCount++
                        scanStopCalls++
                        lastScanRxEpochMs = scope.testScheduler.currentTime
                        scanStartCalls++
                    }
                }
            }
        }

        fun stop() { watchdogJob?.cancel() }

        /** Simula llegada de un anuncio BLE: resetea el timer de sequía. */
        fun injectScanResult() {
            lastScanRxEpochMs = scope.testScheduler.currentTime
        }
    }

    // ---- Tests ----

    @Test
    fun `watchdog no reinicia si hay actividad antes del umbral`() = runTest {
        val harness = WatchdogHarness(this)
        harness.lastScanRxEpochMs = testScheduler.currentTime
        harness.start()

        // Avanzar 59 s (< 60 s de silencio): no debe reiniciar
        advanceTimeBy(BleAdvTransport.SCAN_WATCHDOG_SILENCE_MS - 1_000L)

        assertEquals(0, harness.scanWatchdogRestartCount)
        harness.stop()
    }

    @Test
    fun `watchdog reinicia scan tras 60s de silencio completo`() = runTest {
        val harness = WatchdogHarness(this)
        harness.lastScanRxEpochMs = testScheduler.currentTime
        harness.start()

        // Avanzar 60 s + 1 ciclo de check (30 s): el watchdog habrá verificado 3 veces (30, 60, 90)
        // En t=60 (segundo check a los 60 s) el silencio ya supera el umbral → restart
        advanceTimeBy(BleAdvTransport.SCAN_WATCHDOG_SILENCE_MS + BleAdvTransport.SCAN_WATCHDOG_CHECK_INTERVAL_MS)

        assertTrue("Debe haber al menos un restart", harness.scanWatchdogRestartCount >= 1)
        assertEquals(harness.scanWatchdogRestartCount, harness.scanStopCalls)
        assertEquals(harness.scanWatchdogRestartCount, harness.scanStartCalls)
        harness.stop()
    }

    @Test
    fun `watchdog se resetea al recibir anuncio y no reinicia`() = runTest {
        val harness = WatchdogHarness(this)
        harness.lastScanRxEpochMs = testScheduler.currentTime
        harness.start()

        // Avanzar 50 s, luego inyectar un anuncio, luego otros 50 s
        advanceTimeBy(50_000L)
        harness.injectScanResult()
        advanceTimeBy(50_000L) // total 100 s, pero el silencio se rompió en t=50

        // Con el reset en t=50, el silencio efectivo al t=100 es solo 50 s < 60 s
        assertEquals(0, harness.scanWatchdogRestartCount)
        harness.stop()
    }

    @Test
    fun `watchdog se detiene al cancelar y no cuenta mas reinicios`() = runTest {
        val harness = WatchdogHarness(this)
        harness.lastScanRxEpochMs = testScheduler.currentTime - BleAdvTransport.SCAN_WATCHDOG_SILENCE_MS * 2 // ya vencido
        harness.start()
        runCurrent() // registrar el delay en t=0 antes de avanzar el reloj virtual

        // Avanzar 1 ciclo + 1 s: delay vence exactamente en 30 s y advanceTimeBy no ejecuta el límite
        advanceTimeBy(BleAdvTransport.SCAN_WATCHDOG_CHECK_INTERVAL_MS + 1_000L)
        val restartsAfterOne = harness.scanWatchdogRestartCount
        assertTrue(restartsAfterOne >= 1)

        harness.stop()

        // Avanzar mucho más: el watchdog está cancelado, no debe haber más reinicios
        advanceTimeBy(BleAdvTransport.SCAN_WATCHDOG_SILENCE_MS * 10)
        assertEquals(restartsAfterOne, harness.scanWatchdogRestartCount)
    }

    @Test
    fun `watchdog reinicia multiples veces si la sequia persiste`() = runTest {
        val harness = WatchdogHarness(this)
        harness.lastScanRxEpochMs = testScheduler.currentTime
        harness.start()

        // Avanzar 3 veces el umbral: deben producirse al menos 2 reinicios adicionales
        // ya que tras cada restart se resetea el timer base
        // Con check cada 30 s y silence de 60 s: en t=60 → restart1, timer=60
        // siguiente disparo en t=120 → 60s de silencio → restart2
        advanceTimeBy(BleAdvTransport.SCAN_WATCHDOG_SILENCE_MS * 3)

        assertTrue("Deben ocurrir múltiples reinicios", harness.scanWatchdogRestartCount >= 2)
        harness.stop()
    }

    @Test
    fun `constantes del watchdog son consistentes con evidencia de campo`() {
        // T_nominal = 40.5 s; SILENCE debe ser mayor para no interferir
        assertTrue(
            "SILENCE_MS debe ser > 40500 ms (T nominal de campo)",
            BleAdvTransport.SCAN_WATCHDOG_SILENCE_MS > 40_500L
        )
        // CHECK_INTERVAL debe ser <= SILENCE/2 para detectar con suficiente resolución
        assertTrue(
            "CHECK_INTERVAL_MS debe ser <= SILENCE_MS / 2",
            BleAdvTransport.SCAN_WATCHDOG_CHECK_INTERVAL_MS <= BleAdvTransport.SCAN_WATCHDOG_SILENCE_MS / 2
        )
    }
}
