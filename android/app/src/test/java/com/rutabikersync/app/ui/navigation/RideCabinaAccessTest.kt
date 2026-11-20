package com.rutabikersync.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas puras de la condición exacta usada por MainAppScaffold. */
class RideCabinaAccessTest {
    private data class Access(
        val showClassicCockpit: Boolean,
        val useRosterStart: Boolean,
        val classicCallsBackendStart: Boolean
    )

    private fun canPublishClubRoute(
        currentUserId: String,
        principalLeaderId: String,
        leaderIds: List<String>
    ): Boolean = currentUserId == principalLeaderId || leaderIds.contains(currentUserId)

    private fun access(
        hasRoster: Boolean,
        canPublishClubRoute: Boolean
    ) = Access(
        showClassicCockpit = !hasRoster || !canPublishClubRoute,
        useRosterStart = hasRoster && canPublishClubRoute,
        classicCallsBackendStart = false // El clásico invoca cabina/malla, nunca /start.
    )

    @Test
    fun `sin roster el boton clasico de cabina esta disponible`() {
        val value = access(hasRoster = false, canPublishClubRoute = false)
        assertTrue(value.showClassicCockpit)
        assertFalse(value.useRosterStart)
        assertFalse(value.classicCallsBackendStart)
    }

    @Test
    fun `con roster y lider no hay bypass classico solo inicio desde roster`() {
        val value = access(hasRoster = true, canPublishClubRoute = true)
        assertFalse(value.showClassicCockpit)
        assertTrue(value.useRosterStart)
        assertFalse(value.classicCallsBackendStart)
    }

    @Test
    fun `con roster y miembro se permite activar cabina sin llamar a start`() {
        val value = access(hasRoster = true, canPublishClubRoute = false)
        assertTrue(value.showClassicCockpit)
        assertFalse(value.useRosterStart)
        assertFalse(value.classicCallsBackendStart)
    }

    @Test
    fun `permiso multiclub depende del liderazgo del grupo actual`() {
        val currentUser = "leader-a"
        val groupAIsLeader = canPublishClubRoute(currentUser, currentUser, listOf(currentUser, "co-leader-a"))
        assertTrue(groupAIsLeader)

        // Su liderazgo en club A no lo convierte en líder del club B.
        val groupBIsLeader = canPublishClubRoute(currentUser, "leader-b", listOf("leader-b", "co-leader-b"))
        assertFalse(groupBIsLeader)

        // Como miembro del club B conserva cabina, pero no publicación ni inicio global.
        assertTrue(access(hasRoster = true, canPublishClubRoute = groupBIsLeader).showClassicCockpit)
        assertFalse(access(hasRoster = true, canPublishClubRoute = groupBIsLeader).useRosterStart)
    }
}
