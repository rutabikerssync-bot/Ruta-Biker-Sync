package com.rutabikersync.app.core.clubroutes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fase 5 · regresiones del outbox de RSVP.
 *
 * Cubre los dos requisitos que faltaban tras el contrato de UI:
 *  - sin red → encola, al volver la red → sincroniza solo;
 *  - 403/409 → descarta sin mutar el estado local ni reintentar.
 *
 * Nota de alcance: `Phase1ViewModel.requestRsvp` sigue escribiendo el roster en
 * caché antes del ACK (preexistente de Fase 5). H3 solo eliminó esa mutación
 * optimista en transiciones de ciclo. No se toca aquí por instrucción explícita;
 * se propone como fix separado.
 */
class RsvpOutboxFase5RegressionTest {

    private class RecordingTransport(
        private vararg val results: RsvpSendResult
    ) : RsvpTransport {
        val sent = mutableListOf<Pair<String, String>>()
        private var index = 0

        override suspend fun sendRsvp(
            routeId: String,
            userId: String,
            status: String
        ): RsvpSendResult {
            sent += routeId to status
            return results.getOrNull(index++) ?: RsvpSendResult.Accepted
        }
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("No se encontró el fuente $relative")
    }

    @Test
    fun `offline encola y al volver la red sincroniza sin intervencion manual`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(RsvpOutboxEntity("ruta-1", "u-1", "confirmed"))
        assertEquals(1, dao.pendingCount())
        assertFalse(dao.getUnsynced().single().synced)

        val transport = RecordingTransport(RsvpSendResult.Error("Failed to connect"))
        val offline = RsvpOutboxSyncEngine(dao, transport).flush()
        assertTrue(offline is RsvpOutboxSyncEngine.SyncResult.Failure)
        assertEquals(1, dao.pendingCount())
        assertEquals("Failed to connect", dao.getUnsynced().single().lastError)

        val online = RecordingTransport(RsvpSendResult.Accepted)
        val result = RsvpOutboxSyncEngine(dao, online).flush()
        assertTrue(result is RsvpOutboxSyncEngine.SyncResult.Success)
        assertEquals(1, (result as RsvpOutboxSyncEngine.SyncResult.Success).syncedCount)
        assertEquals(0, dao.pendingCount())
        assertTrue(dao.getUnsynced().isEmpty())
        assertEquals(listOf("ruta-1" to "confirmed"), online.sent)
    }

    @Test
    fun `403 y 409 son terminales y no marcan el estado como sincronizado`() = runTest {
        for (reason in listOf("FORBIDDEN", "INVALID_RSVP_TRANSITION")) {
            val dao = InMemoryRsvpOutboxDao()
            dao.enqueue(RsvpOutboxEntity("ruta-2", "u-2", "confirmed"))

            val transport = RecordingTransport(RsvpSendResult.Rejected(reason))
            val result = RsvpOutboxSyncEngine(dao, transport).flush()

            assertTrue(result is RsvpOutboxSyncEngine.SyncResult.Success)
            val success = result as RsvpOutboxSyncEngine.SyncResult.Success
            assertEquals(1, success.droppedCount)
            assertEquals(0, success.syncedCount)
            assertEquals(0, dao.pendingCount())
            assertTrue(dao.getUnsynced().isEmpty())
            assertEquals(1, transport.sent.size) // sin reintentos
        }
    }

    @Test
    fun `reencolar conserva createdAt y attempts`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(RsvpOutboxEntity("ruta-3", "u-3", "confirmed", createdAt = 100L))
        dao.markAttempt("ruta-3", "u-3", "sin red")

        dao.enqueue(RsvpOutboxEntity("ruta-3", "u-3", "no_go", createdAt = 999L))

        val item = dao.getUnsynced().single()
        assertEquals("no_go", item.targetStatus)
        assertEquals(100L, item.createdAt)
        assertEquals(1, item.attempts)
    }

    @Test
    fun `produccion usa upsert no REPLACE y arrival no envia arrive como estado`() {
        val dao = source("app/src/main/java/com/rutabikersync/app/core/clubroutes/RsvpOutboxDao.kt")
        assertTrue(dao.contains("OnConflictStrategy.IGNORE"))
        assertTrue(dao.contains("UPDATE rsvp_outbox SET targetStatus"))
        assertFalse(dao.contains("OnConflictStrategy.REPLACE"))
        assertTrue(dao.contains("@Transaction"))

        val engine = source(
            "app/src/main/java/com/rutabikersync/app/core/clubroutes/RsvpOutboxSyncEngine.kt"
        )
        assertTrue(engine.contains("sendRsvp(routeId, userId, \"confirmed\")"))
    }
}
