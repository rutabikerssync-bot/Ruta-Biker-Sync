package com.rutabikersync.app.feature.groups

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrato UX: RUTAS DEL CLUB es exclusivamente informativo.
 *
 * La respuesta RSVP vive en PARTICIPANTES y el registro de estadísticas en
 * BITÁCORA. Este test fija esa separación sobre el código fuente porque el
 * módulo no depende de Compose UI test (mismo criterio que Fase 5).
 */
class ClubRoutesInformationalTest {

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("No se encontró el fuente $relative")
    }

    private val routesScreen = source(
        "app/src/main/java/com/rutabikersync/app/feature/groups/ClubRoutesScreen.kt"
    )
    private val detailSheet = source(
        "app/src/main/java/com/rutabikersync/app/feature/groups/RouteDetailComponents.kt"
    )
    private val groupTab = source(
        "app/src/main/java/com/rutabikersync/app/feature/groups/Phase1Screens.kt"
    )
    private val scaffold = source(
        "app/src/main/java/com/rutabikersync/app/ui/navigation/MainAppScaffold.kt"
    )
    private val participants = source(
        "app/src/main/java/com/rutabikersync/app/feature/rides/RideParticipantsScreen.kt"
    )

    @Test
    fun `rutas del club no expone acciones de respuesta RSVP`() {
        val forbidden = listOf(
            "requestRsvp",
            "onRsvp",
            "onActionClick",
            "statusPayload",
            "getAvailableRsvpActions",
            "RsvpAction",
            "rsvpStatuses",
            "\"Asistiré\"",
            "\"No iré\"",
            "\"En casa\""
        )
        forbidden.forEach { token ->
            assertFalse("RUTAS DEL CLUB no debe contener $token", routesScreen.contains(token))
            assertFalse("El detalle de ruta no debe contener $token", detailSheet.contains(token))
        }

        // La información de estado y el banner de pendientes sí se conservan.
        assertTrue(routesScreen.contains("ClubRsvpUi.bannerMessage"))
        assertTrue(routesScreen.contains("ClubRsvpUi.routeSubtitle"))
        assertTrue(detailSheet.contains("Tu respuesta: \$statusText"))
    }

    @Test
    fun `el detalle de ruta solo ofrece ver participantes`() {
        assertTrue(detailSheet.contains("Text(\"Ver participantes\""))
        assertTrue(detailSheet.contains("onOpenParticipants(route.id)"))
        assertTrue(detailSheet.contains("onDismiss()"))
        assertTrue(routesScreen.contains("onOpenParticipants = onOpenParticipants"))
        assertTrue(groupTab.contains("onOpenParticipants = onOpenParticipants"))
        assertFalse(groupTab.contains("viewModel.requestRsvp"))
    }

    @Test
    fun `el CTA fija la ruta elegida y navega a participantes`() {
        assertTrue(scaffold.contains("requestedRosterRouteId = routeId"))
        assertTrue(scaffold.contains("phase1ViewModel.loadRideRoster(routeId)"))
        assertTrue(scaffold.contains("selectedTab = 0"))
        assertTrue(scaffold.contains("requestedRosterRouteId ?: currentRide?.clubRouteId"))
        assertTrue(scaffold.contains("requestedRosterRouteId: String? = null"))
    }

    @Test
    fun `las cards viejas solo informan participantes y no navegan`() {
        assertFalse(
            routesScreen.contains("clickable { selectedRouteId = route.id }")
        )
        assertTrue(
            routesScreen.contains("selectedRoute = nextRoute?.takeIf { it.id == selectedRouteId }")
        )
        assertTrue(routesScreen.contains("\"Participantes: \$countText\""))
        assertTrue(routesScreen.contains("participantCounts[route.id]?.toString()"))
        assertTrue(groupTab.contains("loadRsvps(route.id).summary?.total"))
        assertTrue(groupTab.contains("participantCounts = participantCounts"))
    }

    @Test
    fun `la respuesta se registra en participantes y el registro en bitacora`() {
        assertTrue(participants.contains("\"Asistiré\""))
        assertTrue(participants.contains("\"No asistiré\""))
        assertTrue(participants.contains("onRsvp(\"no_go\")"))
        assertTrue(scaffold.contains("Bitácora de Rodadas"))
        assertTrue(scaffold.contains("showRideHistoryDialog = true"))
    }
}
