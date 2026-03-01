package com.androtrack

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.androtrack.START"
        const val ACTION_STOP = "com.androtrack.STOP"
        const val ACTION_TRACKING_STARTED = "com.androtrack.TRACKING_STARTED"
        const val ACTION_TRACKING_STOPPED = "com.androtrack.TRACKING_STOPPED"
        /** Sent when the charger is disconnected; service stays alive waiting for power to reconnect. */
        const val ACTION_TRACKING_PAUSED = "com.androtrack.TRACKING_PAUSED"
        const val ACTION_STATS_UPDATE = "com.androtrack.STATS_UPDATE"
        const val ACTION_RELOAD_SETTINGS = "com.androtrack.RELOAD_SETTINGS"

        const val EXTRA_DISTANCE_M = "distance_m"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_PAUSE_TIMEOUT_MS = "pause_timeout_ms"
        const val EXTRA_CURRENT_ACCURACY = "current_accuracy"
        const val EXTRA_AVG_ACCURACY = "avg_accuracy"
        const val EXTRA_CURRENT_UPDATE_RATE = "current_update_rate"
        const val EXTRA_AVG_UPDATE_RATE = "avg_update_rate"
        /** Milliseconds the service has been in the paused state; 0 while actively recording. */
        const val EXTRA_PAUSED_FOR_MS = "paused_for_ms"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "TRACK_CHANNEL"
        /**
         * Session-finalize timeout: 20 minutes (1200 s).
         * If the charger remains disconnected for this long, the current GPX file is
         * finalised and session state is cleared.  The service keeps running.
         * The next time power is connected a new file is opened.
         * If power reconnects before this timer fires, recording resumes into the same file.
         */
        private const val NO_MOVEMENT_TIMEOUT_MS = 20 * 60 * 1000L
        private const val DEFAULT_LOCATION_INTERVAL_MS = 1000L
        private const val DEFAULT_LOCATION_MIN_DISTANCE = 1f
        private const val STATS_UPDATE_INTERVAL_MS = 1000L
        private const val INCREMENT_FLUSH_INTERVAL_MS = 10_000L

        @Volatile
        var isRunning = false
            private set
    }

    private var emulatePower = false
    private var locationIntervalMs = DEFAULT_LOCATION_INTERVAL_MS
    private var locationMinDistance = DEFAULT_LOCATION_MIN_DISTANCE

    private fun loadPreferences() {
        val prefs = getSharedPreferences("androtrack_settings", Context.MODE_PRIVATE)
        emulatePower = prefs.getBoolean("pref_emulate_power", false)
        val intervalSec = prefs.getFloat("pref_update_interval_sec", DEFAULT_LOCATION_INTERVAL_MS / 1000f)
        locationIntervalMs = (intervalSec * 1000).toLong()
        locationMinDistance = prefs.getFloat("pref_min_distance_m", DEFAULT_LOCATION_MIN_DISTANCE)
        sensorRecordingEnabled = prefs.getBoolean("pref_sensor_recording", true)
    }

    data class GpxTrackPoint(
        val lat: Double,
        val lon: Double,
        val speed: Float,
        val timeMs: Long,
        val ele: Double = 0.0,
        val leanAngleDeg: Float = Float.NaN,
        val longitudinalAccelMps2: Float = Float.NaN
    )

    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bikeSensorManager: BikeSensorManager? = null
    private var sensorRecordingEnabled = true

    private val trackPoints = mutableListOf<GpxTrackPoint>()
    private var currentGpxFile: File? = null
    private var isRecording = false
    private var flushSequenceNumber = 0
    private var sessionTimestamp = ""
    private var totalDistanceM = 0.0
    private var lastTrackPoint: GpxTrackPoint? = null

    private val handler = Handler(Looper.getMainLooper())
    /**
     * Fires after NO_MOVEMENT_TIMEOUT_MS on battery (charger disconnected).
     * Finalises the current GPX file and clears session state so that the next
     * power-connection opens a fresh file.  The service itself keeps running.
     */
    private val stopTimer = Runnable {
        finalizeSession()
        updateNotification()
    }

    // Stats tracking
    private var recordingStartMs = 0L
    /** Epoch ms at which the service entered the paused state; 0 while actively recording. */
    private var pausedAtMs = 0L
    private var currentAccuracy = 0f
    private var accuracySum = 0.0
    private var accuracyCount = 0L
    private var lastLocationTimeNs = 0L
    private var currentUpdateRateHz = 0f
    private var updateRateSum = 0.0
    private var updateRateCount = 0L

    private val statsUpdater = object : Runnable {
        override fun run() {
            broadcastStats()
            handler.postDelayed(this, STATS_UPDATE_INTERVAL_MS)
        }
    }

    private val incrementFlusher = object : Runnable {
        override fun run() {
            flushIncrement()
            if (isRecording) {
                handler.postDelayed(this, INCREMENT_FLUSH_INTERVAL_MS)
            }
        }
    }

    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Track GPS accuracy
            if (location.hasAccuracy()) {
                currentAccuracy = location.accuracy
                accuracySum += currentAccuracy
                accuracyCount++
            }

            // Track update rate
            val nowNs = System.nanoTime()
            if (lastLocationTimeNs > 0) {
                val deltaSec = (nowNs - lastLocationTimeNs) / 1_000_000_000.0
                if (deltaSec > 0) {
                    currentUpdateRateHz = (1.0 / deltaSec).toFloat()
                    updateRateSum += currentUpdateRateHz
                    updateRateCount++
                }
            }
            lastLocationTimeNs = nowNs

            if (isRecording) {
                val sensor = bikeSensorManager
                val point = GpxTrackPoint(
                    lat = location.latitude,
                    lon = location.longitude,
                    speed = if (location.hasSpeed()) location.speed else 0f,
                    timeMs = location.time,
                    ele = if (location.hasAltitude()) location.altitude else 0.0,
                    leanAngleDeg = if (sensor?.isActive == true) sensor.leanAngleDeg else Float.NaN,
                    longitudinalAccelMps2 = if (sensor?.isActive == true) sensor.longitudinalAccel else Float.NaN
                )
                val last = lastTrackPoint
                if (last != null) {
                    totalDistanceM += haversine(last.lat, last.lon, point.lat, point.lon) * 1000.0
                }
                lastTrackPoint = point
                trackPoints.add(point)
            }
        }

        @Deprecated("Deprecated in API level 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    /**
     * Responds to USB/charger connection changes.
     * Connected → start or resume recording.
     * Disconnected → pause immediately and start the 20-min session-finalize countdown.
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (emulatePower) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> if (!isRecording) startRecording()
                Intent.ACTION_POWER_DISCONNECTED -> if (isRecording) stopRecording()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        loadPreferences()
        createNotificationChannel()
        registerPowerReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!hasLocationPermission()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startForegroundWithNotification()) return START_NOT_STICKY
                if (!isRecording) {
                    startRecording()
                    if (!isCharging() && !emulatePower) stopRecording()
                }
            }
            ACTION_STOP -> {
                stopServiceFully()
                stopSelf()
            }
            ACTION_RELOAD_SETTINGS -> {
                loadPreferences()
                if (isRecording) {
                    stopLocationUpdates()
                    startLocationUpdates()
                    if (sensorRecordingEnabled && bikeSensorManager == null) {
                        bikeSensorManager = BikeSensorManager(this)
                        bikeSensorManager?.start()
                    } else if (!sensorRecordingEnabled && bikeSensorManager != null) {
                        bikeSensorManager?.stop()
                        bikeSensorManager = null
                    }
                }
                if (emulatePower && !isRecording) {
                    startRecording()
                }
                if (!emulatePower && isRecording && !isCharging()) {
                    stopRecording()
                }
                updateNotification()
            }
            else -> {
                if (!hasLocationPermission()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startForegroundWithNotification()) return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopServiceFully()
        unregisterPowerReceiver()
    }

    private fun registerPowerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(powerReceiver, filter)
        }
    }

    private fun unregisterPowerReceiver() {
        try { unregisterReceiver(powerReceiver) } catch (e: Exception) { /* ignore */ }
    }

    /** Returns true if the device is currently powered via USB/charger. */
    private fun isCharging(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun startForegroundWithNotification(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            stopSelf()
            false
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, RideListActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = when {
            isRecording -> "Recording track..."
            emulatePower -> "Ready – power emulated"
            else -> "Waiting for charger..."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroTrack")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AndroTrack Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while AndroTrack is recording a track"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }

    /**
     * Begins or resumes a recording session.
     * - Fresh start (currentGpxFile == null): creates a new GPX file, resets all state.
     * - Resumption (currentGpxFile != null, within the 20-min window): continues appending
     *   to the existing file and track-point list.
     */
    private fun startRecording() {
        handler.removeCallbacks(stopTimer)  // cancel any pending session-finalize timer
        isRecording = true
        isRunning = true
        pausedAtMs = 0L

        val isFreshStart = currentGpxFile == null
        if (isFreshStart) {
            trackPoints.clear()
            recordingStartMs = System.currentTimeMillis()
            currentAccuracy = 0f
            accuracySum = 0.0
            accuracyCount = 0
            lastLocationTimeNs = 0L
            currentUpdateRateHz = 0f
            updateRateSum = 0.0
            updateRateCount = 0
            totalDistanceM = 0.0
            lastTrackPoint = null
            val timestamp = fileNameFormat.format(Date())
            val dir = getExternalFilesDir(null) ?: filesDir
            currentGpxFile = File(dir, "track_$timestamp.gpx")
            sessionTimestamp = timestamp
            flushSequenceNumber = 0
            IncrementManager.getIncrementDir(dir)
        }

        acquireWakeLock()
        if (isFreshStart && sensorRecordingEnabled) {
            bikeSensorManager = BikeSensorManager(this)
            bikeSensorManager?.start()
        }
        startLocationUpdates()
        handler.removeCallbacks(statsUpdater)
        handler.post(statsUpdater)
        handler.removeCallbacks(incrementFlusher)
        handler.postDelayed(incrementFlusher, INCREMENT_FLUSH_INTERVAL_MS)
        sendBroadcast(Intent(ACTION_TRACKING_STARTED).setPackage(packageName))
        updateNotification()
    }

    /**
     * Pauses recording when the charger is disconnected.
     * Stops GPS and releases the wake lock to save battery, but keeps the service
     * alive and the statsUpdater broadcasting.  If the charger reconnects within
     * NO_MOVEMENT_TIMEOUT_MS the session resumes into the same file; after that
     * timeout the session is finalised and the next power-on starts a new file.
     */
    private fun stopRecording() {
        isRecording = false
        // isRunning stays true — service remains alive waiting for charger
        pausedAtMs = System.currentTimeMillis()
        flushIncrement()
        handler.removeCallbacks(stopTimer)
        handler.postDelayed(stopTimer, NO_MOVEMENT_TIMEOUT_MS)
        // statsUpdater keeps running so the UI card continues to update
        stopLocationUpdates()
        bikeSensorManager?.stop()
        bikeSensorManager = null
        releaseWakeLock()
        sendBroadcast(Intent(ACTION_TRACKING_PAUSED).setPackage(packageName))
        updateNotification()
    }

    /**
     * Full stop: called on explicit ACTION_STOP command or service destruction.
     * Shuts down all recording, GPS, and broadcasting, then signals the UI that
     * tracking has ended completely (ACTION_TRACKING_STOPPED).
     */
    private fun stopServiceFully() {
        isRecording = false
        isRunning = false
        handler.removeCallbacks(stopTimer)
        handler.removeCallbacks(statsUpdater)
        handler.removeCallbacks(incrementFlusher)
        stopLocationUpdates()
        bikeSensorManager?.stop()
        bikeSensorManager = null
        finalizeSession()
        releaseWakeLock()
        sendBroadcast(Intent(ACTION_TRACKING_STOPPED).setPackage(packageName))
        updateNotification()
    }

    /**
     * Flushes any remaining in-memory points to disk, merges all increment
     * files into a final GPX, and resets session state.  Safe to call when
     * there is no active session (all operations are no-ops on empty data).
     */
    private fun finalizeSession() {
        flushIncrement()
        if (sessionTimestamp.isNotEmpty()) {
            val dir = getExternalFilesDir(null) ?: filesDir
            val sessions = IncrementManager.findOrphanedSessions(dir)
            val files = sessions[sessionTimestamp]
            if (files != null && files.isNotEmpty()) {
                IncrementManager.mergeIncrementsToGpx(dir, sessionTimestamp, files)
            }
            IncrementManager.deleteSessionIncrements(dir, sessionTimestamp)
        }
        trackPoints.clear()
        currentGpxFile = null
        sessionTimestamp = ""
        flushSequenceNumber = 0
        totalDistanceM = 0.0
        lastTrackPoint = null
    }

    private fun startLocationUpdates() {
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                locationIntervalMs,
                locationMinDistance,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            // Location updates unavailable
        }
    }

    private fun stopLocationUpdates() {
        locationManager?.removeUpdates(locationListener)
    }

    private fun flushIncrement() {
        if (trackPoints.isEmpty()) return
        val dir = getExternalFilesDir(null) ?: filesDir
        val success = IncrementManager.writeIncrement(
            storageDir = dir,
            sessionTimestamp = sessionTimestamp,
            sequenceNumber = flushSequenceNumber,
            points = trackPoints.toList()
        )
        if (success) {
            trackPoints.clear()
            flushSequenceNumber++
        }
    }

    private fun broadcastStats() {
        val now = System.currentTimeMillis()
        val durationMs = if (recordingStartMs > 0) now - recordingStartMs else 0L
        val distanceM = totalDistanceM
        val fileName = currentGpxFile?.name ?: ""
        val pauseTimeoutMs = if (!isRecording && pausedAtMs > 0) {
            val remaining = NO_MOVEMENT_TIMEOUT_MS - (now - pausedAtMs)
            if (remaining > 0) remaining else 0L
        } else 0L
        val pausedForMs = if (!isRecording && pausedAtMs > 0) now - pausedAtMs else 0L

        val avgAccuracy = if (accuracyCount > 0) (accuracySum / accuracyCount).toFloat() else 0f
        val avgUpdateRate = if (updateRateCount > 0) (updateRateSum / updateRateCount).toFloat() else 0f

        val intent = Intent(ACTION_STATS_UPDATE).apply {
            putExtra(EXTRA_DISTANCE_M, distanceM)
            putExtra(EXTRA_DURATION_MS, durationMs)
            putExtra(EXTRA_IS_RECORDING, isRecording)
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_PAUSE_TIMEOUT_MS, pauseTimeoutMs)
            putExtra(EXTRA_PAUSED_FOR_MS, pausedForMs)
            putExtra(EXTRA_CURRENT_ACCURACY, currentAccuracy)
            putExtra(EXTRA_AVG_ACCURACY, avgAccuracy)
            putExtra(EXTRA_CURRENT_UPDATE_RATE, currentUpdateRateHz)
            putExtra(EXTRA_AVG_UPDATE_RATE, avgUpdateRate)
        }
        sendBroadcast(intent.setPackage(packageName))
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroTrack::TrackingWakeLock")
        wakeLock?.acquire(NO_MOVEMENT_TIMEOUT_MS + 60_000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }
}
