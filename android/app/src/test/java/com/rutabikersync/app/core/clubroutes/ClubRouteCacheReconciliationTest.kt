package com.rutabikersync.app.core.clubroutes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresión: una ruta borrada en el backend no debe seguir viva en la caché
 * Room, porque entonces se elige como "próxima rodada" y su roster no carga.
 */
class ClubRouteCacheReconciliationTest {

    private val groupId = "e3988eba-26e8-425a-80ac-2a65aeda8484"

    private fun route(id: String, title: String) = ClubRouteEntity(
        id = id,
        groupId = groupId,
        createdBy = "leader",
        title = title,
        startsAt = "2026-11-24T16:27:00.000Z"
    )

    @Test
    fun `ruta eliminada en el backend desaparece de la cache al reconciliar`() = runTest {
        val dao = InMemoryClubRouteDao()
        val stale = route("ruta-borrada", "Ruta de prueba UX")
        val live = route("ruta-real", "Rodada Oficial")
        dao.upsertRoutes(listOf(stale, live))

        dao.deleteStaleRoutes(groupId, listOf(live.id))
        dao.upsertRoutes(listOf(live))

        assertEquals(listOf("ruta-real"), dao.getRoutes(groupId).map { it.id })
    }

    @Test
    fun `respuesta vacia del backend vacia la cache del club`() = runTest {
        val dao = InMemoryClubRouteDao()
        dao.upsertRoutes(listOf(route("ruta-borrada", "Ruta de prueba UX")))

        dao.deleteAllRoutesForGroup(groupId)

        assertEquals(emptyList<String>(), dao.getRoutes(groupId).map { it.id })
    }
}
