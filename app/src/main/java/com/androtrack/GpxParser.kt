package com.androtrack

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

    data class TrackPoint(val lat: Double, val lon: Double, val timeMs: Long, val speed: Float)

    fun parse(file: File): RideItem? {
        return try {
            val points = mutableListOf<TrackPoint>()
            FileInputStream(file).use { fis ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(fis, null)
                var lat = 0.0
                var lon = 0.0
                var timeMs = 0L
                var speed = 0f
                var inTrkpt = false
                var currentTag = ""

                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name
                            if (currentTag == "trkpt") {
                                inTrkpt = true
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                timeMs = 0L
                                speed = 0f
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inTrkpt) {
                                val text = parser.text.trim()
                                when (currentTag) {
                                    "time" -> timeMs = parseTime(text)
                                    "speed" -> speed = text.toFloatOrNull() ?: 0f
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "trkpt" && inTrkpt) {
                                inTrkpt = false
                                if (lat != 0.0 || lon != 0.0) {
                                    points.add(TrackPoint(lat, lon, timeMs, speed))
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

            if (points.isEmpty()) return null

            val startMs = points.first().timeMs
            val endMs = points.last().timeMs
            val durationMs = if (endMs > startMs) endMs - startMs else 0L

            val distanceKm = calculateDistance(points)

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

            val trackPairs = points.map { Pair(it.lat, it.lon) }

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

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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
