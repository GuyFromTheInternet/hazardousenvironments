package com.abandonsearch.hazardgrid.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

class SettingsRepository(
    private val context: Context
) {
    private val dataStore = context.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            defaultMapApp = MapApp.fromName(preferences[Keys.MAP_APP]),
            mergeShapesEnabled = preferences[Keys.MERGE_SHAPES] ?: true
        )
    }

    suspend fun setDefaultMapApp(mapApp: MapApp) {
        dataStore.edit { prefs -> prefs[Keys.MAP_APP] = mapApp.name }
    }

    suspend fun setMergeShapesEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.MERGE_SHAPES] = enabled }
    }

    private object Keys {
        val MAP_APP: Preferences.Key<String> = stringPreferencesKey("default_map_app")
        val MERGE_SHAPES: Preferences.Key<Boolean> = booleanPreferencesKey("merge_shapes")
    }
}
