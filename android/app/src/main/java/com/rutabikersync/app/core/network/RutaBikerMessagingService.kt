package com.rutabikersync.app.core.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rutabikersync.app.MainActivity
import com.rutabikersync.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Servicio FCM para recepción en segundo plano y primer plano de notificaciones push de Ruta Biker Sync (Fase 7.1.2).
 *
 * Canales canónicos:
 * - sos_channel: Alertas de emergencia y proximidad SOS (Prioridad ALTA / Pantalla bloqueada / Vibración continua).
 * - ride_invitation_channel: Convocatorias e invitaciones a rodadas.
 * - club_invitation_channel: Invitaciones y solicitudes de hermandad y clubes.
 * - direct_message_channel: Mensajes de radio PTT y chat táctico.
 *
 * Cero datos sensibles: Los payloads de datos solo contienen identificadores opacos y metadatos no clínicos.
 */
class RutaBikerMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "RBS_FCM"

        const val CHANNEL_SOS = "sos_channel"
        const val CHANNEL_RIDE = "ride_invitation_channel"
        const val CHANNEL_CLUB = "club_invitation_channel"
        const val CHANNEL_CHAT = "direct_message_channel"

        private const val PREFS_FCM = "rbs_fcm_prefs"
        private const val KEY_CACHED_TOKEN = "cached_fcm_token"

        /**
         * Inicializa los 4 canales canónicos de notificación en Android O+ (API 26+)
         */
        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        ?: return

                // 1. Canal SOS de Emergencia (Máxima prioridad y alerta en pantalla de bloqueo)
                val sosChannel = NotificationChannel(
                    CHANNEL_SOS,
                    "Alertas de Emergencia SOS",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alertas críticas de SOS y proximidad para motociclistas en ruta"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    setSound(
                        soundUri,
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                    )
                }

                // 2. Canal Invitaciones a Rodadas
                val rideChannel = NotificationChannel(
                    CHANNEL_RIDE,
                    "Invitaciones a Rodadas",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notificaciones de convocatorias, rutas y eventos biker activos"
                    enableVibration(true)
                }

                // 3. Canal Hermandad / Club
                val clubChannel = NotificationChannel(
                    CHANNEL_CLUB,
                    "Invitaciones de Hermandad",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notificaciones de membresía y clubes de motociclistas"
                    enableVibration(true)
                }

                // 4. Canal Mensajes y Comunicaciones
                val chatChannel = NotificationChannel(
                    CHANNEL_CHAT,
                    "Mensajes y Comunicaciones",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Comunicaciones de radio PTT y chat táctico entre miembros"
                    enableVibration(true)
                }

                notificationManager.createNotificationChannels(
                    listOf(sosChannel, rideChannel, clubChannel, chatChannel)
                )
                Log.d(TAG, "4 canales de notificación FCM creados correctamente")
            }
        }

        fun getCachedToken(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_FCM, Context.MODE_PRIVATE)
            return prefs.getString(KEY_CACHED_TOKEN, null)
        }

        fun saveCachedToken(context: Context, token: String) {
            val prefs = context.getSharedPreferences(PREFS_FCM, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CACHED_TOKEN, token).apply()
        }

        /**
         * Obtiene el token FCM actual de Firebase e intenta registrarlo en el backend si el usuario está autenticado.
         */
        fun syncFcmToken(context: Context, client: ClubApiClient = ClubApiClient.getInstance()) {
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w(TAG, "No se pudo obtener el token de FirebaseMessaging: ${task.exception?.message}")
                        return@addOnCompleteListener
                    }
                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        saveCachedToken(context, token)
                        Log.i(TAG, "Token FCM obtenido exitosamente")
                        if (client.accessToken != null || client.currentUserId != null) {
                            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                                try {
                                    val res = client.registerFcmToken(token, "android")
                                    if (res.isSuccess) {
                                        Log.i(TAG, "Token FCM sincronizado con el backend exitosamente")
                                    } else {
                                        Log.w(TAG, "Fallo al registrar token en backend: ${res.getOrNull()?.statusCode}")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error enviando token FCM al servidor: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "FirebaseMessaging no disponible o no inicializado: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(applicationContext)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Nuevo token FCM recibido")
        saveCachedToken(applicationContext, token)

        val client = ClubApiClient.getInstance()
        if (client.accessToken != null || client.currentUserId != null) {
            serviceScope.launch {
                try {
                    val result = client.registerFcmToken(token, "android")
                    if (result.isSuccess) {
                        Log.i(TAG, "Token registrado en backend Fastify")
                    } else {
                        Log.w(TAG, "Respuesta no exitosa al registrar token: ${result.getOrNull()?.statusCode}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error registrando token en backend Fastify: ${e.message}")
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "Mensaje FCM recibido de: ${remoteMessage.from}")

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val type = data["type"] ?: "direct_message"
        val title = data["title"] ?: notification?.title ?: defaultTitleForType(type)
        val body = data["body"] ?: notification?.body ?: defaultBodyForType(type)

        showNotification(type, title, body, data)
    }

    fun showNotification(
        type: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ) {
        val channelId = when (type) {
            "sos" -> CHANNEL_SOS
            "ride_invitation" -> CHANNEL_RIDE
            "club_invitation" -> CHANNEL_CLUB
            "direct_message" -> CHANNEL_CHAT
            else -> CHANNEL_CHAT
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("NOTIFICATION_TYPE", type)
            when (type) {
                "sos" -> {
                    putExtra("START_RIDE_PREVIEW", true)
                    putExtra("SOS_ALERT", true)
                    putExtra("SOS_LAT", data["lat"])
                    putExtra("SOS_LNG", data["lng"])
                }
                "ride_invitation" -> {
                    putExtra("START_RIDE_PREVIEW", true)
                    putExtra("RIDE_ID", data["rideId"])
                }
                "club_invitation" -> {
                    putExtra("START_CLUB_TAB", true)
                    putExtra("GROUP_ID", data["groupId"])
                }
                "direct_message" -> {
                    putExtra("START_TACTICAL_CHAT", true)
                }
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_logo_ruta_biker_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (type == "sos") {
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }

        try {
            val notificationManager = NotificationManagerCompat.from(this)
            val notificationId = (System.currentTimeMillis() % 100000).toInt()
            notificationManager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Permiso POST_NOTIFICATIONS no concedido para mostrar notificación: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando notificación push: ${e.message}")
        }
    }

    private fun defaultTitleForType(type: String): String = when (type) {
        "sos" -> "🚨 ALERTA SOS CERCANA"
        "ride_invitation" -> "🏍️ Convocatoria a Rodada"
        "club_invitation" -> "🛡️ Invitación de Hermandad"
        "direct_message" -> "💬 Mensaje de Ruta"
        else -> "Ruta Biker Sync"
    }

    private fun defaultBodyForType(type: String): String = when (type) {
        "sos" -> "Emergencia en ruta reportada a menos de 5 km"
        "ride_invitation" -> "Tienes una nueva invitación a rodar con tu club"
        "club_invitation" -> "Has recibido una solicitud o invitación de membresía"
        "direct_message" -> "Nuevo mensaje de radio / chat en tu grupo"
        else -> "Nueva notificación disponible"
    }
}
