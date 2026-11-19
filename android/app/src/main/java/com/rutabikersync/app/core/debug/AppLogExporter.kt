package com.rutabikersync.app.core.debug

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exportación de logs de la app sin adb (Bloque D).
 *
 * Sólo se invoca desde el panel DEV, protegido por `BuildConfig.DEV_PANEL_ENABLED`.
 * Desde API 16 una app normal no puede leer el logcat global: `logcat -d` devuelve
 * únicamente los mensajes de su propio proceso. Eso es suficiente para diagnosticar
 * la malla BLE, GATT, PTT y RideService, y se documenta como limitación.
 */
object AppLogExporter {

    const val MAX_LOG_LINES = 2_000
    private const val MAX_LINE_CHARS = 2_000
    private val RELEVANT_TAGS = listOf(
        "com.rutabikersync.app",
        "RideService",
        "BleAdvTransport",
        "MeshRelayEngine",
        "GattVoice",
        "PttAudioEngine"
    )
    private val TAG_LINE = Regex(RELEVANT_TAGS.joinToString("|") { Regex.escape(it) })
    private val TIMESTAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    data class ExportResult(
        val file: File,
        val lineCount: Int,
        val droppedLineCount: Int
    )

    data class FilterResult(
        val lines: List<String>,
        val droppedLineCount: Int
    )

    /** Filtra por los tags solicitados y redacta PII. Puro y verificable en JVM. */
    fun filterAndRedact(rawLines: List<String>, maxLines: Int = MAX_LOG_LINES): FilterResult {
        val kept = ArrayList<String>(minOf(maxLines, rawLines.size))
        var dropped = 0
        for (raw in rawLines) {
            if (!TAG_LINE.containsMatchIn(raw)) continue
            if (AppLogRedactor.shouldDropLine(raw)) {
                dropped++
                continue
            }
            val clean = AppLogRedactor.redact(raw).take(MAX_LINE_CHARS)
            if (clean.isBlank()) {
                dropped++
                continue
            }
            kept.add(clean)
            if (kept.size >= maxLines) break
        }
        return FilterResult(kept, dropped)
    }

    private fun readLogcatLines(fetchLines: Int): List<String> {
        val process = ProcessBuilder(
            "logcat", "-d", "-t", fetchLines.toString(), "-v", "threadtime"
        ).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        return lines
    }

    /** Escribe el ZIP bajo `cacheDir/exports/` (ruta ya permitida por `file_paths.xml`). */
    fun writeZip(
        directory: File,
        lines: List<String>,
        metadata: List<String> = emptyList(),
        timestamp: String = TIMESTAMP.format(Date())
    ): File {
        directory.mkdirs()
        val file = File(directory, "rbs_logs_$timestamp.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("logs/app_logcat.txt"))
            lines.forEach { zip.write("${it}\n".toByteArray(Charsets.UTF_8)) }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("manifest.txt"))
            val header = listOf(
                "Ruta Biker Sync - exportacion de logs de diagnostico",
                "generado=${timestamp}",
                "lineas=${lines.size}",
                "filtros=${RELEVANT_TAGS.joinToString(",")}",
                "nota=logcat del propio proceso; PII redactada"
            )
            (header + metadata).forEach { zip.write("${it}\n".toByteArray(Charsets.UTF_8)) }
            zip.closeEntry()
        }
        return file
    }

    suspend fun export(
        context: Context,
        maxLines: Int = MAX_LOG_LINES
    ): ExportResult = withContext(Dispatchers.IO) {
        // Pedimos más líneas de las que queremos porque luego se filtran por tag.
        val fetched = readLogcatLines((maxLines * 4).coerceAtMost(12_000))
        val filtered = filterAndRedact(fetched, maxLines)
        val file = writeZip(
            directory = File(context.cacheDir, "exports"),
            lines = filtered.lines,
            metadata = listOf("origen=logcat -d -v threadtime")
        )
        ExportResult(file, filtered.lines.size, filtered.droppedLineCount)
    }

    /** Comparte el ZIP mediante el FileProvider RGPD ya declarado en el manifest. */
    fun share(context: Context, file: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, "Ruta Biker Sync · logs de diagnóstico")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "RBS logs", uri)
        }
        Intent.createChooser(sendIntent, "Compartir logs").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.also { context.startActivity(it) }
    }
}
