package com.rutabikersync.app.core.crypto

import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.concurrent.thread

class CommunityManifestTest {

    private lateinit var server: ServerSocket
    @Volatile
    private var requestedPath: String? = null

    @Before
    fun setUp() {
        RideManifestKeyProvider.clear()
        RideManifestKeyProvider.installDiskCacheForTest(null)
        requestedPath = null
        server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    }

    @After
    fun tearDown() {
        RideManifestKeyProvider.clear()
        runCatching { server.close() }
    }

    private fun startSingleResponseServer(statusCode: Int, statusText: String, jsonBody: String) {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val requestLine = reader.readLine() ?: ""
                    requestedPath = requestLine.split(" ").getOrNull(1)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                    val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().write(headers.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            }
        }
    }

    @Test
    fun `Ruta Libre con manifiesto comunitario valido autoriza la malla BLE`() = runBlocking {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        val spkiB64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        val now = System.currentTimeMillis() / 1000L
        val pk1 = Base64.getEncoder().encodeToString("user-public-1".toByteArray())
        val pk2 = Base64.getEncoder().encodeToString("user-public-2".toByteArray())
        val secretHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val payloadJson = """
            {
              "v": 1,
              "type": "ride_manifest",
              "rideId": "ride-live-01",
              "hostGroupId": "community",
              "groupIds": ["community"],
              "hostLeaderId": "user-public-1",
              "participants": [
                {"uid": "user-public-1", "gid": "community", "statusKind": "full", "pk": "$pk1"},
                {"uid": "user-public-2", "gid": "community", "statusKind": "full", "pk": "$pk2"}
              ],
              "rideSecretKeyHex": "$secretHex",
              "iat": $now,
              "exp": ${now + 300},
              "kid": "k1"
            }
        """.trimIndent()
        val payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray())
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(kp.private)
            update(payloadB64.toByteArray())
        }.sign()
        val sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
        val responseBody = """{"manifest":"$payloadB64.$sigB64","publicKeys":{"k1":"$spkiB64"}}"""

        startSingleResponseServer(200, "OK", responseBody)

        val apiClient = ClubApiClient(baseUrl = "http://127.0.0.1:${server.localPort}")
        val authorized = RideManifestKeyProvider.resolve(
            rideId = "ride-live-01",
            currentUserId = "user-public-1",
            apiClient = apiClient,
            nowEpochSec = now,
            forceRefresh = true
        )

        assertNotNull(authorized)
        assertEquals("ride-live-01", authorized!!.rideId)
        assertEquals(2, authorized.authorizedShortIds.size)
        assertTrue(requestedPath == "/v1/community/manifest")
    }

    @Test
    fun `Ruta Libre sin manifiesto comunitario (403) falla cerrado`() = runBlocking {
        startSingleResponseServer(403, "Forbidden", """{"error":"COMMUNITY_VISIBILITY_REQUIRED"}""")

        val apiClient = ClubApiClient(baseUrl = "http://127.0.0.1:${server.localPort}")
        val authorized = RideManifestKeyProvider.resolve(
            rideId = "ride-live-01",
            currentUserId = "user-club-only",
            apiClient = apiClient,
            forceRefresh = true
        )

        assertNull(authorized)
        assertTrue(requestedPath == "/v1/community/manifest")
    }
}
