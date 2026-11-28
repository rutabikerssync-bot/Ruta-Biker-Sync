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

    @Test
    fun `el servidor GATT identifica al emisor por la cabecera del framing`() {
        val frames = GattVoiceFramer.frame(
            payload = ByteArray(64) { it.toByte() },
            mtu = 23,
            streamId = 0x012C,
            sequence = 5
        )
        assertEquals(0x012Cu.toUShort(), GattVoiceFramer.senderShortId(frames.first()))
        assertNull(GattVoiceFramer.senderShortId(ByteArray(8)))
        assertNull(GattVoiceFramer.senderShortId(byteArrayOf(0x00, 0x00, 0x01, 0x2C, 0, 0, 0, 1, 0, 0)))
    }

    @Test
    fun `sobres sensibles de 52 y 120 B se fragmentan por GATT sin perder bytes`() {
        listOf(52, 120).forEach { size ->
            val payload = ByteArray(size) { (it * 13).toByte() }

            val frames = GattVoiceFramer.frame(payload, mtu = 23, streamId = 21, sequence = 5)
            assertTrue("Con MTU=23 debe haber fragmentos", frames.size > 1)
            frames.forEach { assertTrue("Frame GATT excede ATT payload: ${it.size}", it.size <= 20) }

            val rx = GattVoiceFramer.Reassembler()
            var restored: ByteArray? = null
            frames.reversed().forEach { restored = rx.feed(it) ?: restored }
            assertArrayEquals(payload, restored!!)

            val negotiated = GattVoiceFramer.frame(payload, mtu = 517, streamId = 21, sequence = 6)
            assertEquals(1, negotiated.size)
            assertArrayEquals(payload, GattVoiceFramer.Reassembler().feed(negotiated.single()))
        }
    }

    @Test
    fun `purgeExpired descarta sesiones incompletas vencidas y permite reensamblar`() {
        val payload = ByteArray(95) { (it * 7).toByte() }
        val frames = GattVoiceFramer.frame(payload, mtu = 23, streamId = 7, sequence = 11)
        val t0 = 1_000L
        val rx = GattVoiceFramer.Reassembler()

        rx.feed(frames.first(), nowMs = t0)
        rx.purgeExpired(nowMs = t0 + 5_001L)

        var restoredBeforeFirst: ByteArray? = null
        frames.drop(1).forEach {
            restoredBeforeFirst = rx.feed(it, nowMs = t0 + 5_002L) ?: restoredBeforeFirst
        }
        assertNull("La sesión vencida no debe reensamblar con su primer frame antiguo", restoredBeforeFirst)

        val restored = frames.first().let { rx.feed(it, nowMs = t0 + 5_003L) }
        assertArrayEquals(payload, restored)
    }

    @Test
    fun `purgeExpired limita el numero de sesiones parciales`() {
        val rx = GattVoiceFramer.Reassembler()
        val t0 = 2_000L
        repeat(33) { sequence ->
            val frames = GattVoiceFramer.frame(
                payload = ByteArray(95) { (it + sequence).toByte() },
                mtu = 23,
                streamId = 7,
                sequence = sequence
            )
            rx.feed(frames.first(), nowMs = t0)
        }

        rx.purgeExpired(nowMs = t0)

        val field = GattVoiceFramer.Reassembler::class.java.getDeclaredField("sessions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = field.get(rx) as Map<*, *>
        assertEquals(32, sessions.size)
    }
}
