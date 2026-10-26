package com.rutabikersync.app.core.uxtest

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.BuildConfig
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.theme.BikerColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Registro UX local (sin red) para pruebas de usabilidad con 6 testers (UX_TEST_PLAN.md).
 *
 * Características:
 * - Código de participante (P1–P6) o P0 (teléfono de apoyo del moderador).
 * - Trazabilidad de tareas T1–T10 con tiempos de inicio/fin y nivel de éxito (0=falló, 1=con ayuda, 2=solo).
 * - Privacidad estricta: NO almacena coordenadas GPS, nombres reales, contactos ni audio.
 * - Exportable a JSON local en `/sdcard/Android/data/.../files/`.
 */
object UxTestTracker {

    data class UxEvent(
        val timestampIso: String,
        val timestampEpochMs: Long,
        val participantId: String,
        val screen: String,
        val action: String,
        val target: String,
        val details: String? = null
    ) {
        fun toJsonObject(): JSONObject = JSONObject().apply {
            put("timestampIso", timestampIso)
            put("timestampEpochMs", timestampEpochMs)
            put("participantId", participantId)
            put("screen", screen)
            put("action", action)
            put("target", target)
            put("details", details ?: JSONObject.NULL)
        }
    }

    private const val PREFS_NAME = "rbs_ux_test_prefs"
    private const val KEY_PARTICIPANT_ID = "ux_participant_id"

    private val _participantId = MutableStateFlow("P1")
    val participantId: StateFlow<String> = _participantId.asStateFlow()

    private val _activeTaskId = MutableStateFlow<String?>(null)
    val activeTaskId: StateFlow<String?> = _activeTaskId.asStateFlow()

    private val taskStartTimes = mutableMapOf<String, Long>()
    private val taskDurationsSeconds = mutableMapOf<String, Double>()
    private val taskResults = mutableMapOf<String, Int>()

    private val events = mutableListOf<UxEvent>()
    private val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    const val MODERATOR_PIN = "1984"

    /**
     * Mapeo estricto de IDs de nodo de malla por participante:
     * P1 -> 1u
     * P2 -> 2u
     * P3 -> 3u
     * P4 -> 4u
     * P5 -> 5u
     * P6 -> 6u
     * P0 -> 99u (apoyo del moderador, no choca con los testers)
     */
    fun participantToShortId(participant: String): UShort = when (participant) {
        "P1" -> 1u
        "P2" -> 2u
        "P3" -> 3u
        "P4" -> 4u
        "P5" -> 5u
        "P6" -> 6u
        "P0" -> 99u
        else -> 100u
    }

    private val _leaderShortId = MutableStateFlow<UShort>(1u)
    val leaderShortId: StateFlow<UShort> = _leaderShortId.asStateFlow()

    val currentLocalShortId: UShort
        get() = participantToShortId(_participantId.value)

    val isConvoyLeader: Boolean
        get() = currentLocalShortId == _leaderShortId.value

    val isModeratorSupport: Boolean
        get() = _participantId.value == "P0"

