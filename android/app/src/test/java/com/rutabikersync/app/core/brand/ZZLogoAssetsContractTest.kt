package com.rutabikersync.app.core.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrato de branding: todos los logos predefinidos (pantalla, login, crest
 * legacy, launcher y landing) derivan de la fuente maestra logo_RBS-1.2.
 */
class ZZLogoAssetsContractTest {

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

    @Test
    fun `el generador usa exclusivamente la fuente maestra nueva`() {
        val script = find("../scripts/adapt_logo.py").readText(Charsets.UTF_8)
        assertTrue(script.contains("logo_RBS_master.png"))
        assertFalse(script.contains("ruta_biker_raw_logo.png"))
        assertFalse(script.contains("logo_patch_clean_hq.png"))
        assertFalse(script.contains("logo_patch_transparent_full.png"))

        val master = find("../assets_branding/logo_RBS_master.png")
        assertTrue(master.isFile)
        assertTrue("Maestra demasiado pequeña", master.length() > 100_000)
    }

    @Test
    fun `todos los assets de logo predefinidos existen y no estan vacios`() {
        val assets = listOf(
            "app/src/main/res/drawable/ic_logo_ruta_biker_sync.webp",
            "app/src/main/res/drawable/ic_logo_rbs_transparent.png",
            "app/src/main/res/drawable/ic_gold_winged_crest.png",
            "app/src/main/res/drawable/ic_biker_crest_gold.png",
            "app/src/main/res/mipmap-mdpi/ic_launcher.png",
            "app/src/main/res/mipmap-mdpi/ic_launcher_round.png",
            "app/src/main/res/mipmap-hdpi/ic_launcher.png",
            "app/src/main/res/mipmap-hdpi/ic_launcher_round.png",
            "app/src/main/res/mipmap-xhdpi/ic_launcher.png",
            "app/src/main/res/mipmap-xhdpi/ic_launcher_round.png",
            "app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
            "app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png",
            "../backend/public/images/logo-patch.webp",
            "../backend/public/images/logo-patch.png",
            "../backend/public/favicon.png",
            "../backend/public/images/favicon-64.png",
            "../backend/public/images/icon-192.png"
        )
        assets.forEach { relative ->
            val file = find(relative)
            assertTrue("$relative no es archivo", file.isFile)
            assertTrue("$relative está vacío", file.length() > 200)
        }
    }

    @Test
    fun `soporte muestra el escudo oficial a tamano hero`() {
        val support = find(
            "app/src/main/java/com/rutabikersync/app/feature/profile/SupportScreen.kt"
        ).readText(Charsets.UTF_8)
        assertTrue(support.contains("R.drawable.ic_logo_rbs_transparent"))
        assertTrue(support.contains(".align(Alignment.CenterHorizontally)"))
        assertTrue(support.contains(".size(220.dp)"))
        assertTrue(support.contains("Alignment.Center"))
        assertTrue(support.contains("contentScale = ContentScale.Fit"))
        assertTrue(support.contains("contentDescription = \"Logo Ruta Biker Sync\""))
    }

    /** Lee ancho/alto del chunk IHDR sin depender de java.desktop (no disponible en tests JVM). */
    private fun pngSize(file: File): Pair<Int, Int> {
        val bytes = file.readBytes()
        require(bytes.size >= 24 && bytes[0] == 0x89.toByte() && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG") {
            "$file no es un PNG válido"
        }
        fun intAt(offset: Int) = ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
        return intAt(16) to intAt(20)
    }

    @Test
    fun `los formatos horizontales y launcher conservan sus dimensiones`() {
        val expected = mapOf(
            "app/src/main/res/drawable/ic_gold_winged_crest.png" to (330 to 200),
            "app/src/main/res/drawable/ic_biker_crest_gold.png" to (640 to 360),
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" to (192 to 192),
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" to (192 to 192),
            "../backend/public/favicon.png" to (32 to 32),
            "../backend/public/images/icon-192.png" to (192 to 192)
        )
        expected.forEach { (relative, size) ->
            val (width, height) = pngSize(find(relative))
            assertEquals(relative, size.first, width)
            assertEquals(relative, size.second, height)
        }
    }
}
