package com.rutabikersync.app.core.clubroutes

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.clubProfileDataStore: DataStore<Preferences> by
    preferencesDataStore("rbs_club_profile_cache")

/** Retrato del perfil listo para pintar en el arranque, antes de cualquier red. */
data class CachedClubProfile(
    val groupId: String,
    val profileVersion: Int?,
    val profileJson: String
)

/**
 * DataStore con el `profileVersion` y el perfil del club. Permite lectura
 * inmediata al arrancar y evita depender del backend para mostrar el club.
 * Clave por `groupId`: un mismo dispositivo puede pertenecer a varios clubes.
 */
class ClubProfileStore(private val context: Context) {

    companion object {
        private fun versionKey(groupId: String) = intPreferencesKey("profile_version_$groupId")
        private fun profileKey(groupId: String) = stringPreferencesKey("profile_json_$groupId")
    }

    suspend fun save(groupId: String, profileVersion: Int?, profileJson: String) {
        context.clubProfileDataStore.edit { prefs ->
            if (profileVersion != null) prefs[versionKey(groupId)] = profileVersion
            prefs[profileKey(groupId)] = profileJson
        }
    }

    suspend fun get(groupId: String): CachedClubProfile? =
        context.clubProfileDataStore.data.map { prefs ->
            val json = prefs[profileKey(groupId)] ?: return@map null
            CachedClubProfile(
                groupId = groupId,
                profileVersion = prefs[versionKey(groupId)],
                profileJson = json
            )
        }.first()
}
