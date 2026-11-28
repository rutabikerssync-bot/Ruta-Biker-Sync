package com.rutabikersync.app.core.mesh.audio

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derivación de la clave PTT a partir de la clave real del manifiesto firmado (P0.4).
 *
 * No cambia el formato de `VoicePacket` ni primitivas criptográficas: solo sustituye la
 * clave fija `ByteArray(32) { it + 0xA5 }` por una clave separada por dominio, imposible
 * de predecir sin el secreto de la rodada. Si no hay manifiesto activo, no se deriva nada
 * y el motor PTT permanece deshabilitado (fail-closed).
 */
object VoiceKeyDerivation {
    private const val ALGORITHM = "HmacSHA256"
    private const val DOMAIN = "rbs-ptt-v1"

    fun derive(manifestKey: ByteArray): ByteArray {
        require(manifestKey.size == 32) { "La clave del manifiesto debe tener 32 bytes" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(manifestKey, ALGORITHM))
        return mac.doFinal(DOMAIN.toByteArray(StandardCharsets.US_ASCII)).copyOf()
    }
}
