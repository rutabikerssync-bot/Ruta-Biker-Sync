package com.rutabikersync.app.core.pilot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import com.rutabikersync.app.BuildConfig
import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Reportero de telemetría exclusivo de builds debug/piloto.
 *
 * Envía latidos periódicos, excepciones no capturadas y las últimas líneas del
 * logcat de ESTE proceso. Nunca envía coordenadas, tokens, claves ni datos
 * médicos; la redacción se aplica antes de salir del dispositivo y el backend
 * vuelve a redactar. En release este objeto es inerte.
 */
object PilotTelemetry {
    private const val PREFS = "rbs_pilot_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_PILOT_KEY = "pilot_key"
    private const val KEY_PILOT_BUILD = "pilot_build"
    private const val KEY_PENDING_ERROR = "pending_error"
    private const val HEARTBEAT_MS = 60_000L
    private const val MAX_LOG_CHARS = 16_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api: ClubApiClient get() = ClubApiClient.getInstance()

    @Volatile
    private var installed = false
    private var appContext: Context? = null

    private val base64Blob = Regex("data:[A-Za-z0-9.+/-]+;base64,[A-Za-z0-9+/=]{16,}")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val bearer = Regex("(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}", RegexOption.IGNORE_CASE)
    private val assignment = Regex(
        "\\b(token|secret|password|api[_-]?key|authorization|x-admin-key|refresh[_-]?token)\\s*[=:]\\s*[^\\s,;]+",
        RegexOption.IGNORE_CASE
    )
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val coordinate = Regex("-?\\d{1,3}\\.\\d{5,}")
    private val uuid = Regex(
        "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
        RegexOption.IGNORE_CASE
    )

    private fun redact(value: String): String = value
        .replace(base64Blob, "[base64-redacted]")
        .replace(jwt, "[jwt-redacted]")
        .replace(bearer) { match -> "${match.groupValues[1]}[redacted]" }
        .replace(assignment) { match -> "${match.groupValues[1]}=[redacted]" }
        .replace(email, "[email-redacted]")
        .replace(coordinate, "[coord]")
        .replace(uuid, "[uuid-redacted]")

    /** Aplica la configuración remota recibida por deep link; solo debug. */
    fun applyRemoteConfig(url: String, key: String, build: String): Boolean {
        if (!BuildConfig.DEBUG) return false
        val normalized = url.trim().trimEnd('/')
        if (!normalized.startsWith("https://", ignoreCase = true) &&
            !normalized.startsWith("http://127.0.0.1", ignoreCase = true)
        ) return false
        if (key.length !in 16..256 || build.length !in 1..40) return false

        return runCatching {
            val ctx = appContext
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_BASE_URL, normalized)
                    .putString(KEY_PILOT_KEY, key)
                    .putString(KEY_PILOT_BUILD, build)
                    .apply()
            }
            api.baseUrl = normalized
            api.pilotKey = key
            api.pilotBuild = build
            true
        }.getOrDefault(false)
    }

    fun install(context: Context) {
        if (!BuildConfig.DEBUG || installed) return
        installed = true
        appContext = context.applicationContext

        restoreSavedConfig()
        installCrashHandler()
        scope.launch {
            flushPendingError()
            while (isActive) {
                sendHeartbeat()
                delay(HEARTBEAT_MS)
            }
        }
    }

    private fun restoreSavedConfig() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_BASE_URL, "").orEmpty()
        val key = prefs.getString(KEY_PILOT_KEY, "").orEmpty()
        val build = prefs.getString(KEY_PILOT_BUILD, "").orEmpty()
        if (url.isNotBlank() && key.isNotBlank()) {
            runCatching {
                api.baseUrl = url
                api.pilotKey = key
                api.pilotBuild = build
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            onUncaught(throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun onUncaught(throwable: Throwable) {
        val stack = StringWriter().use { writer ->
            throwable.printStackTrace(PrintWriter(writer))
            writer.toString()
        }.take(12_000)

        // Persistimos primero: si el proceso muere, se envía en el próximo arranque.
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_PENDING_ERROR, stack)?.apply()

        scope.launch { runCatching { send(stack, "fatal", "error") } }
    }

    private suspend fun flushPendingError() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getString(KEY_PENDING_ERROR, "").orEmpty()
        if (pending.isBlank()) return
        prefs.edit().remove(KEY_PENDING_ERROR).apply()
        runCatching { send(pending, "fatal", "error") }
    }

    private suspend fun sendHeartbeat() {
        if (api.pilotKey.isBlank()) return
        val ctx = appContext ?: return
        val battery = runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else null
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0

        val payload = buildJsonObject {
            put("deviceModel", Build.MODEL)
            put("deviceBrand", Build.BRAND)
            put("androidVersion", Build.VERSION.RELEASE)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("gitCommit", BuildConfig.GIT_COMMIT)
            put("gitDirty", BuildConfig.GIT_DIRTY)
            if (batteryPct != null) put("batteryPct", batteryPct)
            put("isCharging", charging)
            put("kind", "heartbeat")
            put("severity", "info")
            put("logsTail", recentLogs())
        }
        api.reportDeviceTelemetry(payload)
    }

    private suspend fun send(stack: String, severity: String, kind: String) {
        if (api.pilotKey.isBlank()) return
        val payload = buildJsonObject {
            put("deviceModel", Build.MODEL)
            put("deviceBrand", Build.BRAND)
            put("androidVersion", Build.VERSION.RELEASE)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("gitCommit", BuildConfig.GIT_COMMIT)
            put("gitDirty", BuildConfig.GIT_DIRTY)
            put("kind", kind)
            put("severity", severity)
            put("message", stack.take(4_000))
            put("stack", stack.take(12_000))
            put("logsTail", recentLogs())
        }
        api.reportDeviceTelemetry(payload)
    }

    /** Últimas líneas del logcat de este PID, redactadas antes de salir del móvil. */
    private fun recentLogs(): String = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-t", "150", "--pid=${Process.myPid()}")
        )
        val raw = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        process.destroy()
        redact(raw.takeLast(MAX_LOG_CHARS))
    }.getOrDefault("")
}
