package com.rutabikersync.app.core.mesh.sos

import android.content.Context
import java.util.Base64

/**
 * Firma local de las alertas SOS con la clave Ed25519 persistida por el equipo.
 *
 * La verificación en recepción es responsabilidad de `MeshRelayEngine`
 * (`sosCrypto` + `sosPublicKeyResolver`), poblado desde el manifiesto firmado.
 * La misma clave pública se publica al backend para que aparezca en dicho manifiesto.
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

    /** Formato publicado: 32 bytes crudos en Base64, el mismo que firma el manifiesto. */
    fun localPublicKeyRawBase64(): String? = crypto?.let {
        Base64.getEncoder().encodeToString(
            Ed25519SosCrypto.exportRawPublicKey(it.getOrCreateLocalKeyPair().public)
        )
    }

    /** Verificador compartido con el motor de malla; nunca se fabrica una clave distinta. */
    val verifier: Ed25519SosCrypto? get() = crypto

    /** Inyección exclusiva de tests JVM (sin contexto Android). */
    fun setCryptoForTesting(testCrypto: Ed25519SosCrypto) {
        crypto = testCrypto
    }

    /** Restaura el estado no inicializado al terminar un test JVM. */
    fun resetForTesting() {
        crypto = null
    }
}
