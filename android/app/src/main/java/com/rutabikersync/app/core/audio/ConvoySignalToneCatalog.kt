package com.rutabikersync.app.core.audio

import android.media.ToneGenerator
import com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent

/** Tupla audible completa de una señal: tono, pulsos, duración de pulso y gap. */
data class SignalToneTuple(
    val toneType: Int,
    val pulseCount: Int,
    val pulseDurationMs: Long,
    val gapMs: Long
) {
    /** Duración total del patrón: pulsos + gaps intermedios. */
    val totalDurationMs: Long
        get() = pulseCount * pulseDurationMs + (pulseCount - 1) * gapMs
}

/**
 * Catálogo de las 11 señales acústicas de la app (P1/T2). Documenta la tupla
 * completa de cada una y garantiza que no existan dos señales con el mismo
 * patrón. Los códigos de wire (`ConvoySignalKind.code` y
 * [RideLifecycleEvent.code]) NO se modifican: este catálogo solo gobierna sonido.
 *
 * | Señal                 | Tono        | Pulsos | Duración | Gap  |
 * |-----------------------|-------------|--------|----------|------|
 * Los 10 tonos de mensaje duran exactamente 6000 ms en total (pulsos + gaps).
 * El SOS se reproduce en bucle mientras esté activo: duración indefinida.
 *
 * | Señal                 | Tono        | Pulsos | Duración | Gap  | Total |
 * |-----------------------|-------------|--------|----------|------|-------|
 * | OBSTACLE              | NACK        | 1      | 6000 ms  | 0    | 6000  |
 * | REDUCE_SPEED          | BEEP2       | 2      | 2900 ms  | 200  | 6000  |
 * | REGROUP               | BEEP        | 2      | 2800 ms  | 400  | 6000  |
 * | GAS_STOP              | ACK         | 1      | 6000 ms  | 0    | 6000  |
 * | POLICE_CHECKPOINT     | PROMPT      | 3      | 1900 ms  | 150  | 6000  |
 * | START_OUTBOUND        | BEEP        | 3      | 1800 ms  | 300  | 6000  |
 * | ARRIVE_DESTINATION    | ACK         | 2      | 2800 ms  | 400  | 6000  |
 * | START_RETURN          | BEEP2       | 3      | 1800 ms  | 300  | 6000  |
 * | COMPLETE_RIDE         | PROMPT      | 2      | 2600 ms  | 800  | 6000  |
 * | SAFE_HOME             | ACK         | 2      | 2700 ms  | 600  | 6000  |
 * | SOS_LOCAL             | BEEP        | 3      | 150 ms   | 250  | indefinido (bucle) |
 *
 * Nota: `SOS_LOCAL` resume el identificador de la secuencia Morse `· — ·`
 * (duraciones reales 150/450/1500 ms, gaps 100/250/1500 ms) reproducida por
 * `SosTonePlayer`; su tupla no representa un beep simple.
 *
 * Nota informativa (2026-11-22): la activación de audio se mantiene en 15 s.
 * Ese valor no es la duración de un tono y no se modifica aquí.
 */
object ConvoySignalToneCatalog {

    val quickSignals: Map<ConvoySignalKind, SignalToneTuple> =
        ConvoySignalKind.entries.associateWith {
            SignalToneTuple(it.toneType, it.pulseCount, it.pulseDurationMs, it.gapMs)
        }

    val lifecycleEvents: Map<RideLifecycleEvent, SignalToneTuple> = linkedMapOf(
        RideLifecycleEvent.START_OUTBOUND to SignalToneTuple(
            ToneGenerator.TONE_PROP_BEEP, 3, 1_800L, 300L
        ),
        RideLifecycleEvent.ARRIVE_DESTINATION to SignalToneTuple(
            ToneGenerator.TONE_PROP_ACK, 2, 2_800L, 400L
        ),
        RideLifecycleEvent.START_RETURN to SignalToneTuple(
            ToneGenerator.TONE_PROP_BEEP2, 3, 1_800L, 300L
        ),
        RideLifecycleEvent.COMPLETE_RIDE to SignalToneTuple(
            ToneGenerator.TONE_PROP_PROMPT, 2, 2_600L, 800L
        ),
        RideLifecycleEvent.SAFE_HOME to SignalToneTuple(
            ToneGenerator.TONE_PROP_ACK, 2, 2_700L, 600L
        )
    )

    val sosLocal: SignalToneTuple = SignalToneTuple(
        ToneGenerator.TONE_PROP_BEEP, 3, 150L, 250L
    )

    /** Las 11 señales, indexadas por un nombre estable para el test de unicidad. */
    val all: Map<String, SignalToneTuple> =
        quickSignals.mapKeys { (kind, _) -> kind.name } +
            lifecycleEvents.mapKeys { (event, _) -> event.name } +
            mapOf("SOS_LOCAL" to sosLocal)

    fun tupleFor(kind: ConvoySignalKind): SignalToneTuple = quickSignals.getValue(kind)

    fun tupleFor(event: RideLifecycleEvent): SignalToneTuple = lifecycleEvents.getValue(event)

    /** Único punto que dispara el patrón audible de un evento de ciclo. */
    fun playLifecycle(event: RideLifecycleEvent) {
        val tuple = tupleFor(event)
        ConvoySignalAudioHub.playPattern(
            tuple.toneType,
            tuple.pulseCount,
            tuple.pulseDurationMs,
            tuple.gapMs
        )
    }
}
