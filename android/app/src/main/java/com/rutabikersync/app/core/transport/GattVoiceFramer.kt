package com.rutabikersync.app.core.transport

import java.util.LinkedHashMap

/**
 * Framing mínimo para el stream GATT de voz.
 *
 * Un `VoicePacket` ya cifrado mide 48-100 B. Con el MTU por defecto de ATT (23 B,
 * 20 B útiles) hay que partirlo; con el MTU negociado (hasta 517 B) normalmente
 * cabe en una sola notificación/escritura. Se reutiliza el CRC-16 del códec de
 * fragmentación existente, sin crear un segundo códec de audio ni tocar la criptografía.
 *
 * Cabecera (10 B):
 * - 0..1: MAGIC 0x47 0x56 ("GV")
 * - 2..3: streamId (uint16)
 * - 4..5: sequence del VoicePacket (uint16)
 * - 6:    chunkIndex (uint8)
 * - 7:    totalChunks (uint8)
 * - 8..9: CRC-16 del payload completo
 */
object GattVoiceFramer {
    const val MAGIC_0: Byte = 0x47
    const val MAGIC_1: Byte = 0x56
    const val HEADER_BYTES: Int = 10
    const val ATT_HEADER_BYTES: Int = 3
    const val DEFAULT_MTU: Int = 23

    fun maxChunkDataBytes(mtu: Int): Int = mtu - ATT_HEADER_BYTES - HEADER_BYTES

    fun frame(
        payload: ByteArray,
        mtu: Int,
        streamId: Int,
        sequence: Int
    ): List<ByteArray> {
        require(payload.isNotEmpty()) { "Payload de voz vacío" }
        val chunkSize = maxChunkDataBytes(mtu)
        require(chunkSize > 0) { "MTU=$mtu insuficiente para el framing de voz" }
        val chunks = payload.asList().chunked(chunkSize)
        require(chunks.size <= 0xFF) { "Demasiados chunks GATT (${chunks.size} > 255)" }
        val crc = BleFragmentationCodec.crc16(payload)
        return chunks.mapIndexed { index, chunk ->
            val frame = ByteArray(HEADER_BYTES + chunk.size)
            frame[0] = MAGIC_0
            frame[1] = MAGIC_1
            frame[2] = (streamId ushr 8).toByte()
            frame[3] = streamId.toByte()
            frame[4] = (sequence ushr 8).toByte()
            frame[5] = sequence.toByte()
            frame[6] = index.toByte()
            frame[7] = chunks.size.toByte()
            frame[8] = (crc ushr 8).toByte()
            frame[9] = crc.toByte()
            chunk.forEachIndexed { i, b -> frame[HEADER_BYTES + i] = b }
            frame
        }
    }

    /** Reensamblador concurrente, tolerante a reordenación y duplicados del enlace GATT. */
    class Reassembler {
        private data class Session(
            val total: Int,
            val crc16: Int,
            val chunks: MutableMap<Int, ByteArray> = HashMap(),
            var lastUpdatedMs: Long = System.currentTimeMillis()
        )

        private val sessions = LinkedHashMap<Int, Session>()

        @Synchronized
        fun feed(frame: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? {
            if (frame.size <= HEADER_BYTES ||
                frame[0] != MAGIC_0 || frame[1] != MAGIC_1
            ) {
                return null
            }
            val streamId = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
            val sequence = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
            val index = frame[6].toInt() and 0xFF
            val total = frame[7].toInt() and 0xFF
            val crc = ((frame[8].toInt() and 0xFF) shl 8) or (frame[9].toInt() and 0xFF)
            if (total == 0 || index >= total) return null

            val key = (streamId shl 16) or (sequence and 0xFFFF)
            val session = sessions[key] ?: Session(total, crc).also { sessions[key] = it }
            session.lastUpdatedMs = nowMs
            if (session.total != total || session.crc16 != crc) {
                sessions.remove(key)
                return null
            }

            session.chunks[index] = frame.copyOfRange(HEADER_BYTES, frame.size)
            if (session.chunks.size < total) return null

            val result = ByteArray(total * session.chunks.values.first().size)
            var offset = 0
            for (i in 0 until total) {
                val chunk = session.chunks[i] ?: return null
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            sessions.remove(key)
            val payload = result.copyOf(offset)
            return if (BleFragmentationCodec.crc16(payload) == session.crc16) payload else null
        }

        @Synchronized
        fun purgeExpired(nowMs: Long = System.currentTimeMillis(), ttlMs: Long = 5_000L) {
            val expired = sessions.entries.filter { nowMs - it.value.lastUpdatedMs > ttlMs }
                .map { it.key }
            expired.forEach { sessions.remove(it) }
            while (sessions.size > 32) {
                sessions.remove(sessions.keys.first())
            }
        }

        @Synchronized
        fun reset() = sessions.clear()
    }
}
