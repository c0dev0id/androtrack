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

class TrackDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        private const val MOVING_SPEED_THRESHOLD = 0.556f // ~2 km/h in m/s
    }

    private lateinit var binding: ActivityTrackDetailBinding
    private var trackPoints: List<GpxParser.TrackPoint> = emptyList()
    private var geoPoints: List<GeoPoint> = emptyList()
    private var trackOverlay: GradientTrackOverlay? = null
    private var currentMode = HistogramView.Mode.ALTITUDE

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
        }
    }

    private fun calculateValues(mode: HistogramView.Mode): FloatArray {
        return when (mode) {
            HistogramView.Mode.ALTITUDE -> trackPoints.map { it.ele.toFloat() }.toFloatArray()
            HistogramView.Mode.SPEED -> trackPoints.map { it.speed * 3.6f }.toFloatArray()
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

    private fun onHistogramTouch(index: Int) {
        if (index !in trackPoints.indices) return
        val point = trackPoints[index]

        binding.indicatorValues.visibility = View.VISIBLE
        binding.tvIndicatorAltitude.text = String.format("Alt: %.0f m", point.ele)
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
