package com.rutabikersync.app.core.audio

import android.media.ToneGenerator
import com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent

/** Tupla audible completa de una señal: tono, pulsos, duración de pulso y gap. */
data class SignalToneTuple(
    val toneType: Int,
    val pulseCount: Int,
    val pulseDurationMs: Long,
    val gapMs: Long
)

/**
 * Catálogo de las 11 señales acústicas de la app (P1/T2). Documenta la tupla
 * completa de cada una y garantiza que no existan dos señales con el mismo
 * patrón. Los códigos de wire (`ConvoySignalKind.code` y
 * [RideLifecycleEvent.code]) NO se modifican: este catálogo solo gobierna sonido.
 *
 * | Señal                 | Tono        | Pulsos | Duración | Gap  |
 * |-----------------------|-------------|--------|----------|------|
 * | OBSTACLE              | NACK        | 1      | 300 ms   | 100  |
 * | REDUCE_SPEED          | BEEP2       | 2      | 100 ms   | 60   |
 * | REGROUP               | BEEP        | 2      | 220 ms   | 140  |
 * | GAS_STOP              | ACK         | 1      | 520 ms   | 120  |
 * | POLICE_CHECKPOINT     | PROMPT      | 3      | 90 ms    | 60   |
 * | START_OUTBOUND        | BEEP        | 3      | 160 ms   | 90   |
 * | ARRIVE_DESTINATION    | ACK         | 2      | 400 ms   | 100  |
 * | START_RETURN          | BEEP2       | 3      | 120 ms   | 80   |
 * | COMPLETE_RIDE         | PROMPT      | 2      | 200 ms   | 100  |
 * | SAFE_HOME             | ACK         | 2      | 250 ms   | 100  |
 * | SOS_LOCAL             | BEEP        | 3      | 150 ms   | 250  |
 *
 * Nota: `SOS_LOCAL` resume el identificador de la secuencia Morse `· — ·`
 * (duraciones reales 150/450/1500 ms, gaps 100/250/1500 ms) reproducida por
 * `SosTonePlayer`; su tupla no representa un beep simple.
 */
object ConvoySignalToneCatalog {

    val quickSignals: Map<ConvoySignalKind, SignalToneTuple> =
        ConvoySignalKind.entries.associateWith {
            SignalToneTuple(it.toneType, it.pulseCount, it.pulseDurationMs, it.gapMs)
        }

    val lifecycleEvents: Map<RideLifecycleEvent, SignalToneTuple> = linkedMapOf(
        RideLifecycleEvent.START_OUTBOUND to SignalToneTuple(
            ToneGenerator.TONE_PROP_BEEP, 3, 160L, 90L
        ),
        RideLifecycleEvent.ARRIVE_DESTINATION to SignalToneTuple(
            ToneGenerator.TONE_PROP_ACK, 2, 400L, 100L
        ),
        RideLifecycleEvent.START_RETURN to SignalToneTuple(
            ToneGenerator.TONE_PROP_BEEP2, 3, 120L, 80L
        ),
        RideLifecycleEvent.COMPLETE_RIDE to SignalToneTuple(
            ToneGenerator.TONE_PROP_PROMPT, 2, 200L, 100L
        ),
        RideLifecycleEvent.SAFE_HOME to SignalToneTuple(
            ToneGenerator.TONE_PROP_ACK, 2, 250L, 100L
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
