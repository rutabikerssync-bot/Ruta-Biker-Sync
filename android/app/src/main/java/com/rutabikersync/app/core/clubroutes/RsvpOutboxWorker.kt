package com.rutabikersync.app.core.clubroutes

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rutabikersync.app.core.gamification.AppDatabase
import com.rutabikersync.app.core.network.ClubApiClient
import java.util.concurrent.TimeUnit

/**
 * Worker de WorkManager que vacía el outbox de RSVP al recuperar conectividad.
 * Espejo de `GamificationSyncWorker` (Fase 6): `NetworkType.CONNECTED` + backoff exponencial.
 */
class RsvpOutboxWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getInstance(applicationContext).rsvpOutboxDao()
        val api = ClubApiClient.getInstance()

        val transport = object : RsvpTransport {
            override suspend fun sendRsvp(
                routeId: String,
                userId: String,
                status: String
            ): RsvpSendResult {
                val res = api.postRsvp(routeId, status).getOrElse {
                    return RsvpSendResult.Error(it.message ?: "fallo de red")
                }
                return when (res.statusCode) {
                    in 200..299 -> RsvpSendResult.Accepted // 201 y 200 son ambos OK (idempotencia)
                    409 -> RsvpSendResult.Rejected("INVALID_RSVP_TRANSITION")
                    401, 429 -> RsvpSendResult.Error("AUTH_OR_RATE_LIMIT_${res.statusCode}")
                    in 400..499 -> RsvpSendResult.Rejected("HTTP_${res.statusCode}")
                    else -> RsvpSendResult.Error("HTTP_${res.statusCode}")
                }
            }

            override suspend fun sendArrival(routeId: String, userId: String): RsvpSendResult {
                val res = api.postRouteArrival(routeId).getOrElse {
                    return RsvpSendResult.Error(it.message ?: "fallo de red")
                }
                return when (res.statusCode) {
                    in 200..299 -> RsvpSendResult.Accepted
                    409 -> RsvpSendResult.Rejected("RSVP_NOT_CONFIRMED")
                    401, 429 -> RsvpSendResult.Error("AUTH_OR_RATE_LIMIT_${res.statusCode}")
                    in 400..499 -> RsvpSendResult.Rejected("HTTP_${res.statusCode}")
                    else -> RsvpSendResult.Error("HTTP_${res.statusCode}")
                }
            }
        }

        return when (val result = RsvpOutboxSyncEngine(dao, transport).flush()) {
            is RsvpOutboxSyncEngine.SyncResult.Success,
            RsvpOutboxSyncEngine.SyncResult.NoOp -> Result.success()
            is RsvpOutboxSyncEngine.SyncResult.Failure -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "RsvpOutboxWorker"

        /** Encola el vaciado; `KEEP` evita duplicar trabajo si ya hay uno pendiente. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<RsvpOutboxWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
