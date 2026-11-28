package com.rutabikersync.app.core.mesh.audio

import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** P0.4: clave PTT derivada del manifiesto, fail-closed y dueño único del motor. */
class P0VoiceKeyAndOwnerTest {

    private class FakeAudioFocusController : AudioFocusController {
        override fun requestPttFocus(): Boolean = true
        override fun abandonPttFocus() = Unit
    }

    private lateinit var scope: CoroutineScope
    private val clubId: UInt = 0x52425301u

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        PttAudioEngineHolder.release()
    }

    @Test
    fun `clave derivada es estable separada por dominio y no es la clave fija antigua`() {
        val manifestKey = ByteArray(32) { (it + 0x11).toByte() }
        val derived = VoiceKeyDerivation.derive(manifestKey)
        val oldFixedKey = ByteArray(32) { (it + 0xA5).toByte() }

        assertEquals(32, derived.size)
        assertEquals(derived.asList(), VoiceKeyDerivation.derive(manifestKey).asList())
        assertFalse("Nunca debe reutilizarse la clave maestra del manifiesto", manifestKey.contentEquals(derived))
        assertFalse("Debe eliminarse la clave fija predecible", derived.contentEquals(oldFixedKey))
        assertThrows(IllegalArgumentException::class.java) { VoiceKeyDerivation.derive(ByteArray(31)) }
    }

    @Test
    fun `motor sin clave del manifiesto queda inactivo y no emite voz`() {
        var sent = 0
        val engine = PttAudioEngine(
            scope = scope,
            clubNetworkId = clubId,
            localShortId = 1u,
            audioFocusController = FakeAudioFocusController(),
            onSendVoicePacket = { sent++ }
        )

        engine.startTalking()
        assertTrue(engine.pttState.value is PttState.Idle)
        assertEquals(0, sent)
        engine.release()
    }

    @Test
    fun `holder falla cerrado sin clave y no crea motor`() {
        val installed = PttAudioEngineHolder.install(
            clubNetworkId = clubId,
            localShortId = 1u,
            activeRelayEngine = null,
            audioFocusController = FakeAudioFocusController(),
            pttKey = null
        )

        assertNull(installed)
        assertNull(PttAudioEngineHolder.engine)
    }

    @Test
    fun `reinstalar el holder sustituye la suscripcion RX y no duplica tramas`() {
        val pttKey = VoiceKeyDerivation.derive(ByteArray(32) { (it + 0x22).toByte() })
        val meshKey = ByteArray(32) { (it + 0x33).toByte() }
        val mesh = MeshRelayEngine(
            localShortId = 2u,
            rideSecretKey = meshKey,
            requireAuthorization = false
        )

        fun install(): PttAudioEngine = PttAudioEngineHolder.install(
            clubNetworkId = clubId,
            localShortId = 1u,
            activeRelayEngine = mesh,
            audioFocusController = FakeAudioFocusController(),
            pttKey = pttKey
        )!!

        install()
        val second = install()

        val sender: UShort = 3u
        val nonce = VoiceCrypto.buildDeterministicNonce(clubId.toInt(), sender.toInt(), 51, 0)
        val packet = VoicePacket(
            header = MeshHeader(
                clubNetworkId = clubId,
                senderShortId = sender,
                packetType = MeshHeader.TYPE_PTT_VOICE,
                initTtl = 1,
                currentTtl = 1,
                sequenceNum = 51u
            ),
            frameIndex = 0,
            isLastFrame = true,
            nonce = nonce,
            encryptedPayloadWithTag = VoiceCrypto.encrypt(pttKey, nonce, byteArrayOf(0x48, 0x01, 0x02))
        )
        mesh.processIncomingPacket(packet.toBinary())

        assertEquals(1, second.voicePacketsReceived.get())
        assertEquals(
            "Solo el motor actual debe estar suscrito al engine",
            1,
            second.jitterBuffer.totalFramesReceived
        )
        PttAudioEngineHolder.release()
    }

    @Test
    fun `reinstar el holder con los mismos parametros no libera el motor vigente`() {
        val pttKey = VoiceKeyDerivation.derive(ByteArray(32) { (it + 0x44).toByte() })
        val mesh = MeshRelayEngine(
            localShortId = 2u,
            rideSecretKey = ByteArray(32) { (it + 0x55).toByte() },
            requireAuthorization = false
        )

        fun install(): PttAudioEngine = PttAudioEngineHolder.install(
            clubNetworkId = clubId,
            localShortId = 1u,
            activeRelayEngine = mesh,
            audioFocusController = FakeAudioFocusController(),
            pttKey = pttKey
        )!!

        val first = install()
        val second = install()

        assertSame(
            "Una reinstallación idéntica debe conservar el motor (podría estar transmitiendo)",
            first,
            second
        )
        assertSame(first, PttAudioEngineHolder.engine)
        PttAudioEngineHolder.release()
    }

    @Test
    fun `cambiar el relay reinstala el motor y deja una sola suscripcion RX`() {
        val pttKey = VoiceKeyDerivation.derive(ByteArray(32) { (it + 0x66).toByte() })
        fun mesh(id: UByte) = MeshRelayEngine(
            localShortId = id.toUShort(),
            rideSecretKey = ByteArray(32) { (it + 0x77).toByte() },
            requireAuthorization = false
        )
        fun install(relay: MeshRelayEngine): PttAudioEngine = PttAudioEngineHolder.install(
            clubNetworkId = clubId,
            localShortId = 1u,
            activeRelayEngine = relay,
            audioFocusController = FakeAudioFocusController(),
            pttKey = pttKey
        )!!

        fun packet(sequence: Int) = VoicePacket(
            header = MeshHeader(
                clubNetworkId = clubId,
                senderShortId = 3u,
                packetType = MeshHeader.TYPE_PTT_VOICE,
                initTtl = 1,
                currentTtl = 1,
                sequenceNum = sequence.toUShort()
            ),
            frameIndex = 0,
            isLastFrame = true,
            nonce = VoiceCrypto.buildDeterministicNonce(clubId.toInt(), 3, sequence, 0),
            encryptedPayloadWithTag = VoiceCrypto.encrypt(pttKey, VoiceCrypto.buildDeterministicNonce(clubId.toInt(), 3, sequence, 0), byteArrayOf(0x48, 0x01))
        )

        val oldMesh = mesh(2u)
        val newMesh = mesh(4u)
        val first = install(oldMesh)
        val second = install(newMesh)

        assertNotSame(first, second)
        oldMesh.processIncomingPacket(packet(61).toBinary())
        newMesh.processIncomingPacket(packet(62).toBinary())

        assertEquals(0, first.jitterBuffer.totalFramesReceived)
        assertEquals(1, second.jitterBuffer.totalFramesReceived)
        PttAudioEngineHolder.release()
    }
}
