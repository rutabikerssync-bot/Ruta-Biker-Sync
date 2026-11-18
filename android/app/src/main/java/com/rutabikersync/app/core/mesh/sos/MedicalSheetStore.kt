package com.rutabikersync.app.core.mesh.sos

import java.util.Arrays
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ficha médica descifrada en memoria volátil de roles autorizados (Fase 6 · Hito 4 · Etapa 3).
 *
 * Cumplimiento de la Regla R12 (Zero-Knowledge & Purga Estricta):
 * Los datos residen únicamente en RAM y se purgan al finalizar la rodada o cancelar la emergencia.
 */
data class DecryptedMedicalSheet(
    val ownerShortId: UShort,
    val rideId: UUID,
    val data: MedicalRescueData,
    val receivedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Almacén volátil en RAM para Fichas Médicas de Emergencia recibidas por Malla BLE (Fase 6 · Hito 4 · Etapa 3).
 *
 * Principio de Mínimo Privilegio (R10.2):
 * - Nodos regulares no poseen autorización (`isLeaderOrSargento == false`) ni clave de rescate,
 *   por lo que nunca almacenan ni descifran información clínica (actúan como relays ciegos).
 * - Líder Principal y Sargento de Armas configuran [setRescueContext] con la clave de 256 bits
 *   distribuida en el `manifest.json` firmado.
 * - [purgeAllOnRideClosed] aplica la Regla R12: sobrescribe con ceros los buffers criptográficos
 *   y borra inmediatamente todas las fichas de la memoria RAM.
 */
class MedicalSheetStore {

    private val sheets = ConcurrentHashMap<UShort, DecryptedMedicalSheet>()

    @Volatile
    private var isLeaderOrSargento: Boolean = false

    @Volatile
    private var currentRescueKey: ByteArray? = null

    /**
     * Configura el contexto de rescate médico para el nodo local.
     *
     * @param isAuthorized Verdadero si el rol local es Líder Principal o Sargento de Armas
     * @param rescueKey Clave simétrica de 256 bits (32 bytes) obtenida del manifiesto firmado
     */
    @Synchronized
    fun setRescueContext(isAuthorized: Boolean, rescueKey: ByteArray?) {
        // Si había una clave previa, sanitizarla antes de reemplazar
        currentRescueKey?.let { Arrays.fill(it, 0.toByte()) }
        this.isLeaderOrSargento = isAuthorized
        this.currentRescueKey = rescueKey?.copyOf()
    }

    /**
     * Determina si el nodo local tiene privilegios y clave para descifrar fichas médicas.
     */
    fun canDecrypt(): Boolean {
        val key = currentRescueKey
        return isLeaderOrSargento && key != null && key.size == MedicalSheetCrypto.AES_KEY_BYTES
    }

    /**
     * Intenta descifrar y almacenar en memoria volátil la ficha médica recibida.
     *
     * @param packet Trama [MedicalSheetPacket] recibida por la red mesh
     * @return [DecryptedMedicalSheet] si el nodo está autorizado y el descifrado fue exitoso; null si es un relay ciego o fallo
     */
    fun decryptAndStore(packet: MedicalSheetPacket): DecryptedMedicalSheet? {
        if (!canDecrypt()) {
            return null
        }
        val key = currentRescueKey ?: return null

        return try {
            val clinicalData = MedicalSheetCrypto.decryptPacket(packet, key)
            val decrypted = DecryptedMedicalSheet(
                ownerShortId = packet.ownerShortId,
                rideId = packet.rideIdUuid,
                data = clinicalData
            )
            sheets[packet.ownerShortId] = decrypted
            try {
                android.util.Log.i("MedicalSheetStore", "MEDICAL_SHEET_DECRYPTED: owner=${packet.ownerShortId}, blood=${clinicalData.bloodType}, allergiesLength=${clinicalData.allergies.length}, iceContact=${clinicalData.iceName.isNotBlank()}")
            } catch (_: Throwable) {}
            decrypted
        } catch (e: Exception) {
            try {
                android.util.Log.e("MedicalSheetStore", "Error descifrando MedicalSheetPacket: ${e.message}")
            } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Consulta una ficha médica por shortId de piloto en la rodada activa.
     */
    fun getSheet(ownerShortId: UShort): DecryptedMedicalSheet? = sheets[ownerShortId]

    /**
     * Retorna todas las fichas clínicas actualmente disponibles en RAM.
     */
    fun getAllSheets(): List<DecryptedMedicalSheet> = sheets.values.toList()

    /**
     * Conteo de fichas médicas actualmente en memoria.
     */
    val count: Int
        get() = sheets.size

    /**
     * Purga criptográfica absoluta en memoria (Regla R12 · Cierre de Rodada).
     *
     * Sobrescribe con ceros la clave de rescate en RAM, limpia el mapa volátil
     * y revoca el estado de autorización local.
     */
    @Synchronized
    fun purgeAllOnRideClosed() {
        sheets.clear()
        currentRescueKey?.let {
            Arrays.fill(it, 0.toByte())
        }
        currentRescueKey = null
        isLeaderOrSargento = false
    }
}

