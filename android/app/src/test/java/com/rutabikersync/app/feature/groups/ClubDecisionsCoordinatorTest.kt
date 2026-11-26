package com.rutabikersync.app.feature.groups

import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.core.network.TokenCipherStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ClubDecisionsCoordinatorTest {

    private class InMemoryTokenStore : TokenCipherStore {
        override val isUsingHardwareAndroidKeystore: Boolean = false
        override val requiresRelogin: Boolean = false
        private var token: String? = null
        override fun saveRefreshToken(refreshToken: String) {
            token = refreshToken
        }
        override fun loadRefreshToken(): String? = token
        override fun clear() {
            token = null
        }
    }

    private lateinit var serverSocket: ServerSocket
    private val running = AtomicBoolean(true)
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        running.set(true)
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
        thread(isDaemon = true, name = "decisions-test-server") {
            while (running.get()) {
                try {
                    val socket = serverSocket.accept()
                    socket.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        val requestLine = reader.readLine() ?: return@use
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        if (contentLength > 0) {
                            val buf = CharArray(contentLength)
                            reader.read(buf, 0, contentLength)
                        }
                        val parts = requestLine.split(" ")
                        val method = parts.getOrNull(0).orEmpty()
                        val path = parts.getOrNull(1).orEmpty()
                        val (code, body) = when {
                            method == "POST" && path.endsWith("/custom-roles") ->
                                201 to """{"id":"role-1","groupId":"grp-1","title":"Fotógrafo","badgeIcon":"camera","canCreateRide":false,"canModerate":false}"""
                            method == "GET" && path.endsWith("/custom-roles") ->
                                200 to """{"roles":[{"id":"role-1","groupId":"grp-1","title":"Fotógrafo","badgeIcon":"camera","canCreateRide":false,"canModerate":false}]}"""
                            method == "POST" && path.endsWith("/decisions") ->
                                201 to """{"id":"dec-1","groupId":"grp-1","title":"Ruta domingo","description":"","category":"route","options":["Tovar","Galipán"],"allowedAudiences":["FULL_MEMBERS","PROSPECTS"],"minQuorumPercent":0,"state":"open","closesAt":"2026-11-28T00:00:00Z"}"""
                            method == "GET" && path.endsWith("/decisions") ->
                                200 to """{"decisions":[{"id":"dec-1","groupId":"grp-1","title":"Ruta domingo","description":"","category":"route","options":["Tovar","Galipán"],"allowedAudiences":["FULL_MEMBERS","PROSPECTS"],"minQuorumPercent":0,"state":"closed","hasVoted":true,"totalVotes":2,"eligibleCount":3,"quorumMet":true,"tallies":[2,0],"winningOptionIndex":0,"closesAt":"2026-11-28T00:00:00Z"}]}"""
                            method == "POST" && path.endsWith("/cast") ->
                                200 to """{"decisionId":"dec-1","recorded":true}"""
                            method == "POST" && path.endsWith("/close") ->
                                200 to """{"id":"dec-1","state":"closed"}"""
                            else -> 200 to """{}"""
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val headers =
                            "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        s.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: SocketException) {
                }
            }
        }
    }

    @After
    fun tearDown() {
        running.set(false)
        runCatching { serverSocket.close() }
    }

    @Test
    fun `elegibilidad por audiencia excluye a guest y respeta directiva fundadores full y prospectos`() {
        val coordinator = ClubDecisionsCoordinator(ClubApiClient(baseUrl = baseUrl, tokenStore = InMemoryTokenStore()))
        val group = ClubGroupModel(
            id = "grp-1",
            countryCode = "VE",
            name = "Halcones",
            nameNormalized = "halcones",
            logoPhashHex = "00",
            principalLeaderId = "u-founder",
            leaderIds = listOf("u-founder", "u-dir"),
            members = listOf(
                ClubMemberModel("u-founder", "Fundador", "Moto", MemberStatusKind.FULL, ClubRole.PRESIDENT, true, true, PaymentSemaphore.GREEN, 10),
                ClubMemberModel("u-dir", "Directivo", "Moto", MemberStatusKind.FULL, ClubRole.VICE_PRESIDENT, true, false, PaymentSemaphore.GREEN, 8),
                ClubMemberModel("u-full", "Full", "Moto", MemberStatusKind.FULL, null, false, false, PaymentSemaphore.GREEN, 5),
                ClubMemberModel("u-pros", "Prospect", "Moto", MemberStatusKind.PROSPECT, null, false, false, PaymentSemaphore.GREEN, 2),
                ClubMemberModel("u-guest", "Guest", "Moto", MemberStatusKind.GUEST, null, false, false, PaymentSemaphore.GREEN, 0)
            ),
            tier = ClubTier.MC,
            foundationDate = "2020-01-01",
            colorsDescription = "Negro/Oro"
        )

        val onlyFounders = setOf(DecisionAudience.FOUNDERS)
        assertTrue(coordinator.isUserEligibleForDecision("u-founder", group, onlyFounders))
        assertFalse(coordinator.isUserEligibleForDecision("u-dir", group, onlyFounders))
        assertFalse(coordinator.isUserEligibleForDecision("u-full", group, onlyFounders))
        assertFalse(coordinator.isUserEligibleForDecision("u-guest", group, onlyFounders))

        val clubWide = setOf(DecisionAudience.FULL_MEMBERS, DecisionAudience.PROSPECTS)
        assertTrue(coordinator.isUserEligibleForDecision("u-full", group, clubWide))
        assertTrue(coordinator.isUserEligibleForDecision("u-pros", group, clubWide))
        assertFalse(coordinator.isUserEligibleForDecision("u-guest", group, clubWide))
        assertEquals(4, OFFICIAL_DECISION_TEMPLATES.size)
    }

    @Test
    fun `regla P7 prohíbe asignar cargo personalizado a guest antes de llamar red`() = runBlocking {
        val coordinator = ClubDecisionsCoordinator(ClubApiClient(baseUrl = baseUrl, tokenStore = InMemoryTokenStore()))
        val guest = ClubMemberModel("u-guest", "Guest", "Moto", MemberStatusKind.GUEST, null, false, false, PaymentSemaphore.GREEN, 0)
        val res = coordinator.assignCustomRole("grp-1", guest, "role-1")
        assertTrue(res.isFailure)
        assertEquals("GUEST_CANNOT_HOLD_ROLE", res.exceptionOrNull()?.message)
    }

    @Test
    fun `round trip HTTP de cargos personalizados y decisiones secretas actualiza StateFlows`() = runBlocking {
        val coordinator = ClubDecisionsCoordinator(ClubApiClient(baseUrl = baseUrl, tokenStore = InMemoryTokenStore()))

        val roleRes = coordinator.createCustomRole("grp-1", "Fotógrafo", "camera")
        assertTrue(roleRes.isSuccess)
        assertEquals(1, coordinator.customRoles.value.size)

        val decRes = coordinator.createDecision(
            groupId = "grp-1",
            title = "Ruta domingo",
            description = "",
            category = DecisionCategory.ROUTE,
            options = listOf("Tovar", "Galipán"),
            allowedAudiences = setOf(DecisionAudience.FULL_MEMBERS, DecisionAudience.PROSPECTS)
        )
        assertTrue(decRes.isSuccess)

        val castRes = coordinator.castSecretVote("grp-1", "dec-1", 0)
        assertTrue(castRes.isSuccess)
        val loaded = coordinator.decisions.value.first()
        assertTrue(loaded.hasVoted)
        assertEquals(0, loaded.winningOptionIndex)
        assertEquals(listOf(2, 0), loaded.tallies)
    }
}
