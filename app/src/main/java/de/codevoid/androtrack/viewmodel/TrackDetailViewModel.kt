package de.codevoid.androtrack.viewmodel

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.codevoid.androtrack.GpxParser
import de.codevoid.androtrack.HistogramView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class TrackDetailData(
    val trackPoints: List<GpxParser.TrackPoint>,
    val leanAngles: FloatArray,
    val forces: FloatArray,
    val signalValues: FloatArray,
    val totalDistanceKm: Double,
    val ridingTimeMs: Long,
    val pauseTimeMs: Long,
    val avgSpeedKmh: Double,
    val maxLean: Float,
    val maxAccelG: Float,
    val maxBrakeG: Float,
    val hasSignal: Boolean,
    val maxSignal: Int,
    val minSignal: Int
)

sealed class TrackDetailUiState {
    object Loading : TrackDetailUiState()
    data class Ready(val data: TrackDetailData) : TrackDetailUiState()
    object Error : TrackDetailUiState()
}

class TrackDetailViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MOVING_SPEED_THRESHOLD = 0.556f
        private const val GRAVITY = 9.81
    }

    private val _uiState = MutableStateFlow<TrackDetailUiState>(TrackDetailUiState.Loading)
    val uiState: StateFlow<TrackDetailUiState> = _uiState.asStateFlow()

    private val _currentMode = MutableStateFlow(HistogramView.Mode.ALTITUDE)
    val currentMode: StateFlow<HistogramView.Mode> = _currentMode.asStateFlow()

    private val _colors = MutableStateFlow<List<Int>>(emptyList())
    val colors: StateFlow<List<Int>> = _colors.asStateFlow()

    private val _histogramValues = MutableStateFlow(FloatArray(0))
    val histogramValues: StateFlow<FloatArray> = _histogramValues.asStateFlow()

    private val _indicatorIndex = MutableStateFlow(-1)
    val indicatorIndex: StateFlow<Int> = _indicatorIndex.asStateFlow()

    private var detailData: TrackDetailData? = null

    fun load(filePath: String) {
        if (_uiState.value !is TrackDetailUiState.Loading) return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(filePath)
            val points = GpxParser.parseTrackPoints(file)
            if (points == null || points.size < 2) {
                _uiState.value = TrackDetailUiState.Error
                return@launch
            }

            val leanAngles = computeLeanAngles(points)
            val forces = computeForces(points)
            val signalValues = FloatArray(points.size) { i ->
                val dbm = points[i].signalDbm
                if (dbm == Int.MIN_VALUE) Float.NaN else dbm.toFloat()
            }

            var totalDistance = 0.0
            for (i in 1 until points.size) {
                totalDistance += GpxParser.haversine(
                    points[i - 1].lat, points[i - 1].lon,
                    points[i].lat, points[i].lon
                )
            }

            val firstPoint = points.first()
            val lastPoint = points.last()
            val totalDurationMs = if (lastPoint.timeMs > firstPoint.timeMs)
                lastPoint.timeMs - firstPoint.timeMs else 0L

            var ridingTimeMs = 0L
            for (i in 1 until points.size) {
                val segmentMs = points[i].timeMs - points[i - 1].timeMs
                if (segmentMs in 1..60000) {
                    val avgSpeed = (points[i].speed + points[i - 1].speed) / 2f
                    if (avgSpeed > MOVING_SPEED_THRESHOLD) ridingTimeMs += segmentMs
                }
            }
            val pauseTimeMs = (totalDurationMs - ridingTimeMs).coerceAtLeast(0)
            val avgSpeedKmh = if (ridingTimeMs > 0) totalDistance / (ridingTimeMs / 3600000.0) else 0.0

            val validSignals = signalValues.filter { !it.isNaN() }
            val data = TrackDetailData(
                trackPoints = points,
                leanAngles = leanAngles,
                forces = forces,
                signalValues = signalValues,
                totalDistanceKm = totalDistance,
                ridingTimeMs = ridingTimeMs,
                pauseTimeMs = pauseTimeMs,
                avgSpeedKmh = avgSpeedKmh,
                maxLean = leanAngles.maxOrNull() ?: 0f,
                maxAccelG = forces.maxOrNull() ?: 0f,
                maxBrakeG = forces.minOrNull() ?: 0f,
                hasSignal = validSignals.isNotEmpty(),
                maxSignal = if (validSignals.isNotEmpty()) validSignals.max().toInt() else 0,
                minSignal = if (validSignals.isNotEmpty()) validSignals.min().toInt() else 0
            )

            detailData = data
            _uiState.value = TrackDetailUiState.Ready(data)
            switchMode(HistogramView.Mode.ALTITUDE, data)
        }
    }

    fun switchMode(mode: HistogramView.Mode) {
        val data = detailData ?: return
        switchMode(mode, data)
    }

    private fun switchMode(mode: HistogramView.Mode, data: TrackDetailData) {
        _currentMode.value = mode
        _colors.value = calculateColors(mode, data)
        _histogramValues.value = calculateValues(mode, data)
    }

    fun onHistogramTouch(index: Int) {
        _indicatorIndex.value = index
    }

    fun onHistogramRelease() {
        _indicatorIndex.value = -1
    }

    fun getIndicatorText(index: Int): Pair<String, String>? {
        val data = detailData ?: return null
        if (index !in data.trackPoints.indices) return null
        val point = data.trackPoints[index]
        val mode = _currentMode.value
        val primary = when (mode) {
            HistogramView.Mode.LEAN_ANGLE -> String.format("Lean: %.1f°", data.leanAngles.getOrElse(index) { 0f })
            HistogramView.Mode.FORCE -> String.format("Force: %.2f G", data.forces.getOrElse(index) { 0f })
            HistogramView.Mode.SIGNAL -> {
                val dbm = point.signalDbm
                if (dbm == Int.MIN_VALUE) "Signal: N/A" else "Signal: $dbm dBm"
            }
            else -> String.format("Alt: %.0f m", point.ele)
        }
        val secondary = String.format("Speed: %.1f km/h", point.speed * 3.6f)
        return Pair(primary, secondary)
    }

    // --- Color calculations (mirrored from TrackDetailActivity) ---

    private fun calculateColors(mode: HistogramView.Mode, data: TrackDetailData): List<Int> {
        return when (mode) {
            HistogramView.Mode.ALTITUDE -> {
                val startAlt = data.trackPoints.first().ele
                val maxAlt = data.trackPoints.maxOf { it.ele }
                data.trackPoints.map { altitudeToColor(it.ele, startAlt, maxAlt) }
            }
            HistogramView.Mode.SPEED -> data.trackPoints.map { speedToColor(it.speed * 3.6f) }
            HistogramView.Mode.LEAN_ANGLE -> {
                val maxLean = data.leanAngles.maxOrNull()?.coerceAtLeast(5.1f) ?: 5.1f
                data.leanAngles.map { leanAngleToColor(it, maxLean) }
            }
            HistogramView.Mode.FORCE -> {
                val maxAccel = data.forces.filter { it > 0f }.maxOrNull() ?: 0.01f
                val maxBrake = data.forces.filter { it < 0f }.minOrNull() ?: -0.01f
                data.forces.map { forceToColor(it, maxAccel, maxBrake) }
            }
            HistogramView.Mode.SIGNAL -> data.trackPoints.map { signalStrengthToColor(it.signalDbm) }
        }
    }

    private fun calculateValues(mode: HistogramView.Mode, data: TrackDetailData): FloatArray {
        return when (mode) {
            HistogramView.Mode.ALTITUDE -> data.trackPoints.map { it.ele.toFloat() }.toFloatArray()
            HistogramView.Mode.SPEED -> data.trackPoints.map { it.speed * 3.6f }.toFloatArray()
            HistogramView.Mode.LEAN_ANGLE -> data.leanAngles
            HistogramView.Mode.FORCE -> data.forces
            HistogramView.Mode.SIGNAL -> data.signalValues
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

    private fun signalStrengthToColor(dbm: Int): Int {
        if (dbm == Int.MIN_VALUE) return Color.GRAY
        return when {
            dbm >= -40 -> Color.rgb(144, 238, 144)
            dbm >= -80 -> lerpColor(Color.rgb(144, 238, 144), Color.rgb(34, 139, 34), (-40f - dbm) / 40f)
            dbm >= -95 -> lerpColor(Color.rgb(34, 139, 34), Color.rgb(255, 165, 0), (-80f - dbm) / 15f)
            dbm >= -100 -> lerpColor(Color.rgb(255, 165, 0), Color.rgb(220, 20, 60), (-95f - dbm) / 5f)
            else -> Color.rgb(220, 20, 60)
        }
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val tc = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + tc * (Color.red(to) - Color.red(from))).toInt(),
            (Color.green(from) + tc * (Color.green(to) - Color.green(from))).toInt(),
            (Color.blue(from) + tc * (Color.blue(to) - Color.blue(from))).toInt()
        )
    }

    private fun forceToColor(forceG: Float, maxAccel: Float, maxBrake: Float): Int {
        return if (forceG >= 0f) {
            val ratio = if (maxAccel > 0f) (forceG / maxAccel).coerceIn(0f, 1f) else 0f
            Color.HSVToColor(floatArrayOf(120f * (1f - ratio), 0.85f, 0.85f))
        } else {
            val ratio = if (maxBrake < 0f) (abs(forceG) / abs(maxBrake)).coerceIn(0f, 1f) else 0f
            Color.HSVToColor(floatArrayOf(120f + 120f * ratio, 0.85f, 0.85f))
        }
    }

    // --- Lean angle computation ---

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
        val result = FloatArray(points.size)
        for (i in 1 until points.size - 1) {
            val prev = points[i - 1]; val curr = points[i]; val next = points[i + 1]
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

    private fun computeForces(points: List<GpxParser.TrackPoint>): FloatArray {
        val hasSensorData = points.any { !it.longitudinalAccelMps2.isNaN() }
        if (hasSensorData) {
            return FloatArray(points.size) { i ->
                val accel = points[i].longitudinalAccelMps2
                if (accel.isNaN()) 0f else (accel / GRAVITY.toFloat())
            }
        }
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
}
