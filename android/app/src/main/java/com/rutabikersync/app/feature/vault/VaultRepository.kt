package com.rutabikersync.app.feature.vault

import android.content.Context
import android.content.SharedPreferences
import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

interface VaultRepository {
    val documentsFlow: StateFlow<List<BikerDocument>>
    val medicalSheetFlow: StateFlow<EmergencyMedicalSheet>
    val isSyncingFlow: StateFlow<Boolean>

    suspend fun saveDocument(document: BikerDocument)
    suspend fun deleteDocument(id: String)
    suspend fun saveMedicalSheet(sheet: EmergencyMedicalSheet)
    suspend fun toggleLockScreen(enabled: Boolean)

    suspend fun syncVault(): Result<Unit>
    fun generateMnemonic(): List<String>
    fun getOrGenerateMnemonic(): List<String>
    fun hasMnemonicBackedUp(): Boolean
    fun hasMasterKey(): Boolean
    fun validateMnemonic(words: List<String>): Boolean
    suspend fun backupMasterKeyWithMnemonic(words: List<String>): Result<Unit>
    suspend fun restoreFromRecoveryMnemonic(words: List<String>): Result<Unit>
    suspend fun rotateMnemonic(): Result<List<String>>
    fun wipeMnemonicFromMemory(words: MutableList<String>)
    fun getMedicalSheet(): EmergencyMedicalSheet
    fun hasMedicalSheet(): Boolean
    fun getLastSharedTimeMillis(): Long?
    fun getLastEditedTimeMillis(): Long?
    fun getLastSharedRideId(): String?
    fun getLastSharedGroupId(): String?
    suspend fun shareMedicalSheet(rideId: String, groupId: String, durationHours: Int = 24): Result<Unit>
    suspend fun revokeMedicalSheet(rideId: String): Result<Unit>
    suspend fun fetchSharedMedicalSheet(targetUserId: String, rideId: String): Result<EmergencyMedicalSheet>

