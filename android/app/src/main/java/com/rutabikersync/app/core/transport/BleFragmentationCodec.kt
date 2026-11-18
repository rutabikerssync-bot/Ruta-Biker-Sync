package com.rutabikersync.app.core.transport

import java.util.concurrent.ConcurrentHashMap

/**
 * Fragmentación transparente de payloads BLE para advertising legacy (máx. 29 bytes de
 * manufacturer data tras los 2 bytes del company ID 0x5242).
 *
 * Formato de cada fragmento (little-endian-safe, big-endian explícito):
 * - bytes 0..1:  MAGIC (0xA5, 0x5A) — prefijo de 2 bytes para evitar colisión con MeshPacket (20 B).
 * - bytes 2..3:  sessionId (uint16) aleatorio por mensaje.
 * - byte  4:     chunkIndex (uint8, 0-based).
 * - byte  5:     totalChunks (uint8).
 * - bytes 6..7:  crc16 (uint16) del payload completo (validación de reensamblado).
 * - bytes 8..:   datos del fragmento (max 21 B => frame de 29 B).
 *
 * No toca criptografía ni flooding: opera por debajo de MeshRelayEngine, que recibe el
 * paquete original íntegro exactamente una vez por sesión.
 */
object BleFragmentationCodec {

    const val MAGIC_0: Byte = 0xA5.toByte()
    const val MAGIC_1: Byte = 0x5A.toByte()
    const val HEADER_BYTES: Int = 8
    /**
     * Límite real de manufacturer data en advertising legacy: 31 B (PDU) - 3 B (flags AD)
     * - 1 B (length) - 1 B (AD type 0xFF) - 2 B (company ID 0x5242) = 24 B.
     * Confirmado en campo: 29 B produce ADVERTISE_FAILED_DATA_TOO_LARGE (errorCode=1).
     */
    const val MAX_MANUFACTURER_PAYLOAD_BYTES: Int = 24
    const val MAX_CHUNK_DATA_BYTES: Int = MAX_MANUFACTURER_PAYLOAD_BYTES - HEADER_BYTES // 16
    const val SESSION_TIMEOUT_MS: Long = 10_000L

    data class Fragment(
        val sessionId: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val crc16: Int,
        val data: ByteArray
    )

    /** Construye los frames listos para `addManufacturerData(0x5242, frame)`. */
    fun fragment(payload: ByteArray, sessionId: Int): List<ByteArray> {
        require(payload.isNotEmpty()) { "Payload vacío" }
        require(sessionId in 0..0xFFFF) { "sessionId fuera de rango uint16" }
        val chunks = payload.asList().chunked(MAX_CHUNK_DATA_BYTES)
        require(chunks.size <= 0xFF) { "Demasiados fragmentos (${chunks.size} > 255)" }
        val crc = crc16(payload)
        return chunks.mapIndexed { index, chunk ->
            val frame = ByteArray(HEADER_BYTES + chunk.size)
            frame[0] = MAGIC_0
            frame[1] = MAGIC_1
            frame[2] = (sessionId ushr 8).toByte()
            frame[3] = sessionId.toByte()
            frame[4] = index.toByte()
            frame[5] = chunks.size.toByte()
            frame[6] = (crc ushr 8).toByte()
            frame[7] = crc.toByte()
            chunk.forEachIndexed { i, b -> frame[HEADER_BYTES + i] = b }
            frame
        }
    }

    /** Parsea un frame; devuelve null si no tiene el magic o está malformado. */
    fun parse(frame: ByteArray): Fragment? {
        if (frame.size < HEADER_BYTES + 1) return null
        if (frame[0] != MAGIC_0 || frame[1] != MAGIC_1) return null
        val sessionId = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        val index = frame[4].toInt() and 0xFF
        val total = frame[5].toInt() and 0xFF
        val crc = ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)
        if (total == 0 || index >= total || frame.size > HEADER_BYTES + MAX_CHUNK_DATA_BYTES) return null
        val data = frame.copyOfRange(HEADER_BYTES, frame.size)
        if (data.isEmpty() || data.size > MAX_CHUNK_DATA_BYTES) return null
        return Fragment(sessionId, index, total, crc, data)
    }

    /**
     * Reensamblador concurrente. `feed(bytes)` devuelve:
     * - `bytes` si no es un fragmento (paquete normal de 20 B) → el caller lo emite tal cual.
     * - `null` si es un fragmento pero la sesión aún está incompleta.
     * - el payload completo si todos los fragmentos llegaron y el CRC coincide.
     */
    class Reassembler {
        private data class Session(
            val total: Int,
            val crc16: Int,
            val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap(),
            var lastUpdatedMs: Long = System.currentTimeMillis()
        )

        private val sessions = ConcurrentHashMap<Int, Session>()

        fun feed(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? {
            val fragment = parse(bytes) ?: return bytes
            val expired = sessions.entries.filter { nowMs - it.value.lastUpdatedMs > SESSION_TIMEOUT_MS }
                .map { it.key }
            expired.forEach { sessions.remove(it) }

            val session = sessions.computeIfAbsent(fragment.sessionId) {
                Session(fragment.totalChunks, fragment.crc16)
            }
            session.lastUpdatedMs = nowMs
            if (fragment.totalChunks != session.total || fragment.crc16 != session.crc16) {
                sessions.remove(fragment.sessionId)
                return null
            }
            session.chunks[fragment.chunkIndex] = fragment.data
            if (session.chunks.size < session.total) return null

            val payload = ByteArray(session.total * MAX_CHUNK_DATA_BYTES)
            var offset = 0
            for (i in 0 until session.total) {
                val chunk = session.chunks[i] ?: return null
                chunk.copyInto(payload, offset)
                offset += chunk.size
            }
            val result = payload.copyOf(offset)
            sessions.remove(fragment.sessionId)
            return if (crc16(result) == session.crc16) result else null
        }

        fun clear() = sessions.clear()
    }

    /** CRC-16/CCITT-FALSE (polinomio 0x1021, init 0xFFFF). */
    fun crc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
