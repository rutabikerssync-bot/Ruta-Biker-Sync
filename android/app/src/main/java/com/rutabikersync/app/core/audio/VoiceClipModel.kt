package com.rutabikersync.app.core.audio

import java.io.File
import java.util.UUID

/**
 * Modelo de clip de voz PTT para convoy de motociclistas (DESIGN.md §5, TASKS.md Fase 5).
 *
 * Características no negociables:
 * - Duración máxima: 15 segundos.
 * - Formato: Codificación Opus (8–12 kbps, ~10–15 KB por clip).
 * - Enrutamiento y difusión: Reenvío máximo de 2 saltos por malla (R9.1).
 * - Efimeridad: Se reproduce una sola vez y se borra inmediatamente del almacenamiento local.
 */
data class VoiceClipModel(
    val id: String = UUID.randomUUID().toString(),
    val senderShortId: UShort = 1u,
    val senderName: String = "Piloto Convoy",
    val senderAvatarUri: String? = null,
    val senderRole: String = "PILOTO",
    val durationSeconds: Int = 0,
    val recordedEpochMs: Long = System.currentTimeMillis(),
    val filePath: String,
    val audioBytes: ByteArray? = null,
    val hopsTaken: Int = 1,
    val maxHops: Int = 2,
    val isEmergencyPriority: Boolean = false
) {
    /**
     * Valida si el clip aún puede ser reenviado en la malla sin exceder el límite de 2 saltos.
     */
    val canRelay: Boolean
        get() = hopsTaken < maxHops

    /**
     * Devuelve una copia con el conteo de saltos incrementado para retransmisión.
     */
    fun incrementHop(): VoiceClipModel = copy(hopsTaken = hopsTaken + 1)

    /**
     * Borrado seguro y definitivo del archivo de audio temporal del disco.
     */
    fun deleteFile(): Boolean {
        return runCatching {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        }.getOrDefault(false)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VoiceClipModel

        if (id != other.id) return false
        if (senderShortId != other.senderShortId) return false
        if (recordedEpochMs != other.recordedEpochMs) return false
        if (filePath != other.filePath) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + senderShortId.hashCode()
        result = 31 * result + recordedEpochMs.hashCode()
        result = 31 * result + filePath.hashCode()
        return result
    }
}
