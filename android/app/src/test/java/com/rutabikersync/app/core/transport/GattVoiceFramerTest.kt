package com.rutabikersync.app.core.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattVoiceFramerTest {

    @Test
    fun `fragmenta con MTU por defecto y reensambla aunque lleguen desordenados`() {
        val payload = ByteArray(95) { (it * 7).toByte() }
        val frames = GattVoiceFramer.frame(payload, mtu = 23, streamId = 7, sequence = 11)

        assertTrue("Con MTU=23 debe haber fragmentos", frames.size > 1)
        frames.forEach { assertTrue("Frame GATT excede ATT payload: ${it.size}", it.size <= 20) }

        val reassembler = GattVoiceFramer.Reassembler()
        val restored = frames.reversed().mapNotNull { reassembler.feed(it) }
        assertEquals(1, restored.size)
        assertArrayEquals(payload, restored.single())
    }

    @Test
    fun `con MTU negociado el VoicePacket cabe en una sola notificacion`() {
        val payload = ByteArray(88) { (it + 3).toByte() }
        val frames = GattVoiceFramer.frame(payload, mtu = 517, streamId = 9, sequence = 4)

        assertEquals(1, frames.size)
        assertTrue(frames.single().size <= 514)

        val reassembler = GattVoiceFramer.Reassembler()
        assertArrayEquals(payload, reassembler.feed(frames.single()))
    }

    @Test
    fun `un fragmento corrupto se descarta por CRC`() {
        val payload = ByteArray(64) { it.toByte() }
        val frames = GattVoiceFramer.frame(payload, mtu = 23, streamId = 1, sequence = 2).toMutableList()
        val last = frames.last()
        last[last.size - 1] = (last[last.size - 1].toInt() xor 0xFF).toByte()
        frames[frames.lastIndex] = last

        val reassembler = GattVoiceFramer.Reassembler()
        frames.forEach { reassembler.feed(it) }
        assertNull(reassembler.feed(frames.first()))
    }
}
