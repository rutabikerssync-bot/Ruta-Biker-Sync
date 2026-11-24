package com.rutabikersync.app.feature.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrato de la franja de Ficha Médica: foto de rescate a sangre con overlay
 * de contraste, título blanco y pastilla de estado legible sobre la imagen.
 */
class VaultMedicalHeaderContractTest {

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

    private fun source(relative: String): String = find(relative).readText(Charsets.UTF_8)

    @Test
    fun `ficha medica usa franja con foto nitida y degradado como la boveda`() {
        val vault = source(
            "app/src/main/java/com/rutabikersync/app/feature/vault/VaultScreen.kt"
        )
        val start = vault.indexOf("// Sección: Ficha Médica de Emergencia")
        val end = vault.indexOf("Spacer(modifier = Modifier.height(10.dp))", start)
        assertTrue(start >= 0 && end > start)
        val header = vault.substring(start, end)

        assertTrue(header.contains("R.drawable.card_ficha_medica_emergencia"))
        assertTrue(header.contains("Modifier.matchParentSize()"))
        assertTrue(header.contains("contentScale = ContentScale.Crop"))
        assertTrue(header.contains(".height(85.dp)"))
        assertTrue(header.contains("clip(RoundedCornerShape(12.dp))"))
        assertTrue(header.contains("Brush.verticalGradient"))
        assertTrue(header.contains("Color.Transparent,"))
        assertTrue(header.contains("Color(0xFF0F172A).copy(alpha = 0.55f),"))
        assertTrue(header.contains(".padding(horizontal = 12.dp, vertical = 8.dp)"))

        assertTrue(header.contains("text = \"Ficha Médica de Emergencia\""))
        assertTrue(header.contains("color = Color.White"))
        assertTrue(header.contains("Solo visible para líderes en caso de SOS (R10.2)"))
        assertTrue(header.contains("🟢 Ficha Completa"))
        assertTrue(header.contains("🟡 Ficha Incompleta"))

        // La misma imagen y el degradado cubren también grupo sanguíneo y donante.
        assertTrue(header.contains("GRUPO SANGUÍNEO"))
        assertTrue(header.contains("DONANTE ÓRGANOS"))
        assertTrue(header.contains("medicalSheet.bloodType"))
        assertTrue(header.contains(".height(IntrinsicSize.Max)"))
        assertTrue(header.split(".border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))").size - 1 >= 2)

        assertFalse(header.contains("Color.Black.copy(alpha"))
        assertFalse(header.contains("Color(0xFF0F172A).copy(alpha = 0.5f)"))
        assertFalse(header.contains("Color(0xFFD4AF37).copy(alpha = 0.55f)"))
        assertFalse(header.contains(".background(HeritageCardWarm)"))
        assertFalse(header.contains("color = HeritageLeatherBrown"))
        assertFalse(header.contains("color = HeritageLeatherMuted"))
    }

    @Test
    fun `drawable de ficha medica existe y esta optimizado`() {
        val drawable = find("app/src/main/res/drawable/card_ficha_medica_emergencia.webp")
        assertTrue(drawable.isFile)
        val kb = drawable.length() / 1024
        assertTrue("Imagen demasiado pesada: $kb KB", kb in 10..400)
    }
}
