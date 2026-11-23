package com.rutabikersync.app.core.gamification

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import com.rutabikersync.app.core.clubroutes.ClubRouteDao
import com.rutabikersync.app.core.clubroutes.ClubRouteEntity
import com.rutabikersync.app.core.clubroutes.RsvpOutboxDao
import com.rutabikersync.app.core.clubroutes.RideLifecycleOutboxDao
import com.rutabikersync.app.core.clubroutes.RideLifecycleOutboxEntity
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
        RsvpOutboxEntity::class,
        RideLifecycleOutboxEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingGamificationEventDao(): PendingGamificationEventDao
    abstract fun pendingSosRetryDao(): PendingSosRetryDao
    abstract fun clubRouteDao(): ClubRouteDao
    abstract fun rsvpOutboxDao(): RsvpOutboxDao
    abstract fun rideLifecycleOutboxDao(): RideLifecycleOutboxDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE club_routes_cache ADD COLUMN description TEXT")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rsvp_outbox ADD COLUMN operation TEXT NOT NULL DEFAULT 'rsvp'")
                db.execSQL("ALTER TABLE club_route_rsvps_cache ADD COLUMN onSiteAt TEXT")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ride_lifecycle_outbox (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "routeId TEXT NOT NULL, " +
                        "event TEXT NOT NULL, " +
                        "sequence INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "synced INTEGER NOT NULL, " +
                        "attempts INTEGER NOT NULL, " +
                        "lastError TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ride_lifecycle_outbox_routeId " +
                        "ON ride_lifecycle_outbox (routeId)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ruta_biker_sync.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
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