    fun verifyPin(pin: String): Boolean = pin.trim() == MODERATOR_PIN

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val p = prefs.getString(KEY_PARTICIPANT_ID, "P1") ?: "P1"
        _participantId.value = p
    }

    fun setParticipant(id: String, context: Context? = null) {
        _participantId.value = id
        context?.let {
            it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PARTICIPANT_ID, id)
                .apply()
        }
        logEvent(
            screen = "SETTINGS_UX",
            action = "SET_PARTICIPANT",
            target = id,
            details = "shortId=${participantToShortId(id)},isModerator=${id == "P0"}"
        )
    }

    fun setLeaderShortId(leaderId: UShort) {
        _leaderShortId.value = leaderId
        logEvent(
            screen = "MODERATOR_PANEL",
            action = "SET_CONVOY_LEADER",
            target = "shortId_$leaderId",
            details = "leaderShortId=$leaderId"
        )
    }

    @Synchronized
    fun logEvent(
        screen: String,
        action: String,
        target: String = "",
        details: String? = null
    ) {
        val nowMs = System.currentTimeMillis()
        val event = UxEvent(
            timestampIso = sdf.format(Date(nowMs)),
            timestampEpochMs = nowMs,
            participantId = _participantId.value,
            screen = screen,
            action = action,
            target = target,
            details = details
        )
        if (events.size >= 5000) {
            events.removeAt(0)
        }
        events.add(event)
    }

    @Synchronized
    fun startTask(taskId: String) {
        val nowMs = System.currentTimeMillis()
        taskStartTimes[taskId] = nowMs
        _activeTaskId.value = taskId
        logEvent(
            screen = "TASK_RUNNER",
            action = "TASK_START",
            target = taskId,
            details = "Iniciada por moderador"
        )
    }

    @Synchronized
    fun endTask(taskId: String, successLevel: Int = 2) {
        val nowMs = System.currentTimeMillis()
        val startMs = taskStartTimes[taskId] ?: nowMs
        val durationSec = ((nowMs - startMs) / 1000.0).coerceAtLeast(0.0)
        taskDurationsSeconds[taskId] = durationSec
        taskResults[taskId] = successLevel
        _activeTaskId.value = null
        val levelLabel = when (successLevel) {
            2 -> "SOLO (2)"
            1 -> "CON AYUDA (1)"
            else -> "FALLO (0)"
        }
        logEvent(
            screen = "TASK_RUNNER",
            action = "TASK_END",
            target = taskId,
            details = "duracion_s=$durationSec; exito=$levelLabel"
        )
    }

    @Synchronized
    fun getRecordedEvents(): List<UxEvent> = events.toList()

    @Synchronized
    fun exportReportJson(context: Context): String {
        val nowMs = System.currentTimeMillis()
        val obj = JSONObject().apply {
            put("exportTimestampIso", sdf.format(Date(nowMs)))
            put("exportTimestampEpochMs", nowMs)
            put("participantId", _participantId.value)
            put("isModeratorPhone", isModeratorSupport)
            put("applicationId", context.packageName)
            put("buildType", BuildConfig.BUILD_TYPE)
            put("gitCommit", BuildConfig.GIT_COMMIT)
            put("deviceManufacturer", Build.MANUFACTURER)
            put("deviceModel", Build.MODEL)
            put("androidRelease", Build.VERSION.RELEASE)
            put("androidSdkInt", Build.VERSION.SDK_INT)
            put("privacyGuarantee", "ANONIMIZADO_SIN_COORDENADAS_SIN_AUDIO_SIN_NOMBRES")

            val durationsObj = JSONObject()
            taskDurationsSeconds.forEach { (k, v) -> durationsObj.put(k, v) }
            put("taskDurationsSeconds", durationsObj)

            val resultsObj = JSONObject()
            taskResults.forEach { (k, v) -> resultsObj.put(k, v) }
            put("taskResults", resultsObj)

            val eventsArray = JSONArray()
            events.forEach { e -> eventsArray.put(e.toJsonObject()) }
            put("eventsCount", events.size)
            put("events", eventsArray)
        }
        return obj.toString(2)
    }

    private val _sosMeshBroadcastEnabled = MutableStateFlow(false)
    val sosMeshBroadcastEnabled: StateFlow<Boolean> = _sosMeshBroadcastEnabled.asStateFlow()
    val isSosMeshBroadcastEnabled: Boolean get() = _sosMeshBroadcastEnabled.value

    fun setSosMeshBroadcastEnabled(enabled: Boolean) {
        _sosMeshBroadcastEnabled.value = enabled
        logEvent(
            screen = "SETTINGS_UX",
            action = "SET_SOS_MESH_BROADCAST",
            target = "switch_sos_mesh",
            details = "enabled=$enabled"
        )
    }

    private val _simulatedAlertEvent = MutableStateFlow<String?>(null)
    val simulatedAlertEvent: StateFlow<String?> = _simulatedAlertEvent.asStateFlow()

    fun triggerSimulatedLostPeerAlert() {
        val alert = "¡Compañero perdido! Rider #3 (Simulación de prueba UX)"
        _simulatedAlertEvent.value = alert
        logEvent(
            screen = "LIVE_MAP",
            action = "ALERT_PEER_LOST_SIMULATED",
            target = "alert_peer_lost",
            details = "Alerta visual/sonora local sin emisión a la malla"
        )
    }

    fun clearSimulatedAlert() {
        _simulatedAlertEvent.value = null
    }

    fun shareReportJson(context: Context) {
        val json = exportReportJson(context)
        saveReportToFile(context)
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, "UX_Test_Metrics_${_participantId.value}.json")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Compartir Registro UX (${_participantId.value})")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    fun saveReportToFile(context: Context): File {
        val json = exportReportJson(context)
        val fileTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val fileName = "uxtest_${_participantId.value}_${fileTimestamp}.json"
        val file = File(dir, fileName)
        file.writeText(json)
        return file
    }
}

/**
 * Modal flotante de control para el moderador en builds `uxTest`.
 * Permite cambiar el participante P0..P6, cronometrar tareas T1..T10 y exportar el informe.
 */
