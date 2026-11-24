package com.rutabikersync.app.feature.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrato visual de la Bóveda vacía: cabecera de imagen a sangre con degradado
 * hacia Dark Slate, y contenido agrupado con el padding oficial de la tarjeta.
 */
class VaultEmptyCardContractTest {

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

    private val vault = source(
        "app/src/main/java/com/rutabikersync/app/feature/vault/VaultScreen.kt"
    )

    @Test
    fun `estado vacio con cabecera de imagen y sin padding exterior uniforme`() {
        val start = vault.indexOf("if (documents.isEmpty()) {")
        val end = vault.indexOf("} else {", start)
        assertTrue(start >= 0 && end > start)
        val block = vault.substring(start, end)

        assertFalse(block.contains(".padding(28.dp)"))
        assertFalse(block.contains(".height(135.dp)"))
        assertTrue(block.contains("R.drawable.card_boveda_vacia"))
        assertTrue(block.contains("Modifier.matchParentSize()"))
        assertTrue(block.contains("contentScale = ContentScale.Crop"))
        assertTrue(block.contains("alignment = Alignment.Center"))

        assertTrue(block.contains("Brush.verticalGradient"))
        assertTrue(block.contains("Color.Transparent"))
        assertTrue(block.contains("Color(0xFF0F172A).copy(alpha = 0.55f)"))
        assertTrue(block.contains("Color(0xFF0F172A)"))

        assertTrue(block.contains(".align(Alignment.BottomCenter)"))
        assertTrue(block.contains(".padding(horizontal = 16.dp, vertical = 12.dp)"))
        assertTrue(block.contains("Tu Bóveda está vacía"))
        assertTrue(block.contains("Registrar Primer Documento"))
    }

    @Test
    fun `drawable de la boveda vacia existe y esta optimizado`() {
        val drawable = find("app/src/main/res/drawable/card_boveda_vacia.webp")
        assertTrue(drawable.isFile)
        val kb = drawable.length() / 1024
        assertTrue("Imagen demasiado pesada: $kb KB", kb in 10..400)
    }
}
