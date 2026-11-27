package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.power.PowerProfile

/**
 * FASE 3.1: presupuesto de enlaces GATT **cliente** por perfil de energía (8/4/2).
 *
 * Aplica un tope determinista al conjunto de destinos GATT cliente de una rodada: excluye
 * el shortId local si se facilita, deduplica, ordena ASCENDENTEMENTE por shortId y conserva
 * como máximo [PowerProfile.maxGattPeerLinks] elementos. El orden ascendente es una regla
 * estable de selección; NO infiere cercanía, GPS, RSSI ni ninguna otra señal de posición.
 *
 * La colección de entrada debe venir del manifiesto autorizado (en `RideSessionController`,
 * `effectiveExpectedPeers`), nunca de peers inventados ni de una detección local. Esta es una
 * política JVM pura: no depende de Android ni del transporte GATT, y no modifica el servidor
 * GATT, el plan de roles ni el roster completo usado por el estado y el relay.
 */
object GattPeerBudgetPolicy {
    fun cap(
        peerShortIds: Collection<UShort>,
        profile: PowerProfile,
        localShortId: UShort? = null
    ): Set<UShort> =
        peerShortIds
            .asSequence()
            .filter { it != localShortId }
            .sorted()
            .take(profile.maxGattPeerLinks)
            .toSet()
}
