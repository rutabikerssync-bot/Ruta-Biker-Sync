package com.rutabikersync.app.core.mesh.sos

import android.content.Context

/**
 * Firma local de las alertas SOS con la clave Ed25519 persistida por el equipo.
 *
 * La verificación en recepción sigue siendo responsabilidad de `MeshRelayEngine`
 * (`sosCrypto` + `sosPublicKeyResolver`). Mientras el backend no distribuya las
 * claves públicas SOS en el manifiesto firmado, el verificador no puede activarse
 * sin rechazar SOS legítimos; ese bloqueo se documenta en HISTORY_NOTE.md.
 */
object SosSigningHub {
    @Volatile
    private var crypto: Ed25519SosCrypto? = null

    fun initialize(context: Context) {
        if (crypto == null) {
            synchronized(this) {
                if (crypto == null) crypto = Ed25519SosCrypto(context.applicationContext)
            }
        }
    }

    val isReady: Boolean get() = crypto != null

    fun signSos(packet: SosPacket): SosPacket =
        crypto?.signSos(packet) ?: packet

    fun signSosAck(ack: SosAckPacket): SosAckPacket =
        crypto?.signSosAck(ack) ?: ack

    fun localPublicKeyBase64(): String? =
        crypto?.let { Ed25519SosCrypto.exportPublicKeySpkiBase64(it.getOrCreateLocalKeyPair().public) }
}
