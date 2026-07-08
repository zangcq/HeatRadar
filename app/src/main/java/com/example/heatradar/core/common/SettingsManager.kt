package com.example.heatradar.core.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val highFrequencySampling: Boolean = false,
    val longDataRetention: Boolean = false,
    val anomalyAlerts: Boolean = true,
    val showSystemProcesses: Boolean = false,
    val floatingWindowEnabled: Boolean = false,
    val foregroundMonitorEnabled: Boolean = false
)

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val HIGH_FREQ = booleanPreferencesKey("high_freq_sampling")
        val LONG_RETENTION = booleanPreferencesKey("long_retention")
        val ANOMALY_ALERTS = booleanPreferencesKey("anomaly_alerts")
        val SHOW_SYSTEM = booleanPreferencesKey("show_system_processes")
        val FLOATING_WINDOW = booleanPreferencesKey("floating_window")
        val FOREGROUND_MONITOR = booleanPreferencesKey("foreground_monitor")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            highFrequencySampling = prefs[Keys.HIGH_FREQ] ?: false,
            longDataRetention = prefs[Keys.LONG_RETENTION] ?: false,
            anomalyAlerts = prefs[Keys.ANOMALY_ALERTS] ?: true,
            showSystemProcesses = prefs[Keys.SHOW_SYSTEM] ?: false,
            floatingWindowEnabled = prefs[Keys.FLOATING_WINDOW] ?: false,
            foregroundMonitorEnabled = prefs[Keys.FOREGROUND_MONITOR] ?: false
        )
    }

    suspend fun setHighFrequency(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HIGH_FREQ] = enabled }
    }

    suspend fun setLongRetention(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LONG_RETENTION] = enabled }
    }

    suspend fun setAnomalyAlerts(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ANOMALY_ALERTS] = enabled }
    }

    suspend fun setShowSystemProcesses(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SYSTEM] = enabled }
    }

    suspend fun setFloatingWindow(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FLOATING_WINDOW] = enabled }
    }

    suspend fun setForegroundMonitor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FOREGROUND_MONITOR] = enabled }
    }
}
