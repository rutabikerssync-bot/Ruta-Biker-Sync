package com.rutabikersync.app.feature.vault

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import com.rutabikersync.app.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Gestor de Alarmas y Recordatorios para Documentos de la Bóveda Biker (R10.1).
 * Soporta integración con el Calendario de Android (Google Calendar / Samsung / etc.)
 * y AlarmManager del sistema para notificaciones preventivas a 30, 7 y 1 día.
 */
object VaultAlarmScheduler {

    const val CHANNEL_ID = "rbs_vault_alarms_channel"
    private const val CHANNEL_NAME = "Alertas de Vencimiento de Bóveda"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de vencimiento de licencias, seguros y documentos de moto"
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    /**
     * Abre el Calendario nativo de Android con el evento prellenado y alarma activada
     */
    fun openCalendarEvent(
        context: Context,
        doc: BikerDocument,
        daysBefore: Int = 7
    ): Boolean {
        return try {
            val targetTime = if (daysBefore > 0) {
                doc.expirationDateMillis - TimeUnit.DAYS.toMillis(daysBefore.toLong())
            } else {
                doc.expirationDateMillis
            }

            val alertLabel = if (daysBefore > 0) "Alerta Preventiva ($daysBefore días antes)" else "Día de Vencimiento"

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, "🚨 $alertLabel: ${doc.title}")
                putExtra(
                    CalendarContract.Events.DESCRIPTION,
                    """
                    Documento: ${doc.title}
                    Folio / Placa: ${doc.folio}
                    Emisor / Aseguradora: ${doc.issuerOrInsurer}
                    Fecha de Vencimiento Oficial: ${doc.computeExpirationInfo().formattedDate}
                    Notas: ${doc.notes}
                    
                    Alarma generada por Ruta Biker Sync (Regla R10.1).
                    """.trimIndent()
                )
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, targetTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, targetTime + TimeUnit.HOURS.toMillis(2))
                putExtra(CalendarContract.Events.ALL_DAY, true)
                putExtra(CalendarContract.Events.HAS_ALARM, 1)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Programa una alarma local con AlarmManager para emitir una notificación
     */
    fun scheduleLocalAlarm(
        context: Context,
        doc: BikerDocument,
        daysBefore: Int = 7
    ) {
        createNotificationChannel(context)
        val triggerTime = doc.expirationDateMillis - TimeUnit.DAYS.toMillis(daysBefore.toLong())
        if (triggerTime <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, VaultAlarmReceiver::class.java).apply {
            putExtra("doc_title", doc.title)
            putExtra("doc_folio", doc.folio)
            putExtra("days_before", daysBefore)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (doc.id.hashCode() + daysBefore).coerceAtLeast(0),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } catch (_: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }
}

/**
 * Receiver para capturar el disparo del AlarmManager y emitir la notificación heads-up
 */
class VaultAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VaultAlarmScheduler.createNotificationChannel(context)

        val title = intent.getStringExtra("doc_title") ?: "Documento de Bóveda"
        val folio = intent.getStringExtra("doc_folio") ?: ""
        val daysBefore = intent.getIntExtra("days_before", 7)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, VaultAlarmScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("⚠️ Alerta Bóveda: $title")
            .setContentText("El documento con folio $folio vence en $daysBefore día(s). Tramita su renovación.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        nm.notify(title.hashCode(), notification)
    }
}
