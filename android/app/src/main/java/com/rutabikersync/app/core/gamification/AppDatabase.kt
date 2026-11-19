package com.rutabikersync.app.core.gamification

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import com.rutabikersync.app.core.clubroutes.ClubRouteDao
import com.rutabikersync.app.core.clubroutes.ClubRouteEntity
import com.rutabikersync.app.core.clubroutes.RsvpOutboxDao
import com.rutabikersync.app.core.clubroutes.RsvpOutboxEntity
import com.rutabikersync.app.core.clubroutes.RsvpEntity
import com.rutabikersync.app.core.clubroutes.RsvpSummaryEntity
import com.rutabikersync.app.core.mesh.sos.PendingSosRetryDao
import com.rutabikersync.app.core.mesh.sos.PendingSosRetryEntity

/**
 * Base de datos principal de Room para persistencia local off-grid de Ruta Biker Sync
 * (Fase 6, Fase 7.1.5.5 y Fase 5 · caché de rutas y outbox de RSVP).
 */
@Database(
    entities = [
        PendingGamificationEvent::class,
        PendingSosRetryEntity::class,
        ClubRouteEntity::class,
        RsvpEntity::class,
        RsvpSummaryEntity::class,
        RsvpOutboxEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingGamificationEventDao(): PendingGamificationEventDao
    abstract fun pendingSosRetryDao(): PendingSosRetryDao
    abstract fun clubRouteDao(): ClubRouteDao
    abstract fun rsvpOutboxDao(): RsvpOutboxDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ruta_biker_sync.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }

        /**
         * Permite inyectar una instancia de prueba para entornos de test.
         */
        fun setTestInstance(instance: AppDatabase?) {
            INSTANCE = instance
        }
    }
}