    companion object {
        @Volatile
        private var instance: VaultRepository? = null

        fun getInstance(context: Context): VaultRepository {
            return instance ?: synchronized(this) {
                instance ?: LocalVaultRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

class LocalVaultRepository(
    private val context: Context,
    val apiClient: ClubApiClient = ClubApiClient.getInstance(),
    val crypto: VaultCrypto = VaultCrypto(context)
) : VaultRepository {

    companion object {
        private const val PREFS_NAME = "rbs_biker_vault_local_store"
        private const val KEY_DOCS = "vault_documents_json"
        private const val KEY_MEDICAL = "vault_medical_sheet_json"
        private const val KEY_MNEMONIC = "vault_mnemonic_words_v1"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    private val _documents = MutableStateFlow<List<BikerDocument>>(emptyList())
    override val documentsFlow: StateFlow<List<BikerDocument>> = _documents.asStateFlow()

    private val _medicalSheet = MutableStateFlow(EmergencyMedicalSheet())
    override val medicalSheetFlow: StateFlow<EmergencyMedicalSheet> = _medicalSheet.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    override val isSyncingFlow: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        ensureAuthenticatedUser()
        loadData()
        if (!apiClient.currentUserId.isNullOrBlank()) {
            repositoryScope.launch {
                syncVault()
            }
        }
    }

    private fun ensureAuthenticatedUser() {
        if (apiClient.currentUserId.isNullOrBlank()) {
            val savedUid = context.getSharedPreferences("rbs_auth_prefs", Context.MODE_PRIVATE)
                .getString("user_id", null)
            if (!savedUid.isNullOrBlank()) {
                apiClient.currentUserId = savedUid
            }
        }
    }

    private fun loadData() {
        // Cargar documentos descifrándolos localmente con AES-256-GCM
        val docsJson = prefs.getString(KEY_DOCS, null)?.let { blob -> crypto.decrypt(blob) }
        if (!docsJson.isNullOrBlank()) {
            try {
                val list = mutableListOf<BikerDocument>()
                val array = JSONArray(docsJson)
                for (i in 0 until array.length()) {
                    list.add(jsonToDocument(array.getJSONObject(i)))
                }
                _documents.value = list
            } catch (_: Exception) {
                _documents.value = emptyList()
            }
        } else {
            _documents.value = emptyList()
        }

        // Cargar ficha médica
        val medJson = prefs.getString(KEY_MEDICAL, null)?.let { blob -> crypto.decrypt(blob) }
        if (!medJson.isNullOrBlank()) {
            try {
                _medicalSheet.value = jsonToMedicalSheet(JSONObject(medJson))
            } catch (_: Exception) {
                _medicalSheet.value = EmergencyMedicalSheet()
            }
        } else {
            _medicalSheet.value = EmergencyMedicalSheet()
        }
    }

    // -------------------------------------------------------------------------
    // Operaciones CRUD Local-First (0 ms reactividad) con Push al Servidor
    // -------------------------------------------------------------------------

    override suspend fun saveDocument(document: BikerDocument) {
        val updatedDoc = document.copy(updatedAtMillis = System.currentTimeMillis())
        val current = _documents.value.toMutableList()
        val index = current.indexOfFirst { it.id == updatedDoc.id }
        if (index >= 0) {
            current[index] = updatedDoc
        } else {
            current.add(0, updatedDoc)
        }
        _documents.value = current
        persistDocuments(current)

        // Push en segundo plano al backend Fastify
        repositoryScope.launch {
            pushDocumentToBackend(updatedDoc)
        }
    }

    override suspend fun deleteDocument(id: String) {
        val current = _documents.value.filterNot { it.id == id }
        _documents.value = current
        persistDocuments(current)

        repositoryScope.launch {
            apiClient.deleteVaultDocument(id)
        }
    }

    override suspend fun saveMedicalSheet(sheet: EmergencyMedicalSheet) {
        val now = System.currentTimeMillis()
        val updatedSheet = sheet.copy(lastUpdatedMillis = now)
        _medicalSheet.value = updatedSheet
        persistMedicalSheet(updatedSheet)
        prefs.edit().putLong("vault_medical_last_edited_at", now).apply()

        repositoryScope.launch {
            pushMedicalSheetToBackend(updatedSheet)
        }
    }

    override suspend fun toggleLockScreen(enabled: Boolean) {
        val updated = _medicalSheet.value.copy(showOnLockScreen = enabled)
        saveMedicalSheet(updated)
    }

    // -------------------------------------------------------------------------
    // Sincronización Bidireccional (Last-Write-Wins E2EE)
    // -------------------------------------------------------------------------

    override suspend fun syncVault(): Result<Unit> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        try {
            val listRes = apiClient.listVaultDocuments()
            if (!listRes.isSuccess || listRes.getOrNull()?.body == null) {
                return@withContext Result.failure(Exception("Error al listar documentos de bóveda en el servidor"))
            }

            val remoteDocsArray = listRes.getOrNull()?.body?.get("documents")?.jsonArray ?: return@withContext Result.success(Unit)

            val localDocs = _documents.value.toMutableList()
            var localDocsChanged = false
            val userSalt = crypto.getOrCreateUserSalt()

            for (element in remoteDocsArray) {
                val remoteItem = element.jsonObject
                val docId = remoteItem["id"]?.jsonPrimitive?.content ?: continue
                val docType = remoteItem["docType"]?.jsonPrimitive?.content ?: continue
                val remoteSalt = remoteItem["userSalt"]?.jsonPrimitive?.content
                if (!remoteSalt.isNullOrBlank()) {
                    crypto.setUserSalt(remoteSalt)
                }

                val remoteUpdatedAtIso = remoteItem["updatedAt"]?.jsonPrimitive?.content
                val remoteUpdatedAt = remoteUpdatedAtIso?.let { parseIsoToMillis(it) } ?: 0L

                val localIndex = localDocs.indexOfFirst { it.id == docId }
                val localDoc = if (localIndex >= 0) localDocs[localIndex] else null

                if (docType == "medical_sheet") {
                    val localMed = _medicalSheet.value
                    if (remoteUpdatedAt > localMed.lastUpdatedMillis) {
                        // Descargar y descifrar ficha médica del servidor
                        val docRes = apiClient.getVaultDocument(docId)
                        val body = docRes.getOrNull()?.body
                        if (docRes.isSuccess && body != null) {
                            val payload = VaultCrypto.EncryptedPayload(
                                ciphertext = body["ciphertext"]?.jsonPrimitive?.content.orEmpty(),
                                iv = body["iv"]?.jsonPrimitive?.content.orEmpty(),
                                authTag = body["authTag"]?.jsonPrimitive?.content.orEmpty()
                            )
                            val decryptedBytes = runCatching { crypto.decryptPayload(payload) }.getOrNull()
                            if (decryptedBytes != null) {
                                val jsonStr = String(decryptedBytes, Charsets.UTF_8)
                                val downloadedMed = jsonToMedicalSheet(JSONObject(jsonStr))
                                _medicalSheet.value = downloadedMed
                                persistMedicalSheet(downloadedMed)
                            }
                        }
                    } else if (localMed.lastUpdatedMillis > remoteUpdatedAt) {
                        pushMedicalSheetToBackend(localMed)
                    }
                    continue
                }

                // Documentos regulares
                if (localDoc == null || remoteUpdatedAt > localDoc.updatedAtMillis) {
                    // Servidor gana: descargar y descifrar
                    val docRes = apiClient.getVaultDocument(docId)
                    val body = docRes.getOrNull()?.body
                    if (docRes.isSuccess && body != null) {
                        val payload = VaultCrypto.EncryptedPayload(
                            ciphertext = body["ciphertext"]?.jsonPrimitive?.content.orEmpty(),
                            iv = body["iv"]?.jsonPrimitive?.content.orEmpty(),
                            authTag = body["authTag"]?.jsonPrimitive?.content.orEmpty()
                        )
                        val decryptedBytes = runCatching { crypto.decryptPayload(payload) }.getOrNull()
                        if (decryptedBytes != null) {
                            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
                            val downloadedDoc = jsonToDocument(JSONObject(jsonStr))
                            if (localIndex >= 0) {
                                localDocs[localIndex] = downloadedDoc
                            } else {
                                localDocs.add(downloadedDoc)
                            }
                            localDocsChanged = true
                        }
                    }
                } else if (localDoc.updatedAtMillis > remoteUpdatedAt) {
                    // Cliente gana: subir al servidor
                    pushDocumentToBackend(localDoc)
                }
            }

            // Subir documentos locales que aún no existan en el servidor
            for (local in localDocs) {
                val existsRemote = remoteDocsArray.any {
                    it.jsonObject["id"]?.jsonPrimitive?.content == local.id
                }
                if (!existsRemote) {
                    pushDocumentToBackend(local)
                }
            }

            if (localDocsChanged) {
                _documents.value = localDocs
                persistDocuments(localDocs)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    // -------------------------------------------------------------------------
    // BIP-0039 & Recuperación de Clave Maestra
    // -------------------------------------------------------------------------

    override fun generateMnemonic(): List<String> {
        val words = Bip39.generateMnemonic()
        // Guardar mnemónica cifrada en preferencias privadas
        val joined = words.joinToString(" ")
        prefs.edit().putString(KEY_MNEMONIC, crypto.encrypt(joined)).apply()
        return words
    }

    override fun getOrGenerateMnemonic(): List<String> {
        val existingEncrypted = prefs.getString(KEY_MNEMONIC, null)
        if (!existingEncrypted.isNullOrBlank()) {
            val decrypted = runCatching { crypto.decrypt(existingEncrypted) }.getOrNull()
            if (!decrypted.isNullOrBlank()) {
                val words = decrypted.trim().split("\\s+".toRegex())
                if (validateMnemonic(words)) {
                    return words
                }
            }
        }
        return generateMnemonic()
    }

    override fun validateMnemonic(words: List<String>): Boolean {
        return Bip39.validateMnemonic(words)
    }

    override fun hasMnemonicBackedUp(): Boolean {
        val existingEncrypted = prefs.getString(KEY_MNEMONIC, null)
        return !existingEncrypted.isNullOrBlank()
    }

    override fun hasMasterKey(): Boolean {
        return crypto.hasMasterKey()
    }

    override suspend fun backupMasterKeyWithMnemonic(words: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        if (!validateMnemonic(words)) {
            return@withContext Result.failure(IllegalArgumentException("Frase de recuperación BIP-0039 inválida"))
        }

        ensureAuthenticatedUser()
        val blob = crypto.exportRecoveryBlob(words)
        val tokenRes = apiClient.requestRecoveryToken()
        if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "backupMasterKey: token request completed") }
        if (tokenRes.getOrNull()?.statusCode == 429) {
            val retryAfter = tokenRes.getOrNull()?.retryAfterSeconds ?: 3600L
            val minutes = (retryAfter + 59) / 60
            return@withContext Result.failure(Exception("Has superado el límite de intentos de recuperación. Espera $minutes minutos antes de reintentar."))
        }
        val recoveryToken = tokenRes.getOrNull()?.body?.get("recoveryToken")?.jsonPrimitive?.content
            ?: return@withContext Result.failure(Exception("No se pudo obtener el token efímero de recuperación (${tokenRes.getOrNull()?.statusCode})"))

        val uploadRes = apiClient.uploadRecoveryKey(
            recoveryToken = recoveryToken,
            encryptedMasterKey = blob.encryptedMasterKey,
            iv = blob.iv,
            authTag = blob.authTag,
            pbkdf2Salt = blob.pbkdf2Salt,
            pbkdf2Iterations = blob.pbkdf2Iterations
        )
        if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "uploadRecoveryKey: request completed") }

        if (uploadRes.isSuccess) {
            val joined = words.joinToString(" ")
            prefs.edit().putString(KEY_MNEMONIC, crypto.encrypt(joined)).apply()
            Result.success(Unit)
        } else {
            Result.failure(Exception("Fallo al registrar clave de recuperación en el backend: ${uploadRes.getOrNull()?.statusCode}"))
        }
    }

    override suspend fun restoreFromRecoveryMnemonic(words: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        if (!validateMnemonic(words)) {
            return@withContext Result.failure(IllegalArgumentException("Frase de recuperación BIP-0039 inválida"))
        }

        ensureAuthenticatedUser()
        val tokenRes = apiClient.requestRecoveryToken()
        val tokenApiRes = tokenRes.getOrNull()
        if (tokenApiRes?.statusCode == 429) {
            val retryAfter = tokenApiRes.retryAfterSeconds ?: 3600L
            val minutes = (retryAfter + 59) / 60
            return@withContext Result.failure(Exception("Has superado el límite de 5 intentos por hora. Espera $minutes minutos antes de reintentar."))
        }
        val recoveryToken = tokenApiRes?.body?.get("recoveryToken")?.jsonPrimitive?.content
        if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "restore: token request completed") }
        if (recoveryToken == null) {
            return@withContext Result.failure(Exception("No se pudo obtener el token efímero de recuperación"))
        }

        val getRes = apiClient.getRecoveryKey(recoveryToken)
        val getApiRes = getRes.getOrNull()
        if (getApiRes?.statusCode == 429) {
            val retryAfter = getApiRes.retryAfterSeconds ?: 3600L
            val minutes = (retryAfter + 59) / 60
            return@withContext Result.failure(Exception("Has superado el límite de 5 intentos por hora. Espera $minutes minutos antes de reintentar."))
        }
        val body = getApiRes?.body
        if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "restore: recovery blob request completed") }
        if (body == null) {
            return@withContext Result.failure(Exception("No se pudo descargar la clave de recuperación del backend"))
        }

        val blob = VaultCrypto.RecoveryKeyBlob(
            encryptedMasterKey = body["encryptedMasterKey"]?.jsonPrimitive?.content.orEmpty(),
            iv = body["iv"]?.jsonPrimitive?.content.orEmpty(),
            authTag = body["authTag"]?.jsonPrimitive?.content.orEmpty(),
            pbkdf2Salt = body["pbkdf2Salt"]?.jsonPrimitive?.content.orEmpty(),
            pbkdf2Iterations = body["pbkdf2Iterations"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100_000
        )

        val restoredOk = crypto.restoreMasterKeyFromRecovery(words, blob)
        if (com.rutabikersync.app.BuildConfig.DEBUG) { android.util.Log.d("VaultRepo", "restore: master key restore completed") }
        if (!restoredOk) {
            return@withContext Result.failure(SecurityException("La frase de recuperación no coincide con la clave maestra registrada"))
        }

        // Cero persistencia en disco de mnemónicas (D72)
        prefs.edit().remove(KEY_MNEMONIC).apply()

        // Una vez restaurada la clave maestra, ejecutar sincronización completa para descargar y descifrar documentos
        syncVault()
        Result.success(Unit)
    }

    override suspend fun rotateMnemonic(): Result<List<String>> = withContext(Dispatchers.IO) {
        ensureAuthenticatedUser()

        // 1. Generar nueva mnemónica BIP-0039 (128 bits de entropía pura)
        val freshWords = Bip39.generateMnemonic()
        if (!validateMnemonic(freshWords)) {
            return@withContext Result.failure(IllegalStateException("Error al generar mnemónica válida"))
        }

        // 2. Exportar nuevo RecoveryKeyBlob con nuevo salt aleatorio de 16 bytes
        val blob = crypto.exportRecoveryBlob(freshWords)

        // 3. Solicitar token efímero de recuperación de 5 minutos (X-Recovery-Token)
        val tokenRes = apiClient.requestRecoveryToken()
        val tokenApiRes = tokenRes.getOrNull()
        if (tokenApiRes?.statusCode == 429) {
            val retryAfter = tokenApiRes.retryAfterSeconds ?: 3600L
            val minutes = (retryAfter + 59) / 60
            return@withContext Result.failure(Exception("Has superado el límite de 5 intentos por hora. Espera $minutes minutos antes de reintentar."))
        }
        val recoveryToken = tokenApiRes?.body?.get("recoveryToken")?.jsonPrimitive?.content
            ?: return@withContext Result.failure(Exception("No se pudo obtener el token efímero de recuperación (${tokenApiRes?.statusCode})"))

        // 4. Subir nuevo blob al servidor (ON CONFLICT DO UPDATE reemplaza e invalida la anterior)
        val uploadRes = apiClient.uploadRecoveryKey(
            recoveryToken = recoveryToken,
            encryptedMasterKey = blob.encryptedMasterKey,
            iv = blob.iv,
            authTag = blob.authTag,
            pbkdf2Salt = blob.pbkdf2Salt,
            pbkdf2Iterations = blob.pbkdf2Iterations
        )

        if (uploadRes.isSuccess) {
            // 5. Cero persistencia en disco: purgar mnemónicas guardadas en preferencias
            prefs.edit()
                .remove(KEY_MNEMONIC)
                .putLong("vault_mnemonic_rotated_at", System.currentTimeMillis())
                .apply()
            Result.success(freshWords)
        } else {
            Result.failure(Exception("Fallo al registrar la nueva clave de recuperación en el backend: ${uploadRes.getOrNull()?.statusCode}"))
        }
    }

    override fun wipeMnemonicFromMemory(words: MutableList<String>) {
        for (i in words.indices) {
            val chars = words[i].toCharArray()
            chars.fill('0')
            words[i] = String(chars)
        }
        words.clear()
    }

    override fun getMedicalSheet(): EmergencyMedicalSheet = _medicalSheet.value

    override fun hasMedicalSheet(): Boolean {
        val raw = prefs.getString(KEY_MEDICAL, null)
        return !raw.isNullOrBlank()
    }

    override fun getLastSharedTimeMillis(): Long? {
        val t = prefs.getLong("vault_medical_last_shared_at", 0L)
        return if (t > 0L) t else null
    }

    override fun getLastEditedTimeMillis(): Long? {
        val t = prefs.getLong("vault_medical_last_edited_at", 0L)
        return if (t > 0L) t else null
    }

    override fun getLastSharedRideId(): String? {
        return prefs.getString("vault_medical_last_shared_ride_id", null)
    }

    override fun getLastSharedGroupId(): String? {
        return prefs.getString("vault_medical_last_shared_group_id", null)
    }

    override suspend fun shareMedicalSheet(rideId: String, groupId: String, durationHours: Int): Result<Unit> = withContext(Dispatchers.IO) {
        ensureAuthenticatedUser()
        if (rideId.isBlank() || groupId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No hay una rodada activa ni club vinculado para compartir la ficha médica (RULES R6.5)."))
        }
        val currentSheet = _medicalSheet.value
        val valResult = MedicalSheetValidator.validate(currentSheet)
        if (!valResult.isValid) {
            val firstError = valResult.errors.values.firstOrNull() ?: "Ficha médica incompleta o con errores clínicos"
            return@withContext Result.failure(IllegalArgumentException(firstError))
        }

        val jsonStr = medicalSheetToJson(currentSheet).toString()
        val payload = crypto.encryptToPayload(jsonStr.toByteArray(Charsets.UTF_8))

        val res = apiClient.shareMedicalSheet(
            rideId = rideId,
            groupId = groupId,
            ciphertext = payload.ciphertext,
            iv = payload.iv,
            authTag = payload.authTag,
            durationHours = durationHours
        )

        val apiResp = res.getOrNull()
        if (res.isSuccess && apiResp != null && apiResp.isSuccess) {
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong("vault_medical_last_shared_at", now)
                .putString("vault_medical_last_shared_ride_id", rideId)
                .putString("vault_medical_last_shared_group_id", groupId)
                .apply()
            Result.success(Unit)
        } else {
            val errCode = apiResp?.body?.get("error")?.jsonPrimitive?.content
            val errMsg = apiResp?.body?.get("message")?.jsonPrimitive?.content
            val ex = when (errCode) {
                "RIDE_NOT_ACTIVE" -> Exception("RIDE_NOT_ACTIVE: ${errMsg ?: "La rodada no está activa"}")
                "RIDE_NOT_FOUND" -> Exception("RIDE_NOT_FOUND: Rodada no encontrada")
                else -> Exception(errMsg ?: res.exceptionOrNull()?.message ?: "Error al compartir la ficha médica (${apiResp?.statusCode})")
            }
            Result.failure(ex)
        }
    }

    override suspend fun revokeMedicalSheet(rideId: String): Result<Unit> = withContext(Dispatchers.IO) {
        ensureAuthenticatedUser()
        val res = apiClient.revokeSharedMedicalSheet(rideId)
        val apiResp = res.getOrNull()
        if (res.isSuccess && apiResp != null && apiResp.isSuccess) {
            prefs.edit()
                .remove("vault_medical_last_shared_at")
                .remove("vault_medical_last_shared_ride_id")
                .remove("vault_medical_last_shared_group_id")
                .apply()
            Result.success(Unit)
        } else {
            val errMsg = apiResp?.body?.get("message")?.jsonPrimitive?.content
            Result.failure(Exception(errMsg ?: "Error al revocar compartición (${apiResp?.statusCode})"))
        }
    }

    override suspend fun fetchSharedMedicalSheet(targetUserId: String, rideId: String): Result<EmergencyMedicalSheet> = withContext(Dispatchers.IO) {
        ensureAuthenticatedUser()
        val res = apiClient.getEmergencyMedicalSheet(targetUserId, rideId)
        val apiResp = res.getOrNull()
        if (!res.isSuccess || apiResp == null || !apiResp.isSuccess || apiResp.body == null) {
            val errCode = apiResp?.body?.get("error")?.jsonPrimitive?.content
            val errMsg = apiResp?.body?.get("message")?.jsonPrimitive?.content
            val ex = when (errCode) {
                "MEDICAL_ACCESS_RESTRICTED" -> Exception("MEDICAL_ACCESS_RESTRICTED: Solo el Líder o Sargento de Armas puede ver la ficha")
                "RIDE_NOT_ACTIVE" -> Exception("RIDE_NOT_ACTIVE: La rodada no está activa")
                "MEDICAL_SHEET_NOT_FOUND" -> Exception("MEDICAL_SHEET_NOT_FOUND: El piloto no ha compartido su ficha médica para esta rodada")
                else -> Exception(errMsg ?: "Error al consultar ficha médica (${apiResp?.statusCode})")
            }
            return@withContext Result.failure(ex)
        }

        val body = apiResp.body
        val ciphertext = body["ciphertext"]?.jsonPrimitive?.content.orEmpty()
        val iv = body["iv"]?.jsonPrimitive?.content.orEmpty()
        val authTag = body["authTag"]?.jsonPrimitive?.content.orEmpty()

        val payload = VaultCrypto.EncryptedPayload(ciphertext, iv, authTag)
        val decryptedBytes = runCatching { crypto.decryptPayload(payload) }.getOrNull()
            ?: return@withContext Result.failure(Exception("No se pudo descifrar la ficha médica con la clave de seguridad"))

        val jsonStr = String(decryptedBytes, Charsets.UTF_8)
        val sheet = jsonToMedicalSheet(JSONObject(jsonStr))
        Result.success(sheet)
    }

    // -------------------------------------------------------------------------
    // Auxiliares Internos de Cifrado y Empuje
    // -------------------------------------------------------------------------

    private suspend fun pushDocumentToBackend(doc: BikerDocument) {
        ensureAuthenticatedUser()
        val jsonStr = documentToJson(doc).toString()
        val payload = crypto.encryptToPayload(jsonStr.toByteArray(Charsets.UTF_8))
        val userSalt = crypto.getOrCreateUserSalt()

        val res = apiClient.uploadVaultDocument(
            docType = doc.type.name.lowercase(),
            ciphertext = payload.ciphertext,
            iv = payload.iv,
            authTag = payload.authTag,
            userSalt = userSalt,
            title = doc.title
        )
        android.util.Log.d("VaultRepo", "pushDocumentToBackend result: ${res.getOrNull()?.statusCode}")
    }

    private suspend fun pushMedicalSheetToBackend(sheet: EmergencyMedicalSheet) {
        ensureAuthenticatedUser()
        val jsonStr = medicalSheetToJson(sheet).toString()
        val payload = crypto.encryptToPayload(jsonStr.toByteArray(Charsets.UTF_8))
        val userSalt = crypto.getOrCreateUserSalt()

        val res = apiClient.uploadVaultDocument(
            docType = "medical_sheet",
            ciphertext = payload.ciphertext,
            iv = payload.iv,
            authTag = payload.authTag,
            userSalt = userSalt,
            title = "Ficha Médica de Emergencia"
        )
        android.util.Log.d("VaultRepo", "pushMedicalSheetToBackend result: ${res.getOrNull()?.statusCode}")
    }

    private fun persistDocuments(docs: List<BikerDocument>) {
        val array = JSONArray()
        docs.forEach { array.put(documentToJson(it)) }
        val encryptedDocs = crypto.encrypt(array.toString())
        prefs.edit().putString(KEY_DOCS, encryptedDocs).apply()
    }

    private fun persistMedicalSheet(sheet: EmergencyMedicalSheet) {
        val obj = medicalSheetToJson(sheet)
        val encryptedMed = crypto.encrypt(obj.toString())
        prefs.edit().putString(KEY_MEDICAL, encryptedMed).apply()
    }

    private fun documentToJson(doc: BikerDocument): JSONObject {
        val obj = JSONObject()
        obj.put("id", doc.id)
        obj.put("type", doc.type.name)
        obj.put("title", doc.title)
        obj.put("folio", doc.folio)
        obj.put("issuerOrInsurer", doc.issuerOrInsurer)
        obj.put("expirationDateMillis", doc.expirationDateMillis)
        obj.put("emergencyPhone", doc.emergencyPhone)
        obj.put("notes", doc.notes)
        obj.put("photoUri", doc.photoUri ?: "")
        obj.put("createdAtMillis", doc.createdAtMillis)
        obj.put("updatedAtMillis", doc.updatedAtMillis)
        return obj
    }

    private fun jsonToDocument(obj: JSONObject): BikerDocument {
        return BikerDocument(
            id = obj.optString("id", UUID.randomUUID().toString()),
            type = try {
                BikerDocumentType.valueOf(obj.optString("type", BikerDocumentType.OTHER.name))
            } catch (_: Exception) {
                BikerDocumentType.OTHER
            },
            title = obj.optString("title", ""),
            folio = obj.optString("folio", ""),
            issuerOrInsurer = obj.optString("issuerOrInsurer", ""),
            expirationDateMillis = obj.optLong("expirationDateMillis", 0L),
            emergencyPhone = obj.optString("emergencyPhone", ""),
            notes = obj.optString("notes", ""),
            photoUri = obj.optString("photoUri").takeIf { it.isNotBlank() },
            createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
            updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis())
        )
    }

    private fun medicalSheetToJson(sheet: EmergencyMedicalSheet): JSONObject {
        val obj = JSONObject()
        obj.put("bloodType", sheet.bloodType)
        obj.put("allergies", sheet.allergies)
        obj.put("chronicConditions", sheet.chronicConditions)
        obj.put("habitualMedications", sheet.habitualMedications)
        obj.put("isOrganDonor", sheet.isOrganDonor)
        obj.put("emergencyContactName", sheet.emergencyContactName)
        obj.put("emergencyContactRelationship", sheet.emergencyContactRelationship)
        obj.put("emergencyContactPhone", sheet.emergencyContactPhone)
        obj.put("medicalInsuranceName", sheet.medicalInsuranceName)
        obj.put("insurancePolicyNumber", sheet.insurancePolicyNumber)
        obj.put("showOnLockScreen", sheet.showOnLockScreen)
        obj.put("isSealedWithShamir", sheet.isSealedWithShamir)
        obj.put("lastUpdatedMillis", sheet.lastUpdatedMillis)
        return obj
    }

    private fun jsonToMedicalSheet(obj: JSONObject): EmergencyMedicalSheet {
        return EmergencyMedicalSheet(
            bloodType = obj.optString("bloodType", "O+"),
            allergies = obj.optString("allergies", "Ninguna conocida"),
            chronicConditions = obj.optString("chronicConditions", "Ninguna"),
            habitualMedications = obj.optString("habitualMedications", "Ninguna"),
            isOrganDonor = obj.optBoolean("isOrganDonor", true),
            emergencyContactName = obj.optString("emergencyContactName", ""),
            emergencyContactRelationship = obj.optString("emergencyContactRelationship", ""),
            emergencyContactPhone = obj.optString("emergencyContactPhone", ""),
            medicalInsuranceName = obj.optString("medicalInsuranceName", ""),
            insurancePolicyNumber = obj.optString("insurancePolicyNumber", ""),
            showOnLockScreen = obj.optBoolean("showOnLockScreen", true),
            isSealedWithShamir = obj.optBoolean("isSealedWithShamir", true),
            lastUpdatedMillis = obj.optLong("lastUpdatedMillis", System.currentTimeMillis())
        )
    }

    private fun parseIsoToMillis(isoString: String): Long {
        return try {
            Instant.parse(isoString).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
