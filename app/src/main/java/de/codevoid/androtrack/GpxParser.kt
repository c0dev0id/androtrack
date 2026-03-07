package de.codevoid.androtrack

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.*

object GpxParser {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val isoFormatTz = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    data class TrackPoint(
        val lat: Double,
        val lon: Double,
        val timeMs: Long,
        val speed: Float,
        val ele: Double = 0.0,
        val leanAngleDeg: Float = Float.NaN,
        val longitudinalAccelMps2: Float = Float.NaN,
        val signalDbm: Int = Int.MIN_VALUE
    )

    fun parse(file: File): RideItem? {
        return try {
            val segments = parseSegmentsInternal(file)
            val allPoints = segments.flatten()
            if (allPoints.isEmpty()) return null

            val startMs = allPoints.first().timeMs

            // Sum per-segment durations to exclude gaps between merged tracks
            val durationMs = segments.sumOf { seg ->
                if (seg.size < 2) 0L
                else {
                    val first = seg.first().timeMs
                    val last = seg.last().timeMs
                    if (last > first) last - first else 0L
                }
            }

            // Sum per-segment distances to avoid counting jumps between merged tracks
            val distanceKm = segments.sumOf { calculateDistance(it) }

            val startDate = if (startMs > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                sdf.format(java.util.Date(startMs))
            } else {
                extractDateFromFilename(file.name)
            }

            val startTime = if (startMs > 0) {
                val sdf = SimpleDateFormat("HH:mm", Locale.US)
                sdf.format(java.util.Date(startMs))
            } else {
                extractTimeFromFilename(file.name)
            }

            val trackPairs = allPoints.map { Pair(it.lat, it.lon) }

            RideItem(
                file = file,
                date = startDate,
                startTime = startTime,
                durationMs = durationMs,
                distanceKm = distanceKm,
                trackPoints = trackPairs
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseTrackPoints(file: File): List<TrackPoint>? {
        return try {
            val points = parseSegmentsInternal(file).flatten()
            if (points.isEmpty()) null else points
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSegmentsInternal(file: File): List<List<TrackPoint>> {
        val segments = mutableListOf<MutableList<TrackPoint>>()
        var currentSegment: MutableList<TrackPoint>? = null
        val orphanPoints = mutableListOf<TrackPoint>()
        FileInputStream(file).use { fis ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(fis, null)
            var lat = 0.0
            var lon = 0.0
            var timeMs = 0L
            var speed = 0f
            var ele = 0.0
            var lean = Float.NaN
            var accel = Float.NaN
            var signal = Int.MIN_VALUE
            var inTrkpt = false
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        when (currentTag) {
                            "trk" -> currentSegment = mutableListOf()
                            "trkpt" -> {
                                inTrkpt = true
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                timeMs = 0L
                                speed = 0f
                                ele = 0.0
                                lean = Float.NaN
                                accel = Float.NaN
                                signal = Int.MIN_VALUE
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inTrkpt) {
                            val text = parser.text.trim()
                            when (currentTag) {
                                "time" -> timeMs = parseTime(text)
                                "speed" -> speed = text.toFloatOrNull() ?: 0f
                                "ele" -> ele = text.toDoubleOrNull() ?: 0.0
                                "androtrack:lean" -> lean = text.toFloatOrNull() ?: Float.NaN
                                "androtrack:accel" -> accel = text.toFloatOrNull() ?: Float.NaN
                                "androtrack:signal" -> signal = text.toIntOrNull() ?: Int.MIN_VALUE
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "trk" -> {
                                currentSegment?.let { if (it.isNotEmpty()) segments.add(it) }
                                currentSegment = null
                            }
                            "trkpt" -> {
                                if (inTrkpt) {
                                    inTrkpt = false
                                    if (lat != 0.0 || lon != 0.0) {
                                        val pt = TrackPoint(lat, lon, timeMs, speed, ele, lean, accel, signal)
                                        currentSegment?.add(pt) ?: orphanPoints.add(pt)
                                    }
                                }
                            }
                        }
                        if (parser.name == currentTag) {
                            currentTag = ""
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        if (orphanPoints.isNotEmpty()) segments.add(0, orphanPoints)
        return segments
    }

    private fun parseTime(text: String): Long {
        return try {
            isoFormatTz.parse(text)?.time ?: (isoFormat.parse(text)?.time ?: 0L)
        } catch (e: Exception) {
            try { isoFormat.parse(text)?.time ?: 0L } catch (e2: Exception) { 0L }
        }
    }

    private fun calculateDistance(points: List<TrackPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversine(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        return total
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun extractDateFromFilename(name: String): String {
        val regex = Regex("""(\d{4}-\d{2}-\d{2})""")
        return regex.find(name)?.groupValues?.get(1) ?: "Unknown"
    }

    private fun extractTimeFromFilename(name: String): String {
        val regex = Regex("""_(\d{2})-(\d{2})-(\d{2})\.gpx$""")
        val m = regex.find(name) ?: return "00:00"
        return "${m.groupValues[1]}:${m.groupValues[2]}"
    }
}
