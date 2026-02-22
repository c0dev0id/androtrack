package com.androtrack

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GpxMerger {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val isoFormatTz = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    data class RawPoint(
        val lat: String,
        val lon: String,
        val time: String,
        val speed: String,
        val ele: String,
        val timeMs: Long
    )

    fun merge(files: List<File>, outputDir: File): File? {
        if (files.isEmpty()) return null

        val fileTracks = files.map { file -> parsePoints(file) }.filter { it.isNotEmpty() }

        if (fileTracks.isEmpty()) return null

        val outName = "merged_${fileNameFormat.format(Date())}.gpx"
        val outFile = File(outputDir, outName)

        outFile.bufferedWriter().use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            writer.newLine()
            writer.write("""<gpx version="1.1" creator="AndroTrack" xmlns="http://www.topografix.com/GPX/1/1">""")
            writer.newLine()

            for ((index, points) in fileTracks.withIndex()) {
                writer.write("  <trk>")
                writer.newLine()
                writer.write("    <name>Track ${index + 1}</name>")
                writer.newLine()
                writer.write("    <trkseg>")
                writer.newLine()

                for (pt in points) {
                    writer.write("""      <trkpt lat="${pt.lat}" lon="${pt.lon}">""")
                    writer.newLine()
                    if (pt.ele.isNotEmpty()) {
                        writer.write("        <ele>${pt.ele}</ele>")
                        writer.newLine()
                    }
                    if (pt.time.isNotEmpty()) {
                        writer.write("        <time>${pt.time}</time>")
                        writer.newLine()
                    }
                    if (pt.speed.isNotEmpty()) {
                        writer.write("        <speed>${pt.speed}</speed>")
                        writer.newLine()
                    }
                    writer.write("      </trkpt>")
                    writer.newLine()
                }

                writer.write("    </trkseg>")
                writer.newLine()
                writer.write("  </trk>")
                writer.newLine()
            }

            writer.write("</gpx>")
            writer.newLine()
        }

        return outFile
    }

    private fun parsePoints(file: File): List<RawPoint> {
        val points = mutableListOf<RawPoint>()
        try {
            FileInputStream(file).use { fis ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(fis, null)

                var inTrkpt = false
                var lat = ""
                var lon = ""
                var time = ""
                var speed = ""
                var ele = ""
                var currentTag = ""

                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name
                            if (currentTag == "trkpt") {
                                inTrkpt = true
                                lat = parser.getAttributeValue(null, "lat") ?: ""
                                lon = parser.getAttributeValue(null, "lon") ?: ""
                                time = ""; speed = ""; ele = ""
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inTrkpt) {
                                val text = parser.text.trim()
                                when (currentTag) {
                                    "time" -> time = text
                                    "speed" -> speed = text
                                    "ele" -> ele = text
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "trkpt" && inTrkpt) {
                                inTrkpt = false
                                if (lat.isNotEmpty() && lon.isNotEmpty()) {
                                    val timeMs = parseTimeMs(time)
                                    points.add(RawPoint(lat, lon, time, speed, ele, timeMs))
                                }
                            }
                            if (parser.name == currentTag) currentTag = ""
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            // skip malformed file
        }
        return points
    }

    private fun parseTimeMs(text: String): Long {
        if (text.isEmpty()) return 0L
        return try {
            isoFormatTz.parse(text)?.time ?: (isoFormat.parse(text)?.time ?: 0L)
        } catch (e: Exception) {
            try { isoFormat.parse(text)?.time ?: 0L } catch (e2: Exception) { 0L }
        }
    }
}
