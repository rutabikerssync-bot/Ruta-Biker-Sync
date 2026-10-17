package com.rutabikersync.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
    var baseUrl: String = "http://10.0.2.2:3000"
) {
    private val json = Json { ignoreUnknownKeys = true }

    var accessToken: String? = null
    var refreshToken: String? = null
    var currentUserId: String? = null

    data class ApiResponse(
        val statusCode: Int,
        val body: JsonObject?
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
        executeRequest("POST", "/v1/auth/login", payload)
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
                refreshToken = null
            }
            res
        }

    suspend fun getMyProfile(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/users/me", null)
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

    suspend fun getMyInvitations(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        executeRequest("GET", "/v1/invitations/me", null)
    }

    suspend fun sendInvitation(
        groupId: String,
        invitedUserId: String,
        statusKind: String
    ): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("invitedUserId", invitedUserId)
            put("statusKind", statusKind)
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

    private fun executeRequest(
        method: String,
        path: String,
        body: JsonObject?
    ): Result<ApiResponse> {
        return try {
            val url = URL("${baseUrl.trimEnd('/')}$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/json")
                accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                currentUserId?.let { setRequestProperty("x-user-id", it) }
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
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            val parsedJson = if (text.isNotBlank()) {
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            } else {
                null
            }
            Result.success(ApiResponse(code, parsedJson))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
