package com.rutabikersync.app.core.transport

/**
 * Estado GATT capturado de una conexión entrante que todavía no se ha podido mapear a un
 * peer del manifiesto (P0.2, corrección #4).
 *
 * El servidor identifica al cliente por el `shortId` de la cabecera del primer frame de voz,
 * que puede llegar mucho después del `onConnectionStateChange` y del CCCD. Guardar MTU y
 * suscripción por MAC evita descartar esos eventos y evita depender de un timeout corto que
 * canceló la conexión en campo antes de que empezara el PTT.
 */
data class GattVoicePendingConnection(
    var mtu: Int = GattVoiceFramer.DEFAULT_MTU,
    var notificationEnabled: Boolean = false
)

/** Registro puramente JVM de conexiones pendientes, sin tipos Android (testeable). */
class GattVoicePendingConnections {
    private val entries = LinkedHashMap<String, GattVoicePendingConnection>()

    @Synchronized
    fun record(address: String): GattVoicePendingConnection =
        entries.getOrPut(address) { GattVoicePendingConnection() }

    @Synchronized
    fun onMtuChanged(address: String, mtu: Int) {
        record(address).mtu = mtu
    }

    @Synchronized
    fun onNotificationEnabled(address: String) {
        record(address).notificationEnabled = true
    }

    @Synchronized
    fun take(address: String): GattVoicePendingConnection? = entries.remove(address)

    @Synchronized
    fun remove(address: String) {
        entries.remove(address)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun snapshot(): Map<String, GattVoicePendingConnection> =
        entries.mapValues { (_, value) -> value.copy() }
}
