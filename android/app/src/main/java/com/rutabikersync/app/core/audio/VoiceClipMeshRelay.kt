package com.rutabikersync.app.core.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestor de retransmisión de clips de voz por malla con fragmentación y límite de 2 saltos (DESIGN.md §3, §5).
 *
 * Especificación de transporte:
 * - Límite de difusión: Máximo 2 saltos en la malla (hopsTaken <= 2).
 * - Deduplicación: Registro de clips recientes por ID para evitar tormentas de reenvío en malla.
 * - Fragmentación: Paquetes de hasta 512 bytes con índice de fragmento y total de fragmentos.
 * - Entrega a cola: Una vez reensamblados todos los fragmentos, se genera el archivo local y se pasa a la cola de audio.
 */
class VoiceClipMeshRelay(
    private val cacheDir: File,
    private val onClipCompleted: ((VoiceClipModel) -> Unit)? = null
) {
    constructor(context: Context, playerQueue: VoiceClipPlayerQueue) : this(
        cacheDir = context.cacheDir,
        onClipCompleted = { playerQueue.enqueue(it) }
    )

    data class VoiceChunkPacket(
        val clipId: String,
        val senderShortId: UShort,
        val senderName: String,
        val senderRole: String,
        val durationSeconds: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val hopsTaken: Int,
        val isEmergency: Boolean,
        val chunkData: ByteArray
    ) {
        fun toByteArray(): ByteArray {
            val nameBytes = senderName.toByteArray(Charsets.UTF_8).take(32).toByteArray()
            val roleBytes = senderRole.toByteArray(Charsets.UTF_8).take(16).toByteArray()
            val idBytes = clipId.toByteArray(Charsets.UTF_8).take(36).toByteArray()

            val buffer = ByteBuffer.allocate(128 + chunkData.size).order(ByteOrder.BIG_ENDIAN)
            buffer.putShort(senderShortId.toShort())
            buffer.put(if (isEmergency) 1.toByte() else 0.toByte())
            buffer.put(hopsTaken.toByte())
            buffer.put(durationSeconds.toByte())
            buffer.putShort(chunkIndex.toShort())
            buffer.putShort(totalChunks.toShort())

            buffer.put(idBytes.size.toByte())
            buffer.put(idBytes)

            buffer.put(nameBytes.size.toByte())
            buffer.put(nameBytes)

            buffer.put(roleBytes.size.toByte())
            buffer.put(roleBytes)

            buffer.putInt(chunkData.size)
            buffer.put(chunkData)

            return buffer.array().copyOf(buffer.position())
        }

        companion object {
            fun fromByteArray(bytes: ByteArray): VoiceChunkPacket? {
                return runCatching {
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    val senderShortId = buffer.short.toUShort()
                    val isEmergency = buffer.get() == 1.toByte()
                    val hopsTaken = buffer.get().toInt()
                    val durationSeconds = buffer.get().toInt()
                    val chunkIndex = buffer.short.toInt()
                    val totalChunks = buffer.short.toInt()

                    val idLen = buffer.get().toInt()
                    val idBytes = ByteArray(idLen).also { buffer.get(it) }
                    val clipId = String(idBytes, Charsets.UTF_8)

                    val nameLen = buffer.get().toInt()
                    val nameBytes = ByteArray(nameLen).also { buffer.get(it) }
                    val senderName = String(nameBytes, Charsets.UTF_8)

                    val roleLen = buffer.get().toInt()
                    val roleBytes = ByteArray(roleLen).also { buffer.get(it) }
                    val senderRole = String(roleBytes, Charsets.UTF_8)

                    val dataLen = buffer.int
                    val chunkData = ByteArray(dataLen).also { buffer.get(it) }

                    VoiceChunkPacket(
                        clipId = clipId,
                        senderShortId = senderShortId,
                        senderName = senderName,
                        senderRole = senderRole,
                        durationSeconds = durationSeconds,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        hopsTaken = hopsTaken,
                        isEmergency = isEmergency,
                        chunkData = chunkData
                    )
                }.getOrNull()
            }
        }
    }

    companion object {
        const val CHUNK_PAYLOAD_SIZE = 512
        const val MAX_RELAY_HOPS = 2
    }

    // Fragmentos acumulados en reensamblaje: clipId -> Map<chunkIndex, ByteArray>
    private val incomingAssemblies = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    // Registro de clips completados para deduplicación
    private val completedClipIds = ConcurrentHashMap.newKeySet<String>()

    // Eventos salientes para la capa de transporte de radio
    private val _outgoingPackets = MutableSharedFlow<VoiceChunkPacket>(extraBufferCapacity = 64)
    val outgoingPackets: SharedFlow<VoiceChunkPacket> = _outgoingPackets.asSharedFlow()

    /**
     * Fragmenta un clip de voz local y lo emite para transmisión por la malla.
     */
    suspend fun broadcastLocalClip(clip: VoiceClipModel): List<VoiceChunkPacket> {
        val audioBytes = clip.audioBytes ?: runCatching { File(clip.filePath).readBytes() }.getOrNull()
            ?: return emptyList()

        completedClipIds.add(clip.id)

        val totalChunks = (audioBytes.size + CHUNK_PAYLOAD_SIZE - 1) / CHUNK_PAYLOAD_SIZE
        val packets = mutableListOf<VoiceChunkPacket>()

        for (i in 0 until totalChunks) {
            val start = i * CHUNK_PAYLOAD_SIZE
            val end = (start + CHUNK_PAYLOAD_SIZE).coerceAtMost(audioBytes.size)
            val chunk = audioBytes.copyOfRange(start, end)

            val packet = VoiceChunkPacket(
                clipId = clip.id,
                senderShortId = clip.senderShortId,
                senderName = clip.senderName,
                senderRole = clip.senderRole,
                durationSeconds = clip.durationSeconds,
                chunkIndex = i,
                totalChunks = totalChunks,
                hopsTaken = 1,
                isEmergency = clip.isEmergencyPriority,
                chunkData = chunk
            )
            packets.add(packet)
            _outgoingPackets.emit(packet)
        }
        return packets
    }

    /**
     * Procesa un fragmento recibido de otro nodo de la malla.
     * Retorna el clip reconstruido si este paquete completó el audio, o null si aún faltan fragmentos.
     */
    suspend fun processIncomingChunk(packet: VoiceChunkPacket): VoiceClipModel? {
        if (completedClipIds.contains(packet.clipId)) {
            return null // Ya fue procesado o reproducido
        }

        // Reenvío de 2 saltos si corresponde (R9.1)
        if (packet.hopsTaken < MAX_RELAY_HOPS) {
            val relayPacket = packet.copy(hopsTaken = packet.hopsTaken + 1)
            _outgoingPackets.emit(relayPacket)
        }

        val map = incomingAssemblies.computeIfAbsent(packet.clipId) { ConcurrentHashMap() }
        map[packet.chunkIndex] = packet.chunkData

        if (map.size >= packet.totalChunks) {
            // Reensamblaje completo
            completedClipIds.add(packet.clipId)
            incomingAssemblies.remove(packet.clipId)

            val totalBytes = (0 until packet.totalChunks).sumOf { map[it]?.size ?: 0 }
            val assembledBytes = ByteArray(totalBytes)
            var offset = 0
            for (i in 0 until packet.totalChunks) {
                val part = map[i] ?: continue
                System.arraycopy(part, 0, assembledBytes, offset, part.size)
                offset += part.size
            }

            val clipsDir = File(cacheDir, "incoming_clips").apply { if (!exists()) mkdirs() }
            val destFile = File(clipsDir, "mesh_${packet.clipId}.opus")
            runCatching { destFile.writeBytes(assembledBytes) }

            val completedClip = VoiceClipModel(
                id = packet.clipId,
                senderShortId = packet.senderShortId,
                senderName = packet.senderName,
                senderRole = packet.senderRole,
                durationSeconds = packet.durationSeconds,
                filePath = destFile.absolutePath,
                audioBytes = assembledBytes,
                hopsTaken = packet.hopsTaken,
                isEmergencyPriority = packet.isEmergency
            )

            // Auto-encolar para reproducción inmediata con ducking
            onClipCompleted?.invoke(completedClip)
            return completedClip
        }

        return null
    }

    fun clearState() {
        incomingAssemblies.clear()
        completedClipIds.clear()
    }
}
