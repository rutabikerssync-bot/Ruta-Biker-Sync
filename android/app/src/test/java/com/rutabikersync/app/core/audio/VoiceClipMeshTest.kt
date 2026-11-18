package com.rutabikersync.app.core.audio

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceClipMeshTest {

    @Test
    fun testVoiceClipModelHopAndRelayRules() {
        val tempFile = File.createTempFile("test_clip_", ".opus").apply {
            writeBytes(ByteArray(256) { 0x42 })
        }

        val clipHop1 = VoiceClipModel(
            senderShortId = 2u,
            senderName = "Halcón Nocturno",
            senderRole = "ROAD_CAPTAIN",
            durationSeconds = 5,
            filePath = tempFile.absolutePath,
            hopsTaken = 1,
            maxHops = 2
        )

        assertTrue(clipHop1.canRelay)
        val clipHop2 = clipHop1.incrementHop()
        assertEquals(2, clipHop2.hopsTaken)
        assertFalse(clipHop2.canRelay)

        assertTrue(tempFile.exists())
        assertTrue(clipHop2.deleteFile())
        assertFalse(tempFile.exists())
    }

    @Test
    fun testVoiceChunkPacketSerialization() {
        val sampleData = ByteArray(120) { it.toByte() }
        val packet = VoiceClipMeshRelay.VoiceChunkPacket(
            clipId = "test-clip-1234",
            senderShortId = 5u,
            senderName = "Lobo Norteño",
            senderRole = "VICE_PRESIDENT",
            durationSeconds = 8,
            chunkIndex = 1,
            totalChunks = 3,
            hopsTaken = 1,
            isEmergency = true,
            chunkData = sampleData
        )

        val serialized = packet.toByteArray()
        assertTrue(serialized.isNotEmpty())

        val deserialized = VoiceClipMeshRelay.VoiceChunkPacket.fromByteArray(serialized)
        assertNotNull(deserialized)
        assertEquals(packet.clipId, deserialized!!.clipId)
        assertEquals(packet.senderShortId, deserialized.senderShortId)
        assertEquals(packet.senderName, deserialized.senderName)
        assertEquals(packet.senderRole, deserialized.senderRole)
        assertEquals(packet.durationSeconds, deserialized.durationSeconds)
        assertEquals(packet.chunkIndex, deserialized.chunkIndex)
        assertEquals(packet.totalChunks, deserialized.totalChunks)
        assertEquals(packet.hopsTaken, deserialized.hopsTaken)
        assertTrue(deserialized.isEmergency)
        assertArrayEquals(sampleData, deserialized.chunkData)
    }

    @Test
    fun testFragmentationAndReassembly() {
        runBlocking {
            val tempFolder = File(System.getProperty("java.io.tmpdir"), "voice_test_${System.currentTimeMillis()}").apply { mkdirs() }
            var deliveredClip: VoiceClipModel? = null
            val relay = VoiceClipMeshRelay(tempFolder) { deliveredClip = it }

            // Simular clip de audio de 1250 bytes (3 fragmentos de 512 + 512 + 226 bytes)
            val testAudioBytes = ByteArray(1250) { (it % 127).toByte() }
            val tempAudioFile = File.createTempFile("mesh_test_", ".opus").apply {
                writeBytes(testAudioBytes)
            }

            val clip = VoiceClipModel(
                id = "clip-abc-999",
                senderShortId = 10u,
                senderName = "Capitán Oscar",
                senderRole = "PRESIDENT",
                durationSeconds = 6,
                filePath = tempAudioFile.absolutePath,
                audioBytes = testAudioBytes,
                hopsTaken = 1,
                isEmergencyPriority = false
            )

            // 1. Fragmentar clip local
            val chunks = relay.broadcastLocalClip(clip)
            assertEquals(3, chunks.size)
            assertEquals(0, chunks[0].chunkIndex)
            assertEquals(1, chunks[1].chunkIndex)
            assertEquals(2, chunks[2].chunkIndex)
            assertEquals(512, chunks[0].chunkData.size)
            assertEquals(512, chunks[1].chunkData.size)
            assertEquals(226, chunks[2].chunkData.size)

            // 2. Procesar fragmentos simulando recepción en otro nodo
            val receiverRelay = VoiceClipMeshRelay(tempFolder) { deliveredClip = it }
            val result1 = receiverRelay.processIncomingChunk(chunks[1])
            assertNull("No debe completarse antes de tener todos los fragmentos", result1)

            val result2 = receiverRelay.processIncomingChunk(chunks[0])
            assertNull("Aún falta el fragmento 2", result2)

            val finalResult = receiverRelay.processIncomingChunk(chunks[2])
            assertNotNull("Debe reensamblarse completamente al llegar el último fragmento", finalResult)
            assertEquals(clip.id, finalResult!!.id)
            assertEquals(clip.senderName, finalResult.senderName)
            assertEquals(clip.senderShortId, finalResult.senderShortId)
            assertEquals(1250, finalResult.audioBytes!!.size)
            assertArrayEquals(testAudioBytes, finalResult.audioBytes)

            // 3. Deduplicación: Un fragmento repetido debe ser ignorado
            val duplicateResult = receiverRelay.processIncomingChunk(chunks[0])
            assertNull("Fragmentos duplicados de un clip ya completado deben ser ignorados", duplicateResult)

            // Limpieza
            tempAudioFile.delete()
            finalResult.deleteFile()
        }
    }
}
