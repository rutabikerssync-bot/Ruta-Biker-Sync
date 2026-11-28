package com.rutabikersync.app.feature.profile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Google Play (TAREA 4): la política de privacidad debe abrirse desde el perfil.
 * Contrato estático JVM: comprueba el cableado real sin Robolectric ni emulador.
 */
class ProfilePrivacyAccessTest {

    private fun maybeFind(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun source(relative: String): String =
        (maybeFind(relative) ?: error("No se encontró $relative")).readText(Charsets.UTF_8)

    @Test
    fun `el perfil abre el dialogo de privacidad desde ajustes`() {
        val profile = source(
            "app/src/main/java/com/rutabikersync/app/feature/profile/ProfileScreen.kt"
        )
        assertTrue(profile.contains("import com.rutabikersync.app.feature.legal.LegalTermsDialog"))
        assertTrue(profile.contains("var showLegalTerms by remember { mutableStateOf(false) }"))
        assertTrue(profile.contains("\"Privacidad y términos\""))
        assertTrue(profile.contains("showLegalTerms = true"))
        assertTrue(profile.contains("LegalTermsDialog("))
        assertTrue(profile.contains("initialTab = 2"))
    }

    @Test
    fun `el dialogo incluye la pestana de privacidad y la web publica la politica`() {
        val dialog = source(
            "app/src/main/java/com/rutabikersync/app/feature/legal/LegalTermsDialog.kt"
        )
        assertTrue(dialog.contains("text = \"Privacidad\""))
        assertTrue(dialog.contains("Política de Privacidad y Protección de Datos"))
        // Coherencia con el manifest y con backend/public/privacy.html: no se pide ubicación en segundo plano.
        org.junit.Assert.assertFalse(dialog.contains("Se solicita permiso de ubicación en segundo plano"))
        assertTrue(dialog.contains("ACCESS_BACKGROUND_LOCATION no está declarado"))

        val privacy = source("../backend/public/privacy.html")
        assertTrue(privacy.contains("Política de Privacidad"))
        assertTrue(privacy.contains("soporte@rutabikersync.com"))
        assertTrue(privacy.contains("no declara <code>ACCESS_BACKGROUND_LOCATION</code>"))
    }
}
