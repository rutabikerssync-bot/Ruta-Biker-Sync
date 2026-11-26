package com.rutabikersync.app.feature.groups

import androidx.lifecycle.viewModelScope
import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.core.network.TokenCipherStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class Phase1ViewModelPollingAndRidesTest {

    private class InMemoryTokenStore : TokenCipherStore {
        override val isUsingHardwareAndroidKeystore: Boolean = false
        override val requiresRelogin: Boolean = false
        private var refreshToken: String? = null
        override fun saveRefreshToken(refreshToken: String) {
            this.refreshToken = refreshToken
        }
        override fun loadRefreshToken(): String? = refreshToken
        override fun clear() {
            refreshToken = null
        }
    }

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var serverSocket: ServerSocket
    private lateinit var serverThread: Thread
    private val running = AtomicBoolean(true)
    private lateinit var baseUrl: String
    private val groupsGetCount = AtomicInteger(0)
    private val ridesGetCount = AtomicInteger(0)
    private val createdViewModels = mutableListOf<Phase1ViewModel>()

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        groupsGetCount.set(0)
        ridesGetCount.set(0)
        running.set(true)

        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
        serverThread = thread(isDaemon = true, name = "test-http-stub") {
            while (running.get()) {
                try {
                    val socket = serverSocket.accept()
                    socket.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        val requestLine = reader.readLine() ?: return@use
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val path = requestLine.split(" ").getOrNull(1).orEmpty()
                        val body = when {
                            path == "/health" -> """{"status":"ok"}"""
                            path == "/v1/groups" -> {
                                groupsGetCount.incrementAndGet()
                                """{"groups":[]}"""
                            }
                            path.startsWith("/v1/rides/") -> {
                                ridesGetCount.incrementAndGet()
                                """{"participants":[]}"""
                            }
                            else -> """{}"""
                        }
                        val bodyBytes = body.toByteArray(Charsets.UTF_8)
                        val headers =
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${bodyBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        s.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII))
                            write(bodyBytes)
                            flush()
                        }
                    }
                } catch (_: SocketException) {
                    // Server closed during tearDown
                }
            }
        }
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        running.set(false)
        runCatching { serverSocket.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `en una hora virtual Phase1ViewModel no supera 60 GET a v1 groups y no consulta v1 rides en polling`() {
        val apiClient = ClubApiClient(baseUrl = baseUrl, tokenStore = InMemoryTokenStore())
        val repo = ClubRepository(apiClient = apiClient)
        val vm = Phase1ViewModel(
            repository = repo,
            syncDispatcher = testDispatcher
        ).also { createdViewModels.add(it) }

        val stepMs = 5_000L
        val totalSteps = (3_600_000L / stepMs).toInt()
        for (step in 0 until totalSteps) {
            testDispatcher.scheduler.advanceTimeBy(stepMs)
            testDispatcher.scheduler.runCurrent()
            if (step % 13 == 0) {
                Thread.sleep(5)
            }
        }
        Thread.sleep(25)
        testDispatcher.scheduler.runCurrent()

        val count = groupsGetCount.get()
        assertTrue(
            "Se esperaban entre 1 y 60 GET /v1/groups en 1 hora, pero hubo $count (vm=${vm.hashCode()})",
            count in 1..60
        )
        assertEquals(
            "El polling de grupos no debe disparar peticiones a /v1/rides",
            0,
            ridesGetCount.get()
        )
    }

    @Test
    fun `syncWithBackend aplica debounce ante rafagas de llamadas`() {
        val apiClient = ClubApiClient(baseUrl = baseUrl, tokenStore = InMemoryTokenStore())
        val repo = ClubRepository(apiClient = apiClient)
        val vm = Phase1ViewModel(
            repository = repo,
            syncDispatcher = testDispatcher
        ).also { createdViewModels.add(it) }

        testDispatcher.scheduler.advanceTimeBy(Phase1ViewModel.SYNC_DEBOUNCE_MS + 100L)
        testDispatcher.scheduler.runCurrent()
        Thread.sleep(25)
        val baseline = groupsGetCount.get()

        repeat(8) {
            vm.syncWithBackend()
            testDispatcher.scheduler.advanceTimeBy(50L)
        }
        testDispatcher.scheduler.advanceTimeBy(Phase1ViewModel.SYNC_DEBOUNCE_MS + 100L)
        testDispatcher.scheduler.runCurrent()
        Thread.sleep(25)

        val afterBurst = groupsGetCount.get()
        assertEquals(
            "La ráfaga de syncWithBackend debe consolidarse en 1 solo GET /v1/groups",
            baseline + 1,
            afterBurst
        )
    }

    @Test
    fun `peticiones a v1 rides se ejecutan bajo demanda cuando corresponde`() = runBlocking {
        val apiClient = ClubApiClient(baseUrl = baseUrl, tokenStore = InMemoryTokenStore())
        assertEquals(0, ridesGetCount.get())

        val rosterRes = apiClient.fetchRideRoster("ride-42")
        val manifestRes = apiClient.fetchRideManifest("ride-42")

        assertTrue(rosterRes.isSuccess)
        assertTrue(manifestRes.isSuccess)
        assertEquals(2, ridesGetCount.get())
    }
}
