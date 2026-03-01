package de.codevoid.androtrack

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import de.codevoid.androtrack.databinding.ActivityTrackDetailBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class TrackDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        private const val MOVING_SPEED_THRESHOLD = 0.556f // ~2 km/h in m/s
        private const val GRAVITY = 9.81
    }

    private lateinit var binding: ActivityTrackDetailBinding
    private var trackPoints: List<GpxParser.TrackPoint> = emptyList()
    private var geoPoints: List<GeoPoint> = emptyList()
    private var trackOverlay: GradientTrackOverlay? = null
    private var currentMode = HistogramView.Mode.ALTITUDE
    private var leanAngles: FloatArray = FloatArray(0)
    private var forces: FloatArray = FloatArray(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)

        binding.chipAltitude.setOnClickListener { switchMode(HistogramView.Mode.ALTITUDE) }
        binding.chipSpeed.setOnClickListener { switchMode(HistogramView.Mode.SPEED) }
        binding.chipLeanAngle.setOnClickListener { switchMode(HistogramView.Mode.LEAN_ANGLE) }
        binding.chipForce.setOnClickListener { switchMode(HistogramView.Mode.FORCE) }

        binding.histogramView.onPositionChanged = { index -> onHistogramTouch(index) }
        binding.histogramView.onTouchReleased = { onHistogramRelease() }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath != null) {
            loadTrack(File(filePath))
        } else {
            finish()
        }
    }

    private fun loadTrack(file: File) {
        supportActionBar?.title = file.nameWithoutExtension

        trackPoints = GpxParser.parseTrackPoints(file) ?: run {
            finish()
            return
        }

        if (trackPoints.size < 2) {
            finish()
            return
        }

        geoPoints = trackPoints.map { GeoPoint(it.lat, it.lon) }
        leanAngles = computeLeanAngles(trackPoints)
        forces = computeForces(trackPoints)

        setupMap()
        switchMode(HistogramView.Mode.ALTITUDE)
        displayStatistics()
    }

    private fun setupMap() {
        val minLat = trackPoints.minOf { it.lat }
        val maxLat = trackPoints.maxOf { it.lat }
        val minLon = trackPoints.minOf { it.lon }
        val maxLon = trackPoints.maxOf { it.lon }

        val boundingBox = BoundingBox(maxLat, maxLon, minLat, minLon)

        binding.mapView.post {
            binding.mapView.zoomToBoundingBox(boundingBox, true, 60)
        }
    }

    private fun switchMode(mode: HistogramView.Mode) {
        currentMode = mode
        binding.chipAltitude.isChecked = mode == HistogramView.Mode.ALTITUDE
        binding.chipSpeed.isChecked = mode == HistogramView.Mode.SPEED
        binding.chipLeanAngle.isChecked = mode == HistogramView.Mode.LEAN_ANGLE
        binding.chipForce.isChecked = mode == HistogramView.Mode.FORCE

        val colors = calculateColors(mode)
        val values = calculateValues(mode)

        trackOverlay?.let { binding.mapView.overlays.remove(it) }
        trackOverlay = GradientTrackOverlay(geoPoints, colors).also {
            binding.mapView.overlays.add(it)
        }
        binding.mapView.invalidate()

        binding.histogramView.mode = mode
        binding.histogramView.setData(values, colors.toIntArray())
    }

    private fun calculateColors(mode: HistogramView.Mode): List<Int> {
        return when (mode) {
            HistogramView.Mode.ALTITUDE -> {
                val startAlt = trackPoints.first().ele
                val maxAlt = trackPoints.maxOf { it.ele }
                trackPoints.map { altitudeToColor(it.ele, startAlt, maxAlt) }
            }
            HistogramView.Mode.SPEED -> {
                trackPoints.map { speedToColor(it.speed * 3.6f) }
            }
            HistogramView.Mode.LEAN_ANGLE -> {
                val maxLean = leanAngles.maxOrNull()?.coerceAtLeast(5.1f) ?: 5.1f
                leanAngles.map { leanAngleToColor(it, maxLean) }
            }
            HistogramView.Mode.FORCE -> {
                val maxAccel = forces.filter { it > 0f }.maxOrNull() ?: 0.01f
                val maxBrake = forces.filter { it < 0f }.minOrNull() ?: -0.01f
                forces.map { forceToColor(it, maxAccel, maxBrake) }
            }
        }
    }

    private fun calculateValues(mode: HistogramView.Mode): FloatArray {
        return when (mode) {
            HistogramView.Mode.ALTITUDE -> trackPoints.map { it.ele.toFloat() }.toFloatArray()
            HistogramView.Mode.SPEED -> trackPoints.map { it.speed * 3.6f }.toFloatArray()
            HistogramView.Mode.LEAN_ANGLE -> leanAngles
            HistogramView.Mode.FORCE -> forces
        }
    }

    private fun altitudeToColor(altitude: Double, startAlt: Double, maxAlt: Double): Int {
        if (maxAlt <= startAlt) return Color.HSVToColor(floatArrayOf(120f, 0.85f, 0.85f))
        val ratio = ((altitude - startAlt) / (maxAlt - startAlt)).coerceIn(0.0, 1.0)
        val hue = (120.0 * (1.0 - ratio)).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.85f))
    }

    private fun speedToColor(speedKmh: Float): Int {
        val clamped = speedKmh.coerceIn(20f, 160f)
        val ratio = (clamped - 20f) / (160f - 20f)
        val hue = 120f * (1f - ratio)
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.85f))
    }

    private fun leanAngleToColor(angle: Float, maxLean: Float): Int {
        if (angle <= 5f) return Color.HSVToColor(floatArrayOf(120f, 0.85f, 0.85f))
        val ratio = ((angle - 5f) / (maxLean - 5f)).coerceIn(0f, 1f)
        val hue = 120f * (1f - ratio)
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.85f))
    }

    private fun forceToColor(forceG: Float, maxAccel: Float, maxBrake: Float): Int {
        if (forceG >= 0f) {
            val ratio = if (maxAccel > 0f) (forceG / maxAccel).coerceIn(0f, 1f) else 0f
            val hue = 120f * (1f - ratio)
            return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.85f))
        } else {
            val ratio = if (maxBrake < 0f) (abs(forceG) / abs(maxBrake)).coerceIn(0f, 1f) else 0f
            val hue = 120f + 120f * ratio
            return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.85f))
        }
    }

    private fun onHistogramTouch(index: Int) {
        if (index !in trackPoints.indices) return
        val point = trackPoints[index]

        binding.indicatorValues.visibility = View.VISIBLE

        when (currentMode) {
            HistogramView.Mode.LEAN_ANGLE -> {
                binding.tvIndicatorAltitude.text = String.format("Lean: %.1f\u00B0", leanAngles.getOrElse(index) { 0f })
            }
            HistogramView.Mode.FORCE -> {
                binding.tvIndicatorAltitude.text = String.format("Force: %.2f G", forces.getOrElse(index) { 0f })
            }
            else -> {
                binding.tvIndicatorAltitude.text = String.format("Alt: %.0f m", point.ele)
            }
        }
        binding.tvIndicatorSpeed.text = String.format("Speed: %.1f km/h", point.speed * 3.6f)

        trackOverlay?.highlightIndex = index
        binding.mapView.invalidate()
    }

    private fun onHistogramRelease() {
        binding.indicatorValues.visibility = View.GONE
        trackOverlay?.highlightIndex = -1
        binding.mapView.invalidate()
    }

    private fun displayStatistics() {
        val firstPoint = trackPoints.first()
        val lastPoint = trackPoints.last()

        if (firstPoint.timeMs > 0) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault())
            binding.tvDetailDateTime.text = dateFormat.format(Date(firstPoint.timeMs))
        } else {
            binding.tvDetailDateTime.text = getString(R.string.unknown_date)
        }

        var totalDistance = 0.0
        for (i in 1 until trackPoints.size) {
            totalDistance += GpxParser.haversine(
                trackPoints[i - 1].lat, trackPoints[i - 1].lon,
                trackPoints[i].lat, trackPoints[i].lon
            )
        }
        binding.tvDetailLength.text = String.format("%.1f km", totalDistance)

        val totalDurationMs = if (lastPoint.timeMs > firstPoint.timeMs) {
            lastPoint.timeMs - firstPoint.timeMs
        } else {
            0L
        }

        var ridingTimeMs = 0L
        for (i in 1 until trackPoints.size) {
            val segmentMs = trackPoints[i].timeMs - trackPoints[i - 1].timeMs
            if (segmentMs in 1..60000) {
                val avgSpeed = (trackPoints[i].speed + trackPoints[i - 1].speed) / 2f
                if (avgSpeed > MOVING_SPEED_THRESHOLD) {
                    ridingTimeMs += segmentMs
                }
            }
        }
        val pauseTimeMs = (totalDurationMs - ridingTimeMs).coerceAtLeast(0)

        binding.tvDetailRidingTime.text = formatDuration(ridingTimeMs)
        binding.tvDetailPauseTime.text = formatDuration(pauseTimeMs)

        val avgSpeedKmh = if (ridingTimeMs > 0) {
            totalDistance / (ridingTimeMs / 3600000.0)
        } else {
            0.0
        }
        binding.tvDetailAvgSpeed.text = String.format("%.1f km/h", avgSpeedKmh)

        val maxLean = leanAngles.maxOrNull() ?: 0f
        binding.tvDetailMaxLean.text = String.format("%.1f\u00B0", maxLean)

        val maxAccelG = forces.maxOrNull() ?: 0f
        binding.tvDetailMaxAccel.text = String.format("%.2f G", maxAccelG)

        val maxBrakeG = forces.minOrNull() ?: 0f
        binding.tvDetailMaxBrake.text = String.format("%.2f G", abs(maxBrakeG))
    }

    // --- Lean angle computation from GPS trajectory ---

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        return atan2(y, x)
    }

    private fun computeLeanAngles(points: List<GpxParser.TrackPoint>): FloatArray {
        val hasSensorData = points.any { !it.leanAngleDeg.isNaN() }
        if (hasSensorData) {
            return FloatArray(points.size) { i ->
                val lean = points[i].leanAngleDeg
                if (lean.isNaN()) 0f else abs(lean)
            }
        }

        // Fallback: estimate from GPS trajectory
        val result = FloatArray(points.size)
        for (i in 1 until points.size - 1) {
            val prev = points[i - 1]
            val curr = points[i]
            val next = points[i + 1]

            val speed = curr.speed.toDouble()
            if (speed < 2.0) continue

            val bearing1 = bearing(prev.lat, prev.lon, curr.lat, curr.lon)
            val bearing2 = bearing(curr.lat, curr.lon, next.lat, next.lon)

            var dBearing = bearing2 - bearing1
            while (dBearing > Math.PI) dBearing -= 2 * Math.PI
            while (dBearing < -Math.PI) dBearing += 2 * Math.PI

            val absDelta = abs(dBearing)
            if (absDelta < 0.001) continue

            val dist = GpxParser.haversine(prev.lat, prev.lon, curr.lat, curr.lon) * 1000.0
            if (dist < 1.0) continue

            val radius = dist / absDelta
            val lateralAccel = speed * speed / radius
            val leanRad = atan(lateralAccel / GRAVITY)
            result[i] = Math.toDegrees(leanRad).toFloat().coerceAtMost(60f)
        }
        return result
    }

    // --- Longitudinal force computation ---

    private fun computeForces(points: List<GpxParser.TrackPoint>): FloatArray {
        val hasSensorData = points.any { !it.longitudinalAccelMps2.isNaN() }
        if (hasSensorData) {
            return FloatArray(points.size) { i ->
                val accel = points[i].longitudinalAccelMps2
                if (accel.isNaN()) 0f else (accel / GRAVITY.toFloat())
            }
        }

        // Fallback: estimate from speed changes
        val result = FloatArray(points.size)
        for (i in 1 until points.size) {
            val dtMs = points[i].timeMs - points[i - 1].timeMs
            if (dtMs <= 0 || dtMs > 60000) continue

            val dtSec = dtMs / 1000.0
            val dv = (points[i].speed - points[i - 1].speed).toDouble()
            result[i] = (dv / dtSec / GRAVITY).toFloat()
        }
        return result
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0m"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }
}
