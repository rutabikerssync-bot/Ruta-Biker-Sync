package com.rutabikersync.app.core.gamification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Worker de WorkManager para la sincronización diferida de eventos de gamificación (Fase 6 · Hito 5 · Etapa 3).
 *
 * Se ejecuta automáticamente tan pronto como el sistema operativo detecta conectividad a internet ([NetworkType.CONNECTED]),
 * transmitiendo la cola Room al backend con política de reintento exponencial.
 */
class GamificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.pendingGamificationEventDao()
        val client = DefaultTelemetryApiClient(applicationContext)
        val engine = GamificationSyncEngine(dao, client)

        return when (val result = engine.syncPendingEvents()) {
            is GamificationSyncEngine.SyncResult.Success,
            is GamificationSyncEngine.SyncResult.NoOp -> Result.success()
            is GamificationSyncEngine.SyncResult.Failure -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "GamificationSyncWorker"

        /**
         * Encola una solicitud de sincronización diferida que se disparará al detectar conexión a internet.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<GamificationSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                syncRequest
            )
        }
    }
}