@Composable
fun ModeratorControlDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val currentParticipant by UxTestTracker.participantId.collectAsState()
    val activeTask by UxTestTracker.activeTaskId.collectAsState()
    val sosMeshBroadcastEnabled by UxTestTracker.sosMeshBroadcastEnabled.collectAsState()
    val leaderShortId by UxTestTracker.leaderShortId.collectAsState()
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    val taskDescriptions = remember {
        listOf(
            "T1" to "Permisos iniciales",
            "T2" to "Crear perfil y color pin",
            "T3" to "Crear grupo del club",
            "T4" to "Invitar y unirse con código",
            "T5" to "Iniciar rodada",
            "T6" to "Seguir líder en mapa",
            "T7" to "Activar modo guantes",
            "T8" to "Interpretar alerta perdida",
            "T9" to "Activar SOS de prueba",
            "T10" to "Detener rodada (3 s)"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Control de Moderador UX",
                    color = BikerColors.PrimaryAmber,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "[${currentParticipant}]",
                    color = if (currentParticipant == "P0") BikerColors.StatusYellow else BikerColors.StatusGreen,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "1. Selecciona el código de participante:",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val participants = listOf("P0", "P1", "P2", "P3", "P4", "P5", "P6")
                    participants.forEach { p ->
                        val isSelected = p == currentParticipant
                        FilterChip(
                            selected = isSelected,
                            onClick = { UxTestTracker.setParticipant(p, context) },
                            label = {
                                Text(
                                    text = if (p == "P0") "P0 (Apoyo)" else p,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                if (currentParticipant == "P0") {
                    Text(
                        text = "ℹ P0 = Teléfono de apoyo del moderador. Se usará para unirse al grupo de T4 y no contamina las métricas de los testers.",
                        color = BikerColors.StatusYellow,
                        fontSize = 11.sp
                    )
                }

                val currentShortId = UxTestTracker.participantToShortId(currentParticipant)
                val isCurrentLeader = currentShortId == leaderShortId

                Text(
                    text = "ID de malla asignado a este teléfono: #$currentShortId (${if (isCurrentLeader) "LÍDER" else "MIEMBRO"})",
                    color = if (isCurrentLeader) BikerColors.PrimaryAmber else BikerColors.StatusGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Líder de la rodada (fuente para 'Seguir Líder'):",
                    color = BikerColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val possibleLeaders = listOf("P1" to 1u.toUShort(), "P2" to 2u.toUShort(), "P3" to 3u.toUShort())
                    possibleLeaders.forEach { (label, sId) ->
                        val isLeaderSelected = leaderShortId == sId
                        FilterChip(
                            selected = isLeaderSelected,
                            onClick = { UxTestTracker.setLeaderShortId(sId) },
                            label = {
                                Text(
                                    text = "$label (#$sId)",
                                    fontSize = 11.sp,
                                    fontWeight = if (isLeaderSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                HorizontalDivider(color = BikerColors.SkeletonBaseDark)

                Text(
                    text = "2. Cronómetro de Tareas T1–T10:",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                taskDescriptions.forEach { (taskId, label) ->
                    val isActive = activeTask == taskId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isActive) BikerColors.PrimaryAmber.copy(alpha = 0.15f)
                                else BikerColors.SurfaceDark,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = if (isActive) 1.5.dp else 0.dp,
                                color = if (isActive) BikerColors.PrimaryAmber else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$taskId: $label",
                                color = BikerColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isActive) {
                                Text(
                                    text = "EN CURSO...",
                                    color = BikerColors.PrimaryAmber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (!isActive) {
                            Button(
                                onClick = { UxTestTracker.startTask(taskId) },
                                colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(text = "Iniciar", fontSize = 11.sp, color = BikerColors.BackgroundDark)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { UxTestTracker.endTask(taskId, successLevel = 2) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BikerColors.StatusGreen),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(text = "Solo", fontSize = 10.sp, color = Color.White)
                                }
                                Button(
                                    onClick = { UxTestTracker.endTask(taskId, successLevel = 1) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BikerColors.StatusYellow),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(text = "Ayuda", fontSize = 10.sp, color = BikerColors.BackgroundDark)
                                }
                                Button(
                                    onClick = { UxTestTracker.endTask(taskId, successLevel = 0) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BikerColors.StatusRed),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(text = "Fallo", fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = BikerColors.SkeletonBaseDark)

                Text(
                    text = "Simulaciones de Prueba",
                    color = BikerColors.PrimaryAmber,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SOS por malla (prueba)",
                            color = BikerColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (sosMeshBroadcastEnabled) "ON: emite SOS a otros teléfonos" else "OFF: SOS solo local (sin emisión BLE)",
                            color = if (sosMeshBroadcastEnabled) BikerColors.StatusYellow else BikerColors.TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = sosMeshBroadcastEnabled,
                        onCheckedChange = { UxTestTracker.setSosMeshBroadcastEnabled(it) }
                    )
                }

                OutlinedButton(
                    onClick = {
                        UxTestTracker.triggerSimulatedLostPeerAlert()
                        feedbackMessage = "Alerta de compañero perdido (T8) simulada en pantalla."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Simular Compañero Perdido (T8 Local)", fontSize = 11.sp, color = BikerColors.StatusYellow)
                }

                HorizontalDivider(color = BikerColors.SkeletonBaseDark)

                feedbackMessage?.let { msg ->
                    Text(text = msg, color = BikerColors.StatusGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val file = UxTestTracker.saveReportToFile(context)
                        val json = UxTestTracker.exportReportJson(context)
                        clipboardManager.setText(AnnotatedString(json))
                        feedbackMessage = "Guardado en ${file.name} y copiado al portapapeles."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber)
                ) {
                    Text(
                        text = "Exportar y Copiar JSON",
                        color = BikerColors.BackgroundDark,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {
                        UxTestTracker.shareReportJson(context)
                        feedbackMessage = "Abriendo selector para compartir JSON..."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Compartir JSON (Share Intent)", color = BikerColors.PrimaryAmber)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(text = "Cerrar")
            }
        },
        containerColor = BikerColors.SurfaceDark,
        textContentColor = BikerColors.TextPrimary
    )
}
