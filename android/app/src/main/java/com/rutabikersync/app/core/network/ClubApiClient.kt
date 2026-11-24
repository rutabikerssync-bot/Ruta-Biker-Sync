package com.rutabikersync.app.core.network

import com.rutabikersync.app.BuildConfig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP nativo para comunicar la app Android con el backend Fastify + PostgreSQL (BACKEND_LICENSING.md §8).
 * Soporta autenticación con Authorization: Bearer <accessToken> (D35) y cabecera x-user-id de respaldo.
 */
class ClubApiClient(
    baseUrl: String = BuildConfig.BASE_URL,
    val tokenStore: TokenCipherStore = KeystoreEncryptedTokenStore()
) {
    var baseUrl: String = baseUrl
        set(value) {
            field = normalizeAndValidateBaseUrl(value)
        }

    init {
        this.baseUrl = baseUrl
    }
    companion object {
        /**
         * URL base por variante/build (T-02). Debug: localhost. Release: HTTPS (TODO_EXTERNAL:URL_PROD).
         */
        val DEFAULT_BASE_URL: String get() = BuildConfig.BASE_URL

        internal fun normalizeAndValidateBaseUrl(value: String): String {
            val normalized = value.trim().trimEnd('/')
            require(normalized.isNotBlank()) { "BASE_URL must not be blank" }
            if (!BuildConfig.DEBUG && !normalized.startsWith("https://", ignoreCase = true)) {
                throw IllegalArgumentException("Release builds require an https BASE_URL (T-02/B1-4)")
            }
            return normalized
        }

        /**
         * Estrategia de configuración de URL base centralizada:
         * - Desarrollo USB con adb reverse: "http://127.0.0.1:3000"
         * - Desarrollo Wi-Fi local: "http://192.168.1.144:3000" (cambiar por IP de la máquina)
         * - Emulador oficial Android: "http://10.0.2.2:3000"
         * - Staging / Producción: "https://api.rutabikersync.com"
         */

        @Volatile
        private var instance: ClubApiClient? = null

        fun getInstance(): ClubApiClient {
            return instance ?: synchronized(this) {
                instance ?: ClubApiClient().also { instance = it }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkHealth(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/health", null)
    }

    /** Roster enriquecido del mapa en vivo (nombre, avatar y rol reales). */
    suspend fun fetchRideRoster(rideId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/rides/$rideId/participants", null)
    }

    /**
     * P0 auditoría: manifiesto firmado por el backend. Contiene la clave de rodada
     * estable (`rideSecretKeyHex`) y las claves públicas Ed25519 del emisor.
     * Nunca se almacena en disco: se valida en memoria y se descarta al cerrar la rodada.
     */
    suspend fun fetchRideManifest(rideId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/rides/$rideId/manifest", null)
    }

    /**
     * P0 cierre final: publica la clave pública Ed25519 real generada por
     * `Ed25519SosCrypto`. El backend la devuelve dentro del manifiesto firmado
     * para que los demás pilotos puedan verificar nuestros SOS/ACK.
     */
    suspend fun publishSosPublicKey(publicKeyBase64: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest(
            "PATCH",
            "/v1/users/me/sos-public-key",
            buildJsonObject { put("publicKeyBase64", publicKeyBase64) }
        )
    }

    // accessToken (30 min) vive exclusivamente en memoria volátil (D40)
    @Volatile
    var accessToken: String? = null

    // refreshToken (30 días) se cifra/descifra con AES-256-GCM en AndroidKeyStore (D40)
    var refreshToken: String?
        get() = tokenStore.loadRefreshToken()
        set(value) {
            if (value.isNullOrBlank()) {
                tokenStore.clear()
            } else {
                tokenStore.saveRefreshToken(value)
            }
        }

    var currentUserId: String? = null

    var appContext: android.content.Context? = null

    fun initContext(context: android.content.Context) {
        appContext = context.applicationContext
        ensureAuthenticatedUser()
    }

    fun ensureAuthenticatedUser() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences("rbs_auth_prefs", android.content.Context.MODE_PRIVATE)
            val savedUid = prefs.getString("user_id", null)
            if (!savedUid.isNullOrBlank() && (currentUserId.isNullOrBlank() || currentUserId?.startsWith("u-") == true)) {
                currentUserId = savedUid
            }
        } catch (_: Throwable) {}
    }

    suspend fun attemptSilentReauth(): Boolean {
        // 1. Intentar refresh si existe refreshToken (D40)
        val rt = refreshToken
        if (!rt.isNullOrBlank()) {
            val refRes = refreshSession(rt)
            if (refRes.getOrNull()?.isSuccess == true && !accessToken.isNullOrBlank()) {
                return true
            }
        }
        // 2. Intentar silent login si hay usuario autenticado en preferencias privadas
        val ctx = appContext ?: return false
        return try {
            val prefs = ctx.getSharedPreferences("rbs_auth_prefs", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("is_authenticated", false)) return false
            val email = prefs.getString("user_email", null) ?: return false
            val displayName = prefs.getString("user_display_name", null) ?: email.substringBefore("@")
            val isLeader = prefs.getString("account_type", "") == "LEADER"
            val loginRes = login(
                email = email,
                password = null,
                displayName = displayName,
                countryCode = "VE",
                acceptedTermsAndConvivencia = true,
                activateLeaderDemo = isLeader
            )
            val success = loginRes.getOrNull()?.isSuccess == true
            if (success) {
                loginRes.getOrNull()?.body?.let { b ->
                    val backendUid: String? = (b["user"] as? JsonObject)?.get("id")?.let { elem ->
                        runCatching { elem.jsonPrimitive.contentOrNull }.getOrNull()
                    } ?: runCatching { b["token"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    if (!backendUid.isNullOrBlank()) {
                        currentUserId = backendUid
                        prefs.edit().putString("user_id", backendUid).apply()
                    }
                }
            }
            success
        } catch (_: Throwable) {
            false
        }
    }

    data class ApiResponse(
        val statusCode: Int,
        val body: JsonObject?,
        val retryAfterSeconds: Long? = null
    ) {
        val isSuccess: Boolean get() = statusCode in 200..299
    }

    suspend fun login(
        email: String,
        password: String?,
        displayName: String,
        countryCode: String,
        acceptedTermsAndConvivencia: Boolean,
        activateLeaderDemo: Boolean
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("email", email)
            if (!password.isNullOrBlank()) put("password", password)
            put("displayName", displayName)
            put("countryCode", countryCode)
            put("acceptedTermsAndConvivencia", acceptedTermsAndConvivencia)
            put("activateLeaderDemo", activateLeaderDemo)
        }
        val res = executeRequest("POST", "/v1/auth/login", payload)
        res.getOrNull()?.let { apiRes ->
            if (apiRes.isSuccess && apiRes.body != null) {
                apiRes.body["accessToken"]?.let {
                    accessToken = (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: accessToken
                }
                apiRes.body["refreshToken"]?.let {
                    val rt = (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (!rt.isNullOrBlank()) refreshToken = rt
                }
                val backendUid: String? = (apiRes.body["user"] as? JsonObject)?.get("id")?.let { elem ->
                    runCatching { elem.jsonPrimitive.contentOrNull }.getOrNull()
                } ?: runCatching { apiRes.body["token"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                if (!backendUid.isNullOrBlank()) {
                    currentUserId = backendUid
                }
            }
        }
        res
    }

    suspend fun refreshSession(token: String? = refreshToken): Result<ApiResponse> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("refreshToken", token.orEmpty())
            }
            val res = executeRequest("POST", "/v1/auth/refresh", payload)
            res.getOrNull()?.let { apiRes ->
                if (apiRes.isSuccess && apiRes.body != null) {
                    apiRes.body["accessToken"]?.let {
                        accessToken = (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: accessToken
                    }
                    apiRes.body["refreshToken"]?.let {
                        refreshToken = (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: refreshToken
                    }
                }
            }
            res
        }

    suspend fun logout(token: String? = refreshToken): Result<ApiResponse> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("refreshToken", token.orEmpty())
            }
            val res = executeRequest("POST", "/v1/auth/logout", payload)
            if (res.getOrNull()?.isSuccess == true) {
                accessToken = null
                tokenStore.clear()
            }
            res
        }

    suspend fun getMyProfile(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/users/me", null)
    }

    suspend fun checkAppVersion(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/app/version", null)
    }

    suspend fun updateEnrichedProfile(profile: com.rutabikersync.app.feature.auth.UserProfile): Result<ApiResponse> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("aliasBiker", profile.aliasBiker)
                put("pinColorHex", profile.pinColorHex)
                put("motorcycleBrand", profile.motorcycleBrand)
                put("motorcycleModel", profile.motorcycleModel)
                if (profile.motorcycleDisplacement > 0) put("motorcycleDisplacement", profile.motorcycleDisplacement)
                if (profile.motorcycleYear > 0) put("motorcycleYear", profile.motorcycleYear)
                if (profile.motorcycleColor.isNotBlank()) put("motorcycleColor", profile.motorcycleColor)
                if (profile.motorcycleSerial.isNotBlank()) put("motorcycleSerial", profile.motorcycleSerial)
                put("profileVisibility", profile.profileVisibility.name)
                put("motorcycleVisibility", profile.motorcycleVisibility.name)
                if (!profile.photoBase64.isNullOrBlank()) put("photoBase64", profile.photoBase64)
                if (!profile.motorcyclePhotoBase64.isNullOrBlank()) put("motorcyclePhotoBase64", profile.motorcyclePhotoBase64)
            }
            executeRequest("PUT", "/v1/users/profile", payload)
        }

    suspend fun uploadProfilePhoto(photoBase64: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("photoBase64", photoBase64)
        }
        executeRequest("POST", "/v1/users/photo", payload)
    }

    suspend fun uploadMotorcyclePhoto(photoBase64: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("photoBase64", photoBase64)
        }
        executeRequest("POST", "/v1/users/motorcycle-photo", payload)
    }

    suspend fun submitSupportReport(
        subject: String,
        message: String,
        screenshotBase64: String? = null
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("subject", subject)
            put("message", message)
            if (!screenshotBase64.isNullOrBlank()) {
                put("screenshotBase64", screenshotBase64)
            }
        }
        executeRequest("POST", "/v1/support/report", payload)
    }

    suspend fun updateMyProfile(
        displayName: String?,
        bikeModel: String?,
        mapColor: String?,
        avatarWebpBase64: String? = null
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            if (displayName != null) put("displayName", displayName)
            if (bikeModel != null) put("bikeModel", bikeModel)
            if (mapColor != null) put("mapColor", mapColor)
            if (avatarWebpBase64 != null) put("avatarWebpBase64", avatarWebpBase64)
        }
        executeRequest("PATCH", "/v1/users/me", payload)
    }

    suspend fun getMyGroups(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/groups", null)
    }

    /**
     * Detalle del club con propagación por versión. Si el servidor no tiene
     * cambios devuelve 304 y el cliente conserva su estado local.
     */
    suspend fun getGroupDetail(
        groupId: String,
        sinceVersion: Int? = null
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val suffix = sinceVersion?.let { "?since=$it" }.orEmpty()
        executeRequest("GET", "/v1/groups/$groupId$suffix", null)
    }

    // -----------------------------------------------------------------------
    // Fase 5: rutas de club y RSVP (backend Fase 3). Las respuestas se consumen
    // desde ClubRoutesRepository, que aplica la estrategia cache-first.
    // -----------------------------------------------------------------------

    suspend fun getClubRoutes(groupId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/groups/$groupId/routes", null)
    }

    suspend fun createClubRoute(
        groupId: String,
        title: String,
        startsAt: String,
        description: String? = null
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("title", title)
            put("startsAt", startsAt)
            if (description != null) put("description", description)
        }
        executeRequest("POST", "/v1/groups/$groupId/routes", payload)
    }

    suspend fun updateClubRoute(
        routeId: String,
        title: String? = null,
        startsAt: String? = null,
        description: String? = null,
        updateDescription: Boolean = false
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            if (title != null) put("title", title)
            if (startsAt != null) put("startsAt", startsAt)
            if (updateDescription) {
                if (description == null) put("description", JsonNull) else put("description", description)
            }
        }
        executeRequest("PATCH", "/v1/routes/$routeId", payload)
    }

    suspend fun getRsvps(routeId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/routes/$routeId/rsvps", null)
    }

    suspend fun getRideRoster(routeId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/routes/$routeId/roster", null)
    }

    suspend fun postRouteArrival(routeId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("POST", "/v1/routes/$routeId/arrive", buildJsonObject {})
    }

    suspend fun startClubRoute(routeId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("POST", "/v1/routes/$routeId/start", buildJsonObject {})
    }

    suspend fun arriveDestination(routeId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("POST", "/v1/routes/$routeId/arrive-destination", buildJsonObject {})
    }

    suspend fun startReturnRoute(routeId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("POST", "/v1/routes/$routeId/return", buildJsonObject {})
    }

    suspend fun completeRoute(routeId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("POST", "/v1/routes/$routeId/complete", buildJsonObject {})
    }

    /**
     * El backend es idempotente: 201 la primera vez, 200 con `changed=false`
     * en reintentos. Ambos cuentan como éxito para el outbox.
     */
    suspend fun postRsvp(routeId: String, status: String): Result<ApiResponse> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject { put("status", status) }
            executeRequest("POST", "/v1/routes/$routeId/rsvp", payload)
        }

    suspend fun createGroup(
        countryCode: String,
        name: String,
        logoPhashHex: String
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("countryCode", countryCode)
            put("name", name)
            put("logoPhashHex", logoPhashHex)
        }
        executeRequest("POST", "/v1/groups", payload)
    }

    suspend fun getGroupMembers(groupId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/groups/$groupId/members", null)
    }

    suspend fun addGroupLeader(groupId: String, userId: String): Result<ApiResponse> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject { put("userId", userId) }
            executeRequest("POST", "/v1/groups/$groupId/leaders", payload)
        }

    suspend fun removeGroupLeader(groupId: String, leaderUserId: String): Result<ApiResponse> =
        withContext(Dispatchers.IO) {
            executeRequest("DELETE", "/v1/groups/$groupId/leaders/$leaderUserId", null)
        }

    suspend fun expelMember(groupId: String, memberUserId: String): Result<ApiResponse> =
        withContext(Dispatchers.IO) {
            executeRequest("DELETE", "/v1/groups/$groupId/members/$memberUserId", null)
        }

    suspend fun getMyInvitations(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/invitations/me", null)
    }

    suspend fun getGroupInvitations(groupId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/groups/$groupId/invitations", null)
    }

    suspend fun updateMemberRoleOrStatus(
        groupId: String,
        memberUserId: String,
        statusKind: String?,
        clubRole: String?
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            if (statusKind != null) put("statusKind", statusKind)
            if (clubRole != null) {
                put("clubRole", clubRole)
            } else {
                put("clubRole", kotlinx.serialization.json.JsonNull)
            }
        }
        executeRequest("PATCH", "/v1/groups/$groupId/members/$memberUserId", payload)
    }

    suspend fun updateGroupProfile(
        groupId: String,
        colorsDescription: String?,
        clubSiglas: String?,
        traditionType: String?,
        directivaScope: String?,
        directivaRegion: String?,
        motto: String?,
        city: String?,
        foundationDate: String?,
        logoUrl: String? = null,
        tier: String? = null
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        // Espejo D2: las columnas de la 0016 siguen siendo la fuente de verdad;
        // el JSON viaja junto a ellas para alimentar club_profile.
        val profile = buildJsonObject {
            if (colorsDescription != null) put("colorsDescription", colorsDescription)
            if (clubSiglas != null) put("clubSiglas", clubSiglas)
            if (traditionType != null) put("traditionType", traditionType)
            if (directivaScope != null) put("directivaScope", directivaScope)
            if (directivaRegion != null) put("directivaRegion", directivaRegion)
            if (motto != null) put("motto", motto)
            if (city != null) put("city", city)
            if (foundationDate != null) put("foundationDate", foundationDate)
            if (logoUrl != null) put("logoUrl", logoUrl)
            if (tier != null) put("tier", tier)
        }
        val payload = buildJsonObject {
            if (colorsDescription != null) put("colorsDescription", colorsDescription)
            if (clubSiglas != null) put("clubSiglas", clubSiglas)
            if (traditionType != null) put("traditionType", traditionType)
            if (directivaScope != null) put("directivaScope", directivaScope)
            if (directivaRegion != null) put("directivaRegion", directivaRegion)
            if (motto != null) put("motto", motto)
            if (city != null) put("city", city)
            if (foundationDate != null) put("foundationDate", foundationDate)
            if (logoUrl != null) put("logoUrl", logoUrl)
            if (tier != null) put("tier", tier)
            put("profile", profile)
        }
        executeRequest("PUT", "/v1/groups/$groupId/profile", payload)
    }

    suspend fun sendInvitation(
        groupId: String,
        invitedUserId: String? = null,
        statusKind: String,
        invitationType: String = "hermandad",
        customId: String? = null
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            if (invitedUserId != null && invitedUserId.isNotBlank()) {
                put("invitedUserId", invitedUserId)
            }
            put("statusKind", statusKind)
            put("invitationType", invitationType)
            if (customId != null && customId.isNotBlank()) {
                put("customId", customId)
            }
        }
        executeRequest("POST", "/v1/groups/$groupId/invitations", payload)
    }

    suspend fun acceptInvitation(
        invitationId: String,
        membership: Boolean,
        location: Boolean,
        emergencyPackage: Boolean,
        notifications: Boolean
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put(
                "consents",
                buildJsonObject {
                    put("membership", membership)
                    put("location", location)
                    put("emergencyPackage", emergencyPackage)
                    put("notifications", notifications)
                }
            )
        }
        executeRequest("POST", "/v1/invitations/$invitationId/accept", payload)
    }

    suspend fun declineInvitation(invitationId: String): Result<ApiResponse> =
        withContext(Dispatchers.IO) {
            executeRequest("POST", "/v1/invitations/$invitationId/decline", buildJsonObject {})
        }

    suspend fun getConsents(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/users/me/consents", null)
    }

    suspend fun updateConsent(
        kind: String,
        granted: Boolean,
        groupId: String? = null,
        policyVersion: String = "1.0"
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("kind", kind)
            put("granted", granted)
            if (groupId != null) put("groupId", groupId)
            put("policyVersion", policyVersion)
        }
        executeRequest("POST", "/v1/users/me/consents", payload)
    }

    suspend fun exportUserData(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${baseUrl.trimEnd('/')}/v1/users/me/export")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
                accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                currentUserId?.let { setRequestProperty("x-user-id", it) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code in 200..299) {
                Result.success(text)
            } else {
                Result.failure(IllegalStateException("HTTP $code: $text"))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun deleteUserAccount(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("DELETE", "/v1/users/me", null)
    }

    suspend fun createModerationReport(
        groupId: String,
        reportedUserId: String,
        rideId: String?,
        reason: String,
        note: String?
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("reportedUserId", reportedUserId)
            rideId?.let { put("rideId", it) }
            put("reason", reason)
            note?.let { put("note", it) }
        }
        executeRequest("POST", "/v1/groups/$groupId/reports", body)
    }

    suspend fun getModerationReports(groupId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/groups/$groupId/reports", null)
    }

    suspend fun resolveModerationReport(
        groupId: String,
        reportId: String,
        resolution: String?,
        state: String
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            resolution?.let { put("resolution", it) }
            put("state", state)
        }
        executeRequest("POST", "/v1/groups/$groupId/reports/$reportId/resolve", body)
    }

    suspend fun getLeaderVotes(groupId: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/groups/$groupId/votes", null)
    }

    suspend fun proposeLeaderVote(
        groupId: String,
        proposal: String,
        targetId: String?
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("proposal", proposal)
            targetId?.let { put("targetId", it) }
        }
        executeRequest("POST", "/v1/groups/$groupId/votes", body)
    }

    suspend fun castLeaderVoteBallot(
        voteId: String,
        approve: Boolean
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("approve", approve)
        }
        executeRequest("POST", "/v1/votes/$voteId/ballot", body)
    }

    // -------------------------------------------------------------------------
    // Fase 5 · Bóveda Biker E2EE (RULES.md §15, MEMORY.md D69)
    // -------------------------------------------------------------------------

    suspend fun uploadVaultDocument(
        docType: String,
        ciphertext: String,
        iv: String,
        authTag: String,
        userSalt: String,
        title: String? = null
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("docType", docType)
            put("ciphertext", ciphertext)
            put("iv", iv)
            put("authTag", authTag)
            put("userSalt", userSalt)
            title?.let { put("title", it) }
        }
        executeRequest("POST", "/v1/vault/documents", body)
    }

    suspend fun listVaultDocuments(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/vault/documents", null)
    }

    suspend fun getVaultDocument(id: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/vault/documents/$id", null)
    }

    suspend fun deleteVaultDocument(id: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("DELETE", "/v1/vault/documents/$id", null)
    }

    suspend fun requestRecoveryToken(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("POST", "/v1/vault/recovery/token", kotlinx.serialization.json.buildJsonObject {})
    }

    suspend fun uploadRecoveryKey(
        recoveryToken: String,
        encryptedMasterKey: String,
        iv: String,
        authTag: String,
        pbkdf2Salt: String,
        pbkdf2Iterations: Int = 100_000
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("encryptedMasterKey", encryptedMasterKey)
            put("iv", iv)
            put("authTag", authTag)
            put("pbkdf2Salt", pbkdf2Salt)
            put("pbkdf2Iterations", pbkdf2Iterations)
        }
        executeRequest("POST", "/v1/vault/recovery", body, mapOf("x-recovery-token" to recoveryToken))
    }

    suspend fun getRecoveryKey(recoveryToken: String): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/vault/recovery", null, mapOf("x-recovery-token" to recoveryToken))
    }

    suspend fun shareMedicalSheet(
        rideId: String,
        groupId: String,
        ciphertext: String,
        iv: String,
        authTag: String,
        durationHours: Int = 12
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("rideId", rideId)
            put("groupId", groupId)
            put("ciphertext", ciphertext)
            put("iv", iv)
            put("authTag", authTag)
            put("durationHours", durationHours)
        }
        executeRequest("POST", "/v1/vault/medical/share", body)
    }

    suspend fun getEmergencyMedicalSheet(
        targetUserId: String,
        rideId: String
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/vault/medical/$targetUserId?rideId=$rideId", null)
    }

    suspend fun registerFcmToken(
        token: String,
        platform: String = "android"
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("token", token)
            put("platform", platform)
        }
        executeRequest("POST", "/v1/users/fcm-token", body)
    }

    suspend fun sendTelemetryHeartbeat(
        lat: Double,
        lng: Double,
        heading: Double = 0.0,
        speedKmh: Double = 0.0
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("latitude", lat)
            put("longitude", lng)
            put("heading", heading)
            put("speedKmh", speedKmh)
        }
        executeRequest("POST", "/v1/telemetry/heartbeat", body)
    }

    suspend fun getNearbySosAlerts(
        lat: Double? = null,
        lng: Double? = null,
        radiusKm: Double = 5.0
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val query = if (lat != null && lng != null) "?lat=$lat&lng=$lng&radiusKm=$radiusKm" else "?radiusKm=$radiusKm"
        executeRequest("GET", "/v1/sos/nearby$query", null)
    }

    suspend fun triggerSos(
        lat: Double,
        lng: Double,
        hasMedicalSheet: Boolean = false,
        radiusKm: Double = 5.0,
        notes: String? = null,
        source: String = "app",
        meshPacketId: String? = null
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("latitude", lat)
            put("longitude", lng)
            put("hasMedicalSheet", hasMedicalSheet)
            put("radiusKm", radiusKm)
            notes?.let { put("notes", it) }
            put("source", source)
            meshPacketId?.let { put("meshPacketId", it) }
        }
        executeRequest("POST", "/v1/sos", body)
    }

    suspend fun revokeSharedMedicalSheet(
        rideId: String
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("DELETE", "/v1/vault/medical/share?rideId=$rideId", null)
    }

    private suspend fun executeRequest(
        method: String,
        path: String,
        body: JsonObject?,
        extraHeaders: Map<String, String> = emptyMap(),
        isRetry: Boolean = false
    ): Result<ApiResponse> {
        return try {
            ensureAuthenticatedUser()
            val safeBaseUrl = normalizeAndValidateBaseUrl(baseUrl)
            val url = URL("$safeBaseUrl$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/json")
                accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                currentUserId?.let { setRequestProperty("x-user-id", it) }
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }

            if (body != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }
            }

            val code = conn.responseCode

            // Reintento transparente ante 401 UNAUTHORIZED (D35, D40)
            if (code == 401 && !isRetry && path != "/v1/auth/login" && path != "/v1/auth/refresh") {
                val reauthed = attemptSilentReauth()
                if (reauthed) {
                    return executeRequest(method, path, body, extraHeaders, isRetry = true)
                }
            }
            val retryAfterHeader = conn.getHeaderField("Retry-After")?.toLongOrNull()
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            val parsedJson = if (text.isNotBlank()) {
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            } else {
                null
            }
            val retryAfter = retryAfterHeader
                ?: parsedJson?.get("retryAfter")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            Result.success(ApiResponse(code, parsedJson, retryAfter))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
