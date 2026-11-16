package com.rutabikersync.app.feature.sos

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Repositorio de emergencias SOS, gestión del paquete de rescate médico,
 * cifrado efímero AES-256-GCM y auditoría de accesos temporales de 60 minutos (R10.2, R10.3).
 */
class SosRepository(
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "SosRepository"

        @Volatile
        private var instance: SosRepository? = null

        fun getInstance(context: Context? = null): SosRepository {
            return instance ?: synchronized(this) {
                instance ?: SosRepository(context).also { instance = it }
            }
        }
    }

    /** Gestor de cifrado efímero AES-GCM por sesión de rodada (Fase 5 - R10.1, R10.2). */
    val packageCrypto = EmergencyPackageCrypto()

    // Paquete médico local del piloto actual (se inicializa con datos de perfil o configuración)
    private val _myRescuePacket = MutableStateFlow<EmergencyRescuePacket>(createDefaultRescuePacket())
    val myRescuePacket: StateFlow<EmergencyRescuePacket> = _myRescuePacket.asStateFlow()

    // Paquete médico cifrado con clave efímera de la sesión actual
    private val _encryptedRescuePacket = MutableStateFlow<ByteArray?>(null)
    val encryptedRescuePacket: StateFlow<ByteArray?> = _encryptedRescuePacket.asStateFlow()

    // Alertas de emergencia activas en el convoy
    private val _activeAlerts = MutableStateFlow<List<SosAlertEvent>>(emptyList())
    val activeAlerts: StateFlow<List<SosAlertEvent>> = _activeAlerts.asStateFlow()

    // Registro de auditoría de accesos al paquete de emergencia (Regla R10.3)
    private val _accessAudits = MutableStateFlow<List<SosAccessAudit>>(emptyList())
    val accessAudits: StateFlow<List<SosAccessAudit>> = _accessAudits.asStateFlow()

    /**
     * Inicializa la clave efímera para la rodada y cifra el paquete médico local.
     */
    fun initRideCrypto() {
        packageCrypto.initForRide()
        try {
            _encryptedRescuePacket.value = packageCrypto.encryptPacket(_myRescuePacket.value)
            Log.i(TAG, "Clave efímera de rodada inicializada y paquete médico cifrado.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cifrando paquete al iniciar rodada: ${e.message}", e)
        }
    }

    /**
     * Actualiza el paquete de rescate médico personal del piloto.
     * Si la rodada está activa con clave inicializada, actualiza también el blob cifrado.
     */
    fun updateMyRescuePacket(packet: EmergencyRescuePacket) {
        _myRescuePacket.value = packet
        try {
            _encryptedRescuePacket.value = packageCrypto.encryptPacket(packet)
        } catch (_: Exception) {
            // Clave aún no inicializada (esperando inicio de rodada)
        }
        Log.i(TAG, "Paquete de rescate médico personal actualizado para ${packet.fullName}")
    }

    /**
     * Descifra un paquete médico de emergencia si se cumplen las condiciones de seguridad
     * (debe haber una alerta SOS activa en el convoy).
     */
    fun decryptEmergencyPacket(encryptedBlob: ByteArray): EmergencyRescuePacket? {
        return packageCrypto.decryptPacket(encryptedBlob, this)
    }

    /**
     * Emite una alerta SOS propia del piloto local.
     */
    fun emitLocalSos(
        senderShortId: UShort,
        senderName: String,
        senderRole: String,
        lat: Double,
        lon: Double,
        triggerType: SosTriggerType
    ): SosAlertEvent {
        val alert = SosAlertEvent(
            sosId = "sos-${UUID.randomUUID().toString().take(8)}",
            senderShortId = senderShortId,
            senderName = senderName,
            senderRole = senderRole,
            latitude = lat,
            longitude = lon,
            triggerType = triggerType,
            rescuePacket = _myRescuePacket.value
        )

        val updated = _activeAlerts.value.toMutableList()
        // Reemplazar o añadir
        updated.removeAll { it.senderShortId == senderShortId }
        updated.add(0, alert)
        _activeAlerts.value = updated

        if (com.rutabikersync.app.BuildConfig.DEBUG) { Log.i(TAG, "SOS local emitido") }
        return alert
    }

    /**
     * Registra una alerta SOS recibida de otro piloto por la malla BLE.
     */
    fun onRemoteSosReceived(alert: SosAlertEvent) {
        val current = _activeAlerts.value.toMutableList()
        val index = current.indexOfFirst { it.sosId == alert.sosId || it.senderShortId == alert.senderShortId }
        if (index >= 0) {
            current[index] = alert
        } else {
            current.add(0, alert)
        }
        _activeAlerts.value = current
        if (com.rutabikersync.app.BuildConfig.DEBUG) {
            Log.i(TAG, "Alerta SOS recibida por malla")
        }
    }

    /**
     * Resuelve / finaliza una alerta SOS activa.
     */
    fun resolveSos(sosId: String) {
        val current = _activeAlerts.value.toMutableList()
        val index = current.indexOfFirst { it.sosId == sosId }
        if (index >= 0) {
            current.removeAt(index)
            _activeAlerts.value = current
            if (com.rutabikersync.app.BuildConfig.DEBUG) {
                Log.i(TAG, "SOS marcado como resuelto")
            }
        }
    }

    /**
     * Registra el acceso de un líder al paquete médico de un rider (Regla R10.3).
     * Devuelve el objeto de auditoría con la expiración de 60 minutos.
     */
    fun logAccessToEmergencyPacket(
        sosId: String,
        viewerUserId: String,
        viewerUserName: String,
        targetRiderName: String
    ): SosAccessAudit {
        val audit = SosAccessAudit(
            auditId = "audit-${UUID.randomUUID().toString().take(8)}",
            sosId = sosId,
            viewerUserId = viewerUserId,
            viewerUserName = viewerUserName,
            targetRiderName = targetRiderName
        )

        val updated = _accessAudits.value.toMutableList()
        updated.add(0, audit)
        _accessAudits.value = updated
        if (com.rutabikersync.app.BuildConfig.DEBUG) { Log.i(TAG, "Auditor?a: acceso a paquete de emergencia registrado") }
        return audit
    }

    /**
     * Purga todas las alertas y paquetes temporales al cerrar o detener la rodada (R6.1, R10.3).
     */
    fun clearAllOnRideFinished() {
        _activeAlerts.value = emptyList()
        _encryptedRescuePacket.value = null
        packageCrypto.clearKey()
        if (com.rutabikersync.app.BuildConfig.DEBUG) {
            Log.i(TAG, "Alertas y paquetes de emergencia purgados por fin de rodada")
        }
    }

    private fun createDefaultRescuePacket(): EmergencyRescuePacket {
        return EmergencyRescuePacket(
            riderId = "u-rider-local",
            fullName = "",
            riderAlias = "",
            bloodType = "O+",
            allergies = emptyList(),
            medicalConditions = emptyList(),
            regularMedications = emptyList(),
            organDonor = true,
            emergencyContacts = emptyList(),
            insuranceCompany = null,
            insurancePolicyNumber = null,
            insurancePhone = null,
            bikeBrand = null,
            bikeModel = null,
            bikePlate = null,
            bikeColor = null
        )
    }
}
