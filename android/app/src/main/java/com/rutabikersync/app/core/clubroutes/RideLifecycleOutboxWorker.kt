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

/** Vacia el outbox de ciclo al recuperar conectividad, en orden de secuencia. */
class RideLifecycleOutboxWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getInstance(applicationContext).rideLifecycleOutboxDao()
        val api = ClubApiClient.getInstance()

        val transport = object : RideLifecycleTransport {
            override suspend fun send(
                routeId: String,
                event: RideLifecycleTransition
            ): RideLifecycleSendResult {
                val result = when (event) {
                    RideLifecycleTransition.START_OUTBOUND -> api.startClubRoute(routeId)
                    RideLifecycleTransition.ARRIVE -> api.postRouteArrival(routeId)
                    RideLifecycleTransition.ARRIVE_DESTINATION -> api.arriveDestination(routeId)
                    RideLifecycleTransition.START_RETURN -> api.startReturnRoute(routeId)
                    RideLifecycleTransition.COMPLETE_RIDE -> api.completeRoute(routeId)
                }
                return RideLifecycleHttpMapper.fromResult(result)
            }
        }

        return when (
            val result = RideLifecycleOutboxSyncEngine(dao, transport).flush()
        ) {
            is RideLifecycleOutboxSyncEngine.SyncResult.Success,
            RideLifecycleOutboxSyncEngine.SyncResult.NoOp -> Result.success()
            is RideLifecycleOutboxSyncEngine.SyncResult.Failure -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "RideLifecycleOutboxWorker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<RideLifecycleOutboxWorker>()
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
