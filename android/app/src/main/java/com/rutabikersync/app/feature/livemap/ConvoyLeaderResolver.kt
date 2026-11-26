package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.crypto.RideManifestKeyProvider
import com.rutabikersync.app.core.crypto.RideManifestPayload

/**
 * Resuelve el `shortId` del líder de la ruta a partir del manifiesto firmado
 * (quien creó/lidera la rodada), para distinguir entre "Eres Líder" y "Rutero".
 */
object ConvoyLeaderResolver {
    fun resolveLeaderShortId(manifest: RideManifestPayload): UShort? {
        val leaderParticipant = manifest.participants.firstOrNull { p ->
            p.uid == manifest.hostLeaderId && !p.pk.isNullOrBlank()
        } ?: manifest.participants.firstOrNull { p ->
            p.statusKind.trim().equals("leader", ignoreCase = true) && !p.pk.isNullOrBlank()
        } ?: manifest.participants.firstOrNull { p ->
            p.rideRole?.trim().equals("LEADER", ignoreCase = true) && !p.pk.isNullOrBlank()
        } ?: return null

        val pk = leaderParticipant.pk?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return RideManifestKeyProvider.shortIdFromParticipantPublicKey(pk)
    }
}
