package com.rutabikersync.app.core.gamification

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import com.rutabikersync.app.core.mesh.sos.PendingSosRetryDao
import com.rutabikersync.app.core.mesh.sos.PendingSosRetryEntity

/**
 * Base de datos principal de Room para persistencia local off-grid de Ruta Biker Sync (Fase 6 y Fase 7.1.5.5).
 */
@Database(
    entities = [
        PendingGamificationEvent::class,
        PendingSosRetryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingGamificationEventDao(): PendingGamificationEventDao
    abstract fun pendingSosRetryDao(): PendingSosRetryDao

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
