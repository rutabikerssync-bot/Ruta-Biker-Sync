package com.rutabikersync.app.core.mesh

/**
 * Gestor de Modo de Bajo Consumo (Low-Power Node · LPN) para Malla BLE Off-Grid (Fase 6 · Hito 5 · Etapa 2).
 *
 * Características Operativas:
 * - Diseñado para balizas solares estacionarias y dispositivos moteros con batería crítica (< 15%).
 * - Ciclo de Sueño del 95%: Activo durante 200 ms cada 5.000 ms (5 segundos).
 * - Filosofía de Ahorro Extremo de Energía:
 *   * No participa en el flooding convencional (no retransmite Heartbeats ni tramas PTT Voice).
 *   * Procesa mensajes de texto dirigidos hacia él (unicast propio o broadcast general).
 *   * Prioridad Absoluta de Vida o Muerte: Siempre despierta y retransmite alertas SOS ([MeshHeader.TYPE_SOS], [MeshHeader.TYPE_SOS_ACK]).
 * - Política Híbrida: Automático si la batería cae a <= 15%, pero configurable por el usuario mediante switch manual.
 */
class LowPowerNodeMode(
    var batteryPercent: Int = 100,
    var manualOverrideEnabled: Boolean? = null,
    val autoThresholdPercent: Int = DEFAULT_AUTO_THRESHOLD_PERCENT
) {

    /**
     * Determina si el modo LPN está actualmente activo.
     * Prioridad:
     * 1. Si el usuario configuró una preferencia manual ([manualOverrideEnabled] != null), se respeta esa decisión.
     * 2. Si no hay override manual, se activa automáticamente si [batteryPercent] <= [autoThresholdPercent].
     */
    val isLpnActive: Boolean
        get() = manualOverrideEnabled ?: (batteryPercent <= autoThresholdPercent)

    /**
     * Evalúa si el nodo se encuentra despierto en un instante temporal específico [epochMs]
     * dentro de su ciclo de 5 segundos (200 ms despierto / 4.800 ms dormido).
     */
    fun isAwakeAt(epochMs: Long): Boolean {
        if (!isLpnActive) {
            return true // Nodos regulares siempre están despiertos
        }
        val cycleOffset = epochMs % TOTAL_CYCLE_MS
        return cycleOffset < ACTIVE_WINDOW_MS
    }

    /**
     * Determina si el nodo en modo LPN debe participar en la retransmisión (relay) de un paquete entrante.
     *
     * @param packetType Tipo de paquete en la cabecera [MeshHeader]
     * @param isForLocalNode Verdadero si el paquete está dirigido a este nodo
     * @return `true` si debe retransmitir; `false` si debe suprimir la retransmisión para ahorrar batería
     */
    fun shouldRelay(packetType: Int, isForLocalNode: Boolean): Boolean {
        // Nodos estándar sin LPN activo siempre participan en el relay
        if (!isLpnActive) {
            return true
        }

        // 1. Rescate y Emergencia SOS: Prioridad absoluta siempre permitida
        if (packetType == MeshHeader.TYPE_SOS || packetType == MeshHeader.TYPE_SOS_ACK) {
            return true
        }

        // 2. Si el paquete era para este nodo, ya se consume localmente y no se retransmite
        if (isForLocalNode) {
            return false
        }

        // 3. Un LPN suprime la retransmisión de tráfico rutinario (PTT Voice, Heartbeats, Texto ajeno)
        return false
    }

    companion object {
        /** Umbral automático de batería: activa LPN al caer a 15% o menos */
        const val DEFAULT_AUTO_THRESHOLD_PERCENT = 15

        /** Ventana activa de escucha y radio: 200 ms */
        const val ACTIVE_WINDOW_MS = 200L

        /** Ventana de sueño profundo de radio: 4.800 ms */
        const val SLEEP_WINDOW_MS = 4800L

        /** Duración total del ciclo: 5.000 ms (5 segundos) */
        const val TOTAL_CYCLE_MS = ACTIVE_WINDOW_MS + SLEEP_WINDOW_MS
    }
}
