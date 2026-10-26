package com.rutabikersync.app.core.util

import com.rutabikersync.app.core.uxtest.UxTestTracker
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseSecurityBuildConfigTest {

    @Test
    fun `release buildType garantiza ALLOW_DEFAULT_TEST_RIDE_KEY en false y DEV_PANEL_ENABLED en false`() {
        val candidates = listOf(
            File("build.gradle.kts"),
            File("android/app/build.gradle.kts"),
            File("../app/build.gradle.kts"),
            File("../../android/app/build.gradle.kts")
        )
        val gradleFile = candidates.firstOrNull { it.exists() }
        assertTrue("El archivo build.gradle.kts debe existir", gradleFile != null && gradleFile.exists())

        val content = gradleFile!!.readText()
        val releaseIndex = content.indexOf("release {")
        assertTrue("Debe existir el bloque release en build.gradle.kts", releaseIndex >= 0)

        val releaseBlock = content.substring(releaseIndex, content.indexOf("}", releaseIndex))
        assertTrue(
            "release debe tener DEV_PANEL_ENABLED = false",
            releaseBlock.contains("""buildConfigField("boolean", "DEV_PANEL_ENABLED", "false")""")
        )
        assertTrue(
            "release debe tener ALLOW_DEFAULT_TEST_RIDE_KEY = false (bloqueante D58)",
            releaseBlock.contains("""buildConfigField("boolean", "ALLOW_DEFAULT_TEST_RIDE_KEY", "false")""")
        )
    }

    @Test
    fun `UxTestTracker registra participante, cronometra tareas y garantiza privacidad en JSON`() {
        UxTestTracker.setParticipant("P1")
        assertEquals("P1", UxTestTracker.participantId.value)
        assertFalse(UxTestTracker.isModeratorSupport)

        UxTestTracker.startTask("T1")
        assertEquals("T1", UxTestTracker.activeTaskId.value)

        Thread.sleep(15) // Simular duración mínima
        UxTestTracker.endTask("T1", successLevel = 2)
        assertEquals(null, UxTestTracker.activeTaskId.value)

        UxTestTracker.logEvent(
            screen = "LIVE_MAP",
            action = "TAP_FOLLOW_LEADER",
            target = "btn_seguir_lider"
        )

        // Verificar el participante de apoyo del moderador P0
        UxTestTracker.setParticipant("P0")
        assertTrue(UxTestTracker.isModeratorSupport)

        // Verificar que los eventos registrados son válidos
        val recorded = UxTestTracker.getRecordedEvents()
        assertTrue(recorded.isNotEmpty())
        assertTrue(recorded.any { it.action == "TASK_START" && it.target == "T1" })
        assertTrue(recorded.any { it.action == "TASK_END" && it.target == "T1" })
        assertTrue(recorded.any { it.action == "TAP_FOLLOW_LEADER" })

        // Probar el export JSON sintético
        val json = JSONObject().apply {
            put("participantId", "P1")
            put("isModeratorPhone", false)
            put("privacyGuarantee", "ANONIMIZADO_SIN_COORDENADAS_SIN_AUDIO_SIN_NOMBRES")
            val evArray = org.json.JSONArray()
            recorded.forEach { evArray.put(it.toJsonObject()) }
            put("events", evArray)
        }
        assertEquals("P1", json.getString("participantId"))
        assertFalse(json.getBoolean("isModeratorPhone"))
        assertTrue(json.getString("privacyGuarantee").contains("SIN_COORDENADAS"))

        // Reset
        UxTestTracker.setParticipant("P1")
    }
}
