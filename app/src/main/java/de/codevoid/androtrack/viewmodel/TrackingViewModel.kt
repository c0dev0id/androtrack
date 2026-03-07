package de.codevoid.androtrack.viewmodel

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import de.codevoid.androtrack.TrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackStats(
    val distanceM: Double = 0.0,
    val durationMs: Long = 0L,
    val isRecording: Boolean = false,
    val fileName: String = "",
    val pauseTimeoutMs: Long = 0L,
    val pausedForMs: Long = 0L,
    val curAccuracy: Float = 0f,
    val avgAccuracy: Float = 0f,
    val curRate: Float = 0f,
    val avgRate: Float = 0f
)

sealed class TrackingState {
    object Idle : TrackingState()
    data class Active(val stats: TrackStats, val isPaused: Boolean) : TrackingState()
}

class TrackingViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<TrackingState>(
        if (TrackingService.isRunning) TrackingState.Active(TrackStats(), false)
        else TrackingState.Idle
    )
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TrackingService.ACTION_TRACKING_STARTED -> {
                    _state.value = TrackingState.Active(TrackStats(), isPaused = false)
                }
                TrackingService.ACTION_TRACKING_PAUSED -> {
                    val current = (_state.value as? TrackingState.Active)?.stats ?: TrackStats()
                    _state.value = TrackingState.Active(current, isPaused = true)
                }
                TrackingService.ACTION_TRACKING_STOPPED -> {
                    _state.value = TrackingState.Idle
                }
                TrackingService.ACTION_STATS_UPDATE -> {
                    val stats = TrackStats(
                        distanceM = intent.getDoubleExtra(TrackingService.EXTRA_DISTANCE_M, 0.0),
                        durationMs = intent.getLongExtra(TrackingService.EXTRA_DURATION_MS, 0L),
                        isRecording = intent.getBooleanExtra(TrackingService.EXTRA_IS_RECORDING, false),
                        fileName = intent.getStringExtra(TrackingService.EXTRA_FILE_NAME) ?: "",
                        pauseTimeoutMs = intent.getLongExtra(TrackingService.EXTRA_PAUSE_TIMEOUT_MS, 0L),
                        pausedForMs = intent.getLongExtra(TrackingService.EXTRA_PAUSED_FOR_MS, 0L),
                        curAccuracy = intent.getFloatExtra(TrackingService.EXTRA_CURRENT_ACCURACY, 0f),
                        avgAccuracy = intent.getFloatExtra(TrackingService.EXTRA_AVG_ACCURACY, 0f),
                        curRate = intent.getFloatExtra(TrackingService.EXTRA_CURRENT_UPDATE_RATE, 0f),
                        avgRate = intent.getFloatExtra(TrackingService.EXTRA_AVG_UPDATE_RATE, 0f)
                    )
                    val isPaused = (_state.value as? TrackingState.Active)?.isPaused ?: !stats.isRecording
                    _state.value = TrackingState.Active(stats, isPaused = !stats.isRecording)
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(TrackingService.ACTION_TRACKING_STARTED)
            addAction(TrackingService.ACTION_TRACKING_PAUSED)
            addAction(TrackingService.ACTION_TRACKING_STOPPED)
            addAction(TrackingService.ACTION_STATS_UPDATE)
        }
        ContextCompat.registerReceiver(
            application, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCleared() {
        super.onCleared()
        try { getApplication<Application>().unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    fun startTracking(): Boolean {
        val ctx = getApplication<Application>()
        val hasLocation = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return false

        val intent = Intent(ctx, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        ContextCompat.startForegroundService(ctx, intent)
        return true
    }

    fun stopTracking() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        ctx.startService(intent)
    }

    fun hasLocationPermission(): Boolean {
        val ctx = getApplication<Application>()
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val ctx = getApplication<Application>()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
