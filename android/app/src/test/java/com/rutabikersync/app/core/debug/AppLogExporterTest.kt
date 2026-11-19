package com.rutabikersync.app.core.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Bloque D: la exportación sólo se habilita en debug, pero aun así el contenido debe
 * filtrarse por tags y no contener PII. Estas pruebas no invocan `logcat` (no está
 * disponible en JVM): verifican las transformaciones puras y el ZIP resultante.
 */
class AppLogExporterTest {

    @Test
    fun `filtra por tags de malla y descarta lineas con datos personales`() {
        val raw = listOf(
            "11-18 20:00:00.000  123  123 E MeshRelayEngine: peer lat=10.349246 lon=-67.034904",
            "11-18 20:00:01.000  123  123 E OtroProceso: dato irrelevante",
            "11-18 20:00:02.000  123  123 E RideService: displayName=José Manuel MF",
            "11-18 20:00:03.000  123  123 E BleAdvTransport: token=abc123secreto ok",
            "11-18 20:00:04.000  123  123 E PttAudioEngine: session juan@example.com conectado"
        )

        val result = AppLogExporter.filterAndRedact(raw)

        assertEquals(3, result.lines.size)
        assertEquals(1, result.droppedLineCount)
        assertTrue(result.lines[0].contains("[coord]"))
        assertTrue(result.lines.any { it.contains("token=[redacted]") })
        assertFalse(result.lines.any { it.contains("abc123secreto") })
        assertFalse(result.lines.any { it.contains("juan@example.com") })
        assertFalse(result.lines.any { it.contains("José Manuel") })
    }

    @Test
    fun `redactor elimina jwt uuid bearer y blobs base64`() {
        val line = "GattVoice uid=4fb8dbf0-1111-2222-3333-444455556666 " +
            "Authorization: Bearer eyJhbGciOi.payload.signature " +
            "logo=data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA=="

        val clean = AppLogRedactor.redact(line)

        assertFalse(clean.contains("4fb8dbf0"))
        assertFalse(clean.contains("eyJhbGciOi"))
        assertFalse(clean.contains("/9j/4AAQ"))
        assertTrue(clean.contains("[uuid-redacted]"))
        assertTrue(clean.contains("[base64-redacted]"))
    }

    @Test
    fun `el zip contiene el log redactado y un manifest sin secretos`() {
        val dir: File = Files.createTempDirectory("rbs-log-export").toFile()
        val filtered = AppLogExporter.filterAndRedact(
            listOf("E MeshRelayEngine: lat=10.349246 token=abc123secreto")
        )
        val file = AppLogExporter.writeZip(
            directory = dir,
            lines = filtered.lines,
            metadata = listOf("origen=logcat"),
            timestamp = "20261118_210000"
        )

        assertEquals("rbs_logs_20261118_210000.zip", file.name)
        ZipInputStream(file.inputStream()).use { zip ->
            val entries = mutableMapOf<String, String>()
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.bufferedReader(Charsets.UTF_8).readText()
                zip.closeEntry()
                entry = zip.nextEntry
            }
            assertEquals(setOf("logs/app_logcat.txt", "manifest.txt"), entries.keys)
            assertTrue(entries.getValue("logs/app_logcat.txt").contains("[coord]"))
            assertTrue(entries.getValue("logs/app_logcat.txt").contains("token=[redacted]"))
            assertFalse(entries.getValue("logs/app_logcat.txt").contains("abc123secreto"))
            assertTrue(entries.getValue("manifest.txt").contains("filtros="))
            assertTrue(entries.getValue("manifest.txt").contains("RideService"))
            assertFalse(entries.getValue("manifest.txt").contains("MAPBOX"))
        }
        file.delete()
        dir.delete()
    }
}
