package com.androtrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.androtrack.START"
        const val ACTION_STOP = "com.androtrack.STOP"
        const val ACTION_TRACKING_STARTED = "com.androtrack.TRACKING_STARTED"
        const val ACTION_TRACKING_STOPPED = "com.androtrack.TRACKING_STOPPED"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "TRACK_CHANNEL"
        private const val NO_MOVEMENT_TIMEOUT_MS = 20 * 60 * 1000L
        private const val MOTION_THRESHOLD = 0.8f
        private const val LOCATION_INTERVAL_MS = 200L  // 5 Hz; hardware delivers at fastest available rate if slower
        private const val LOCATION_MIN_DISTANCE = 0f
    }

    data class GpxTrackPoint(
        val lat: Double,
        val lon: Double,
        val speed: Float,
        val timeMs: Long,
        val ele: Double = 0.0
    )

    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val trackPoints = mutableListOf<GpxTrackPoint>()
    private var currentGpxFile: File? = null
    private var isRecording = false

    private val handler = Handler(Looper.getMainLooper())
    private val stopTimer = Runnable { stopRecording() }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (isRecording) {
                val point = GpxTrackPoint(
                    lat = location.latitude,
                    lon = location.longitude,
                    speed = if (location.hasSpeed()) location.speed else 0f,
                    timeMs = location.time,
                    ele = if (location.hasAltitude()) location.altitude else 0.0
                )
                trackPoints.add(point)
                resetStopTimer()
            }
        }

        @Deprecated("Deprecated in API level 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt(x * x + y * y + z * z)
            if (magnitude > MOTION_THRESHOLD) {
                if (!isRecording) {
                    startRecording()
                } else {
                    resetStopTimer()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
        registerMotionSensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                if (!isRecording) startRecording()
            }
            ACTION_STOP -> {
                if (isRecording) stopRecording()
                stopSelf()
            }
            else -> {
                startForegroundWithNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stopTimer)
        if (isRecording) stopRecording()
        unregisterMotionSensor()
        stopLocationUpdates()
        releaseWakeLock()
    }

    private fun startForegroundWithNotification() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroTrack")
            .setContentText(if (isRecording) "Recording track..." else "Waiting for movement...")
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
        nm.createNotificationChannel(channel)
    }

    private fun registerMotionSensor() {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        sensorManager?.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterMotionSensor() {
        sensorManager?.unregisterListener(sensorListener)
    }

    private fun startRecording() {
        isRecording = true
        trackPoints.clear()
        val timestamp = fileNameFormat.format(Date())
        val dir = getExternalFilesDir(null) ?: filesDir
        currentGpxFile = File(dir, "track_$timestamp.gpx")
        acquireWakeLock()
        startLocationUpdates()
        resetStopTimer()
        sendBroadcast(Intent(ACTION_TRACKING_STARTED))
        updateNotification()
    }

    private fun stopRecording() {
        isRecording = false
        handler.removeCallbacks(stopTimer)
        stopLocationUpdates()
        writeGpxFile()
        releaseWakeLock()
        sendBroadcast(Intent(ACTION_TRACKING_STOPPED))
        updateNotification()
    }

    private fun startLocationUpdates() {
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                LOCATION_MIN_DISTANCE,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    private fun stopLocationUpdates() {
        locationManager?.removeUpdates(locationListener)
    }

    private fun resetStopTimer() {
        handler.removeCallbacks(stopTimer)
        handler.postDelayed(stopTimer, NO_MOVEMENT_TIMEOUT_MS)
    }

    private fun writeGpxFile() {
        val file = currentGpxFile ?: return
        if (trackPoints.isEmpty()) {
            file.delete()
            return
        }
        try {
            FileWriter(file).use { writer ->
                writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
                writer.write("\n")
                writer.write("""<gpx version="1.1" creator="AndroTrack" xmlns="http://www.topografix.com/GPX/1/1">""")
                writer.write("\n")
                writer.write("  <trk>\n")
                writer.write("    <name>Track ${fileNameFormat.format(Date(trackPoints.first().timeMs))}</name>\n")
                writer.write("    <trkseg>\n")
                for (pt in trackPoints) {
                    writer.write("""      <trkpt lat="${pt.lat}" lon="${pt.lon}">""")
                    writer.write("\n")
                    writer.write("        <ele>${pt.ele}</ele>\n")
                    writer.write("        <time>${isoFormat.format(Date(pt.timeMs))}</time>\n")
                    writer.write("        <speed>${pt.speed}</speed>\n")
                    writer.write("      </trkpt>\n")
                }
                writer.write("    </trkseg>\n")
                writer.write("  </trk>\n")
                writer.write("</gpx>\n")
            }
        } catch (e: Exception) {
            // Failed to write GPX
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
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
