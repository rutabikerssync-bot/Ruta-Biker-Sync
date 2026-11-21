package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.audio.SosToneHub
import com.rutabikersync.app.core.audio.SosTonePlayer
import com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto
import com.rutabikersync.app.core.mesh.sos.InMemorySosPublicKeyResolver
import com.rutabikersync.app.core.mesh.sos.SosAckPacket
import com.rutabikersync.app.core.mesh.sos.SosSigningHub
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba decisiva del Hito 1: el SOS emitido por el motor activo de un equipo debe
 * hacer sonar la alarma en OTRO equipo de la rodada y su ACK debe detenerla.
 * Usa el transporte simulado real, sin inventar paquetes ni eventos de malla.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosAlarmMeshDeliveryTest {

    private class RecordingPlayer : SosTonePlayer {
        override var isPlaying: Boolean = false
            private set
        var starts: Int = 0
            private set
        var stops: Int = 0
            private set

        override fun start() {
            starts++
            isPlaying = true
        }

        override fun stop() {
            stops++
            isPlaying = false
        }
    }

    @After
    fun tearDown() {
        SosToneHub.setCustomPlayerForTesting(null)
        SosSigningHub.resetForTesting()
    }

    @Test
    fun `SOS de un equipo suena en el otro y su ACK lo detiene`() = runTest(UnconfinedTestDispatcher()) {
        val player = RecordingPlayer()
        SosToneHub.setCustomPlayerForTesting(player)

        val medium = SimulatedBleMedium()
        val secretKey = "rbs-sos-mesh-delivery-test-32b!!".toByteArray(Charsets.UTF_8)
        val sender = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null },
            simulatedMedium = medium
        )
        val receiver = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null },
            simulatedMedium = medium
        )

        sender.startRide(
            rideId = "ride-sos-delivery",
            localShortId = 1u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )
        receiver.startRide(
            rideId = "ride-sos-delivery",
            localShortId = 2u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )
        sender.updateLocalTelemetry(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)

        // Fail-closed: sin manifiesto SOS no se entregaría nada. Este fixture usa un
        // par Ed25519 real (el mismo embedding que firma SOS y ACK en el hub) y lo
        // publica en el resolver de ambos motores con sus shortIds reales de rodada.
        val sosVerifier = Ed25519SosCrypto(context = null)
        SosSigningHub.setCryptoForTesting(sosVerifier)
        val sosPublicKey = sosVerifier.getOrCreateLocalKeyPair().public
        listOf(sender, receiver).forEach { controller ->
            controller.relayEngine?.apply {
                sosCrypto = sosVerifier
                sosPublicKeyResolver = InMemorySosPublicKeyResolver().apply {
                    registerPeer(1u, sosPublicKey)
                    registerPeer(2u, sosPublicKey)
                }
            }
        }

        assertNull("Sin SOS previo no debe haber alerta remota", SosToneHub.latestRemoteSos.value)

        sender.triggerSos(active = true)
        runCurrent()

        val received = SosToneHub.latestRemoteSos.value
        assertNotNull("El otro equipo debe recibir el SOS por el transporte activo", received)
        assertEquals(1.toUShort(), received!!.header.senderShortId)
        assertTrue("La alarma debe estar sonando en el receptor", player.isPlaying)

        // La fuente local del mismo proceso no debe sostener por sí sola la alarma remota:
        // si el equipo emisor desaparece, el receptor sigue alertando (esto es el objetivo).
        SosToneHub.setLocalActive(false)
        assertTrue("La alarma remota sigue vigente sin la fuente local", player.isPlaying)

        val ack = SosAckPacket.create(
            clubNetworkId = received.header.clubNetworkId,
            ackerShortId = 2u,
            sequenceNum = 501u,
            originalSenderShortId = received.header.senderShortId,
            originalSequenceNum = received.header.sequenceNum,
            ackTimestampEpochSec = 1_731_152_002,
            signature = ByteArray(64),
            currentTtl = 5
        )
        val signedAck = sosVerifier.signSosAck(ack, sosVerifier.getOrCreateLocalKeyPair().private)
        assertTrue("El ACK debe aceptarse en el transporte activo del receptor", receiver.sendSosAck(signedAck))
        runCurrent()

        assertFalse("El ACK debe detener la alarma del incidente", player.isPlaying)
        assertNull("El incidente confirmado deja de estar activo", SosToneHub.latestRemoteSos.value)
        assertEquals("La alarma no debe reiniciarse tras el ACK", 1, player.starts)

        sender.stopAll()
        receiver.stopAll()
    }
}
