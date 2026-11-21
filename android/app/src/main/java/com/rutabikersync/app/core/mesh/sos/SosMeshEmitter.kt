package com.rutabikersync.app.core.mesh.sos

import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import kotlinx.coroutines.CoroutineScope

/** Reutiliza el wire format SOS existente y lo entrega al MeshRelayEngine real de la rodada. */
object SosMeshEmitter {

    fun createLocalPacket(
        relay: MeshRelayEngine,
        latitude: Double,
        longitude: Double,
        incidentCode: Byte = 0x02,
        bloodType: Byte = 1
    ): SosPacket {
        val header = MeshHeader(
            clubNetworkId = relay.clubNetworkId,
            packetType = MeshHeader.TYPE_SOS,
            senderShortId = relay.localShortId,
            sequenceNum = (System.currentTimeMillis() and 0xFFFF).toUShort(),
            initTtl = 5,
            currentTtl = 5
        )
        val unsigned = SosPacket(
            header = header,
            latitudeRaw = (latitude * 1e7).toInt(),
            longitudeRaw = (longitude * 1e7).toInt(),
            incidentCode = incidentCode,
            bloodType = bloodType,
            timestampEpochSec = (System.currentTimeMillis() / 1000L).toInt(),
            signature = ByteArray(64),
            hopCount = 0
        )
        // Wire format idéntico (88 bytes); solo se rellena la firma que antes iba en ceros.
        return SosSigningHub.signSos(unsigned)
    }

    fun startLocalBroadcast(
        relay: MeshRelayEngine,
        scope: CoroutineScope,
        latitude: Double,
        longitude: Double,
        incidentCode: Byte = 0x02,
        bloodType: Byte = 1
    ): Boolean {
        if (!relay.isRunning) return false
        val packet = createLocalPacket(relay, latitude, longitude, incidentCode, bloodType)
        relay.startSosBroadcaster(packet, scope) {
            relay.emitSosPacket(packet)
        }
        return true
    }
}
