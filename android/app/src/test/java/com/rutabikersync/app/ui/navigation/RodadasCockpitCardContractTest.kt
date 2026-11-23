package com.rutabikersync.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrato UX de Rodadas tras la limpieza de botones redundantes:
 * - no existe "Cabina Activa" ni su imagen con acción;
 * - la imagen card3.2 es cabecera decorativa de PARTICIPANTES;
 * - "Ruta Libre" (Modo Libre) está siempre disponible, con o sin club;
 * - el ciclo de rodada de club sigue viviendo en el roster (líder).
 */
class RodadasCockpitCardContractTest {

    private fun maybeFind(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun find(relative: String): File =
        maybeFind(relative) ?: error("No se encontró $relative")

    private fun source(relative: String): String = find(relative).readText()

    private val scaffold = source(
        "app/src/main/java/com/rutabikersync/app/ui/navigation/MainAppScaffold.kt"
    )
    private val freeRide = source(
        "app/src/main/java/com/rutabikersync/app/ui/navigation/RodadasSections.kt"
    )
    private val participants = source(
        "app/src/main/java/com/rutabikersync/app/feature/rides/RideParticipantsScreen.kt"
    )

    @Test
    fun `cabina activa desaparece del codigo y de los tres idiomas`() {
        assertFalse(scaffold.contains("CockpitStartCard"))
        assertFalse(scaffold.contains("Cabina + Malla"))
        listOf("values", "values-en", "values-pt").forEach { locale ->
            assertFalse(
                "Sigue el string de Cabina Activa en $locale",
                source("app/src/main/res/$locale/strings.xml")
                    .contains("rides_btn_start_active_cockpit")
            )
        }
    }

    @Test
    fun `la imagen de participantes es decorativa y vive dentro del roster`() {
        assertTrue(freeRide.contains("R.drawable.card_participantes"))
        val bannerStart = freeRide.indexOf("fun ParticipantsHeaderBanner()")
        val bannerEnd = freeRide.indexOf("/** Etiqueta negra")
        assertTrue(bannerStart >= 0 && bannerEnd > bannerStart)
        assertFalse(freeRide.substring(bannerStart, bannerEnd).contains("onClick"))

        val rosterStart = scaffold.indexOf("rideRoster?.takeIf { primaryGroup != null }?.let { roster ->")
        assertTrue(rosterStart >= 0)
        val rosterBlock = scaffold.substring(rosterStart)
        assertTrue(rosterBlock.contains("ParticipantsHeaderBanner()"))
        assertTrue(rosterBlock.contains("RideParticipantsScreen("))
        assertFalse(scaffold.contains("if (!canPublishClubRoute)"))

        val drawable = "app/src/main/res/drawable"
        assertFalse(maybeFind("$drawable/card_iniciar_cabina.webp") != null)
        assertTrue(find("$drawable/card_participantes.webp").isFile)
    }

    @Test
    fun `ruta libre sin registro de club`() {
        assertTrue(freeRide.contains("fun RutaLibreCard("))
        assertTrue(freeRide.contains("text = \"Ruta Libre\""))
        assertTrue(freeRide.contains("Modo Libre (sin registro de club, sin gamificación)"))
        assertTrue(freeRide.contains("R.drawable.card_rodada_solitaria"))

        assertFalse(scaffold.contains("Rodada Individual / Solitaria"))
        assertFalse(scaffold.contains("Para usuarios simples y premium"))
        assertTrue(scaffold.split("RutaLibreCard(").size - 1 == 1) // una sola invocación en el scaffold
    }

    @Test
    fun `ruta libre es incondicional y esta-separada de la rodada de club`() {
        val clubHeader = scaffold.indexOf("text = \"Rodada de Club\"")
        val roster = scaffold.indexOf("rideRoster?.takeIf { primaryGroup != null }?.let { roster ->")
        val routeCard = scaffold.indexOf("// Tarjeta: Ruta y Corredor Vial")
        val free = scaffold.indexOf("RutaLibreCard(")

        assertTrue(clubHeader >= 0)
        assertTrue(clubHeader < roster)
        assertTrue(roster < routeCard)
        assertTrue(routeCard < free)

        // La invocación está fuera del bloque `if (currentRide != null)` y no tiene else.
        val afterFree = scaffold.substring(free)
        assertFalse(afterFree.contains("} else {"))
        assertFalse(scaffold.contains("rides_btn_start_ride_preview"))
    }

    @Test
    fun `el boton dice iniciar ruta libre en los tres idiomas`() {
        assertTrue(freeRide.contains("R.string.rides_btn_start_free_ride"))
        assertTrue(source("app/src/main/res/values/strings.xml")
            .contains("<string name=\"rides_btn_start_free_ride\">Iniciar Ruta Libre</string>"))
        assertTrue(source("app/src/main/res/values-en/strings.xml")
            .contains("<string name=\"rides_btn_start_free_ride\">Start Free Ride</string>"))
        assertTrue(source("app/src/main/res/values-pt/strings.xml")
            .contains("<string name=\"rides_btn_start_free_ride\">Iniciar Rota Livre</string>"))
        listOf("values", "values-en", "values-pt").forEach { locale ->
            assertFalse(
                "Queda el botón genérico 'Iniciar' en $locale",
                source("app/src/main/res/$locale/strings.xml")
                    .contains("rides_btn_start_ride_preview")
            )
        }
    }

    @Test
    fun `sin club solo ruta libre y se conservan bitacora y corredor offline`() {
        val gate = scaffold.indexOf(
            "if (primaryGroup != null) {",
            scaffold.indexOf("// Normalización: sin club no hay convocatoria")
        )
        val endGate = scaffold.indexOf("// Sección 1: Rodada de Club")
        assertTrue(gate >= 0 && endGate > gate)

        val clubBlock = scaffold.substring(gate, endGate)
        assertTrue(clubBlock.contains("Tarjeta: Próxima Rodada"))
        assertTrue(clubBlock.contains("+ Crear Ruta Oficial de Club"))

        // Lo que pertenece al motociclista o al modo offline queda fuera del gate.
        assertTrue(scaffold.indexOf("Bitácora de Rodadas") < gate)
        assertTrue(scaffold.indexOf("Rutas y Corredor sin señal") > endGate)
        assertTrue(scaffold.indexOf("RutaLibreCard(") > endGate)
        assertTrue(scaffold.contains("rideRoster?.takeIf { primaryGroup != null }"))
    }

    @Test
    fun `regresion el ciclo de club sigue en el roster y llegan lo confirma el lider`() {
        listOf("requestRsvp(", "requestOnSite(", "startRide(", "arriveDestination(", "startReturn(")
            .forEach { assertTrue("Falta $it", scaffold.contains(it)) }
        assertTrue(participants.contains("if (currentUserIsLeader) {"))
        assertTrue(participants.contains("El líder confirma la llegada"))
    }
}
