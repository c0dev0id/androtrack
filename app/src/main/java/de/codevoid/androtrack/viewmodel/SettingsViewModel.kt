package de.codevoid.androtrack.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import de.codevoid.androtrack.TrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val emulatePower: Boolean = false,
    val sensorRecording: Boolean = true,
    val updateIntervalSec: Float = 0.2f,
    val minDistanceM: Float = 0f,
    val maxAccuracyM: Float = 20f
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("androtrack_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load() = AppSettings(
        emulatePower = prefs.getBoolean("pref_emulate_power", false),
        sensorRecording = prefs.getBoolean("pref_sensor_recording", true),
        updateIntervalSec = prefs.getFloat("pref_update_interval_sec", 0.2f),
        minDistanceM = prefs.getFloat("pref_min_distance_m", 0f),
        maxAccuracyM = prefs.getFloat("pref_max_accuracy_m", 20f)
    )

    fun updateSettings(new: AppSettings) {
        prefs.edit()
            .putBoolean("pref_emulate_power", new.emulatePower)
            .putBoolean("pref_sensor_recording", new.sensorRecording)
            .putFloat("pref_update_interval_sec", new.updateIntervalSec)
            .putFloat("pref_min_distance_m", new.minDistanceM)
            .putFloat("pref_max_accuracy_m", new.maxAccuracyM)
            .apply()
        _settings.value = new

        if (TrackingService.isRunning) {
            val ctx = getApplication<Application>()
            ctx.startService(Intent(ctx, TrackingService::class.java).apply {
                action = TrackingService.ACTION_RELOAD_SETTINGS
            })
        }
    }
}
