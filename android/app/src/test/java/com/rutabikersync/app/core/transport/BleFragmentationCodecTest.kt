package com.rutabikersync.app.core.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifica la fragmentación transparente que permite texto cifrado en BLE legacy (29 B). */
class BleFragmentationCodecTest {

    @Test
    fun `CRC16 CCITT-FALSE valor de referencia`() {
        assertEquals(0x29B1, BleFragmentationCodec.crc16("123456789".toByteArray()))
    }

    @Test
    fun `payload pequeno genera un solo frame y hace round trip`() {
        val payload = ByteArray(10) { it.toByte() }
        val frames = BleFragmentationCodec.fragment(payload, 1)
        assertEquals(1, frames.size)
        assertEquals(BleFragmentationCodec.HEADER_BYTES + 10, frames[0].size)
        val rx = BleFragmentationCodec.Reassembler()
        assertArrayEquals(payload, rx.feed(frames[0]))
    }

    @Test
    fun `round trip multi fragmento fuera de orden incluyendo duplicados`() {
        val payload = ByteArray(100) { (it * 7).toByte() }
        val frames = BleFragmentationCodec.fragment(payload, 0x1234)
        assertEquals(7, frames.size)
        frames.forEach { assert(it.size <= BleFragmentationCodec.MAX_MANUFACTURER_PAYLOAD_BYTES) }

        val rx = BleFragmentationCodec.Reassembler()
        val reversed = frames.reversed()
        reversed.take(6).forEach { assertNull("fragmento intermedio", rx.feed(it)) }
        assertNull("duplicado no debe completar nada", rx.feed(frames[6]))
        assertArrayEquals(payload, rx.feed(frames[0]))
    }

    @Test
    fun `CRC invalido descarta el mensaje completo`() {
        val payload = "Gasolinera a 2 km · mensaje largo de prueba".toByteArray()
        val frames = BleFragmentationCodec.fragment(payload, 42).toMutableList()
        val bad = frames[0].copyOf()
        bad[bad.lastIndex] = (bad[bad.lastIndex].toInt() xor 0xFF).toByte()
        frames[0] = bad

        val rx = BleFragmentationCodec.Reassembler()
        frames.dropLast(1).forEach { assertNull(rx.feed(it)) }
        assertNull("CRC corrupto debe descartarse", rx.feed(frames.last()))
    }

    @Test
    fun `trama normal pasa sin cambios por el reensamblador`() {
        val normal = ByteArray(20) { (it + 3).toByte() }
        val rx = BleFragmentationCodec.Reassembler()
        assertArrayEquals(normal, rx.feed(normal))
        assertNotNull(BleFragmentationCodec.Reassembler().feed(normal))
    }

    @Test
    fun `frames exceden el limite legacy solo por el overhead del codec`() {
        val maxText = ByteArray(120) { 0x41 }
        val frames = BleFragmentationCodec.fragment(maxText, 7)
        assertEquals(8, frames.size)
        frames.forEach {
            assert(it.size <= BleFragmentationCodec.MAX_MANUFACTURER_PAYLOAD_BYTES) {
                "frame de ${it.size} B excede el máximo legacy"
            }
        }
    }
}
