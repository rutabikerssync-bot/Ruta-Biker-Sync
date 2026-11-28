package com.rutabikersync.app.core.mesh.audio

import android.util.Log
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Arrays

/**
 * Dueño único del motor PTT de la app (P0.4).
 *
 * Antes existían dos instancias: una en `LiveMapScreen` y otra en `BleMeshService`, cada una
 * con su propio `JitterBuffer` y su propia suscripción `onVoiceReceived`. Esto podía producir
 * doble reproducción y competencia por AudioFocus. El holder centraliza creación, recepción
 * y liberación, y usa siempre la clave derivada del manifiesto activo.
 *
 * Los consumidores deben leer `engine`/`engineFlow` en el momento de usar el motor, nunca
 * guardar una referencia propia: una cabina y un servicio pueden reinstalar el holder.
 */
object PttAudioEngineHolder {
    private const val TAG = "PttAudioEngineHolder"

    // Scope propio de aplicación: sobrevive al Composable y al ciclo de un Service.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _engineFlow = MutableStateFlow<PttAudioEngine?>(null)
    val engineFlow: StateFlow<PttAudioEngine?> = _engineFlow.asStateFlow()

    @Volatile
    var engine: PttAudioEngine? = null
        private set

    @Volatile
    private var relayEngine: MeshRelayEngine? = null
    private var installedKey: ByteArray? = null
    private var installedClubNetworkId: UInt? = null
    private var installedShortId: UShort? = null
    private var unsubscribe: (() -> Unit)? = null
    private val voiceSentListeners = LinkedHashSet<((VoicePacket) -> Unit)>()

    /**
     * Instala (o reemplaza) el motor único. Si no hay clave real, no se crea motor:
     * fail-closed, sin clave demo. Devuelve null en ese caso.
     *
     * Una reinstalación con los mismos identificadores, relay y clave es idempotente: no
     * libera un motor que pueda estar transmitiendo, solo actualiza el callback de envío.
     */
    @Synchronized
    fun install(
        clubNetworkId: UInt,
        localShortId: UShort,
        activeRelayEngine: MeshRelayEngine?,
        audioFocusController: AudioFocusController,
        pttKey: ByteArray?,
        onVoicePacketSent: ((VoicePacket) -> Unit)? = null
    ): PttAudioEngine? {
        val current = engine
        if (current != null && pttKey != null &&
            installedClubNetworkId == clubNetworkId &&
            installedShortId == localShortId &&
            relayEngine === activeRelayEngine &&
            installedKey?.contentEquals(pttKey) == true
        ) {
            if (onVoicePacketSent != null) voiceSentListeners.add(onVoicePacketSent)
            return current
        }

        releaseLocked()
        if (pttKey == null || pttKey.size != 32) {
            Log.w(TAG, "FAIL_CLOSED_PTT_KEY_UNAVAILABLE: no se crea motor sin clave del manifiesto")
            return null
        }
        val keyCopy = pttKey.copyOf()
        if (onVoicePacketSent != null) voiceSentListeners.add(onVoicePacketSent)
        val created = PttAudioEngine(
            scope = scope,
            clubNetworkId = clubNetworkId,
            localShortId = localShortId,
            clubPttKey = keyCopy,
            audioFocusController = audioFocusController,
            onSendVoicePacket = { packet ->
                // Usar SIEMPRE el relay vigente, no el que existía al instalar: una rodada
                // nueva puede sustituirlo y el motor sobrevive al cambio de suscripción.
                val currentRelay = relayEngine
                if (currentRelay == null) {
                    Log.w(TAG, "VoicePacketTx descartado: no hay relayEngine activo")
                } else {
                    when (currentRelay.emitVoicePacket(packet)) {
                        true -> synchronized(voiceSentListeners) { voiceSentListeners.toList() }
                            .forEach { listener -> listener(packet) }
                        false -> Log.w(
                            TAG,
                            "VoicePacketTx descartado: relayEngine rechazó el paquete (backpressure/transporte)"
                        )
                    }
                }
            }
        )
        engine = created
        _engineFlow.value = created
        installedKey = keyCopy
        installedClubNetworkId = clubNetworkId
        installedShortId = localShortId
        relayEngine = activeRelayEngine
        unsubscribe = activeRelayEngine?.onVoiceReceived { packet ->
            created.onVoicePacketReceived(packet)
        }
        Log.i(TAG, "motor único instalado node=$localShortId club=0x${clubNetworkId.toString(16)}")
        return created
    }

    @Synchronized
    fun release() {
        releaseLocked()
    }

    private fun releaseLocked() {
        unsubscribe?.invoke()
        unsubscribe = null
        engine?.release()
        engine = null
        _engineFlow.value = null
        relayEngine = null
        installedKey?.let { Arrays.fill(it, 0) }
        installedKey = null
        installedClubNetworkId = null
        installedShortId = null
        voiceSentListeners.clear()
    }

    fun startTalking() {
        engine?.startTalking()
    }

    fun stopTalking() {
        engine?.stopTalking()
    }

    fun calculateVoicePlr(): Float = engine?.calculateVoicePlr() ?: 0f

    fun onVoicePacketReceived(packet: VoicePacket) {
        engine?.onVoicePacketReceived(packet)
    }
}
