package com.rutabikersync.app.feature.promo

import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.core.network.TokenCipherStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

class PromoBannerRepositoryTest {

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
        thread(isDaemon = true, name = "promo-test-server") {
            while (running.get()) {
                try {
                    val socket = serverSocket.accept()
                    socket.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        reader.readLine() ?: return@use
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val body = """{"banners":[
                            {"id":"b-camp","title":"Rodada Aniversario","subtitle":"Del 26 al 30","badgeText":"OFICIAL","accentColorHex":"#E5B869","ctaLabel":"Ver ruta","ctaTarget":"","frequency":"once_per_campaign","priority":10},
                            {"id":"b-day","title":"Promo Aceite","subtitle":"20% descuento","badgeText":"ALIADO","accentColorHex":"#34D399","ctaLabel":"Abrir","ctaTarget":"https://rutabikersync.com","frequency":"once_per_day","priority":5}
                        ]}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val headers =
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
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
    fun `respeta frecuencia once_per_campaign y once_per_day tras marcar como visto`() = runBlocking {
        val repo = PromoBannerRepository(context = null)
        val apiClient = ClubApiClient(baseUrl = baseUrl, tokenStore = InMemoryTokenStore())
        val t0 = 1_000_000_000_000L

        val initial = repo.fetchEligibleBanners(apiClient, nowMs = t0)
        assertEquals(2, initial.size)

        repo.markAllSeen(initial, nowMs = t0)

        // 12 horas después: ninguno debe aparecer
        val after12h = repo.fetchEligibleBanners(apiClient, nowMs = t0 + (12L * 3600L * 1000L))
        assertTrue(after12h.isEmpty())

        // 25 horas después: solo vuelve b-day (once_per_day); b-camp sigue oculto
        val after25h = repo.fetchEligibleBanners(apiClient, nowMs = t0 + (25L * 3600L * 1000L))
        assertEquals(1, after25h.size)
        assertEquals("b-day", after25h.first().id)
    }
}
