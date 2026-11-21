package com.rutabikersync.app.core.audio

/** Identidad cableada del incidente, sin crear identificadores paralelos para UI o audio. */
data class SosIncidentKey(val senderShortId: UShort, val sequenceNum: UShort)

/**
 * Arbitra qué alarma debe sonar sin borrar el incidente durante una supresión PTT/foco.
 * Todas las transiciones son idempotentes y el ACK corresponde a un incidente concreto.
 */
class SosAlarmCoordinator(private val player: SosTonePlayer) {
    private val lock = Any()
    private val activeRemoteIncidents = mutableSetOf<SosIncidentKey>()
    private val acknowledgedIncidents = LinkedHashMap<SosIncidentKey, Unit>()
    private val pttSources = mutableSetOf<String>()
    private var localActive = false
    private var focusAvailable = true

    val hasActiveIncident: Boolean
        get() = synchronized(lock) { localActive || activeRemoteIncidents.isNotEmpty() }

    val isPlaying: Boolean
        get() = synchronized(lock) { player.isPlaying }

    fun setLocalActive(active: Boolean) = synchronized(lock) {
        localActive = active
        reconcileLocked()
    }

    fun onRemoteSos(incident: SosIncidentKey): Boolean = synchronized(lock) {
        if (incident in acknowledgedIncidents) return false
        val isNew = activeRemoteIncidents.add(incident)
        if (isNew) reconcileLocked()
        isNew
    }

    fun onAck(incident: SosIncidentKey) = synchronized(lock) {
        acknowledgedIncidents[incident] = Unit
        while (acknowledgedIncidents.size > 256) {
            acknowledgedIncidents.remove(acknowledgedIncidents.keys.first())
        }
        activeRemoteIncidents.remove(incident)
        reconcileLocked()
    }

    fun onSenderResolved(senderShortId: UShort) = synchronized(lock) {
        val resolved = activeRemoteIncidents.filter { it.senderShortId == senderShortId }
        resolved.forEach { incident ->
            activeRemoteIncidents.remove(incident)
            acknowledgedIncidents[incident] = Unit
        }
        if (resolved.isNotEmpty()) reconcileLocked()
    }

    fun setPttActive(source: String, active: Boolean) = synchronized(lock) {
        if (active) pttSources += source else pttSources -= source
        reconcileLocked()
    }

    fun setFocusAvailable(available: Boolean) = synchronized(lock) {
        focusAvailable = available
        reconcileLocked()
    }

    /** Libera inmediatamente los recursos visibles sin falsificar un ACK ni borrar el SOS. */
    fun silenceForCabinaExit() = synchronized(lock) {
        player.stop()
    }

    fun stopAll() = synchronized(lock) {
        localActive = false
        activeRemoteIncidents.clear()
        acknowledgedIncidents.clear()
        // PTT/foco son señales de hardware vigentes: no se reabre su prioridad al reiniciar.
        player.stop()
    }

    fun reconcile() = synchronized(lock) { reconcileLocked() }

    private fun reconcileLocked() {
        val canPlay = (localActive || activeRemoteIncidents.isNotEmpty()) &&
            pttSources.isEmpty() && focusAvailable
        if (canPlay) {
            if (!player.isPlaying) player.start()
        } else if (player.isPlaying) {
            player.stop()
        }
    }
}
