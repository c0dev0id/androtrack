package de.codevoid.androtrack

import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object IncrementManager {

    private const val INCREMENT_DIR_NAME = "increments"
    private const val FILE_EXTENSION = ".inc"

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun getIncrementDir(storageDir: File): File {
        val dir = File(storageDir, INCREMENT_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Writes a batch of track points to a new increment file.
     * Uses write-to-temp-then-rename for atomicity.
     */
    fun writeIncrement(
        storageDir: File,
        sessionTimestamp: String,
        sequenceNumber: Int,
        points: List<TrackingService.GpxTrackPoint>
    ): Boolean {
        if (points.isEmpty()) return true

        val incDir = getIncrementDir(storageDir)
        val seqStr = String.format("%04d", sequenceNumber)
        val finalName = "${sessionTimestamp}_${seqStr}${FILE_EXTENSION}"
        val finalFile = File(incDir, finalName)
        val tempFile = File(incDir, "${finalName}.tmp")

        return try {
            FileWriter(tempFile).use { writer ->
                for (pt in points) {
                    val leanStr = if (pt.leanAngleDeg.isNaN()) "" else "%.2f".format(pt.leanAngleDeg)
                    val accelStr = if (pt.longitudinalAccelMps2.isNaN()) "" else "%.3f".format(pt.longitudinalAccelMps2)
                    writer.write("${pt.lat},${pt.lon},${pt.speed},${pt.timeMs},${pt.ele},$leanStr,$accelStr\n")
                }
                writer.flush()
            }
            tempFile.renameTo(finalFile)
        } catch (e: Exception) {
            tempFile.delete()
            false
        }
    }

    /**
     * Finds all orphaned increment sessions (grouped by session timestamp).
     */
    fun findOrphanedSessions(storageDir: File): Map<String, List<File>> {
        val incDir = File(storageDir, INCREMENT_DIR_NAME)
        if (!incDir.exists()) return emptyMap()
        val incFiles = incDir.listFiles { f -> f.name.endsWith(FILE_EXTENSION) }
            ?: return emptyMap()
        if (incFiles.isEmpty()) return emptyMap()

        return incFiles.groupBy { extractSessionTimestamp(it.name) }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, files) -> files.sortedBy { it.name } }
    }

    /**
     * Reads track points from a list of increment files in order.
     * Skips corrupted files/lines gracefully.
     */
    fun readPointsFromIncrements(files: List<File>): List<TrackingService.GpxTrackPoint> {
        val points = mutableListOf<TrackingService.GpxTrackPoint>()
        for (file in files) {
            try {
                BufferedReader(FileReader(file)).use { reader ->
                    reader.forEachLine { line ->
                        val parts = line.split(",")
                        if (parts.size >= 5) {
                            val lean = if (parts.size > 5) parts[5].toFloatOrNull() ?: Float.NaN else Float.NaN
                            val accel = if (parts.size > 6) parts[6].toFloatOrNull() ?: Float.NaN else Float.NaN
                            points.add(TrackingService.GpxTrackPoint(
                                lat = parts[0].toDoubleOrNull() ?: return@forEachLine,
                                lon = parts[1].toDoubleOrNull() ?: return@forEachLine,
                                speed = parts[2].toFloatOrNull() ?: 0f,
                                timeMs = parts[3].toLongOrNull() ?: return@forEachLine,
                                ele = parts[4].toDoubleOrNull() ?: 0.0,
                                leanAngleDeg = lean,
                                longitudinalAccelMps2 = accel
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip corrupted file; other files still recover
            }
        }
        return points
    }

    /**
     * Merges increment files into a final GPX file.
     */
    fun mergeIncrementsToGpx(
        storageDir: File,
        sessionTimestamp: String,
        files: List<File>
    ): File? {
        val points = readPointsFromIncrements(files)
        if (points.isEmpty()) return null

        val gpxFile = File(storageDir, "track_${sessionTimestamp}.gpx")
        return writeGpxFile(gpxFile, points)
    }

    /**
     * Writes a list of track points as a complete GPX file.
     */
    fun writeGpxFile(file: File, points: List<TrackingService.GpxTrackPoint>): File? {
        if (points.isEmpty()) return null
        return try {
            FileWriter(file).use { writer ->
                writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
                writer.write("\n")
                writer.write("""<gpx version="1.1" creator="AndroTrack" xmlns="http://www.topografix.com/GPX/1/1" xmlns:androtrack="http://androtrack.codevoid.de/gpx/1">""")
                writer.write("\n")
                writer.write("  <trk>\n")
                val nameTimestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                    .format(Date(points.first().timeMs))
                writer.write("    <name>Track $nameTimestamp</name>\n")
                writer.write("    <trkseg>\n")
                for (pt in points) {
                    writer.write("""      <trkpt lat="${pt.lat}" lon="${pt.lon}">""")
                    writer.write("\n")
                    writer.write("        <ele>${pt.ele}</ele>\n")
                    writer.write("        <time>${isoFormat.format(Date(pt.timeMs))}</time>\n")
                    writer.write("        <speed>${pt.speed}</speed>\n")
                    if (!pt.leanAngleDeg.isNaN() || !pt.longitudinalAccelMps2.isNaN()) {
                        writer.write("        <extensions>\n")
                        if (!pt.leanAngleDeg.isNaN()) {
                            writer.write("          <androtrack:lean>%.2f</androtrack:lean>\n".format(pt.leanAngleDeg))
                        }
                        if (!pt.longitudinalAccelMps2.isNaN()) {
                            writer.write("          <androtrack:accel>%.3f</androtrack:accel>\n".format(pt.longitudinalAccelMps2))
                        }
                        writer.write("        </extensions>\n")
                    }
                    writer.write("      </trkpt>\n")
                }
                writer.write("    </trkseg>\n")
                writer.write("  </trk>\n")
                writer.write("</gpx>\n")
            }
            file
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /**
     * Deletes all increment files for a session (including stale .tmp files).
     */
    fun deleteSessionIncrements(storageDir: File, sessionTimestamp: String) {
        val incDir = File(storageDir, INCREMENT_DIR_NAME)
        if (!incDir.exists()) return
        incDir.listFiles { f ->
            f.name.startsWith(sessionTimestamp) &&
                (f.name.endsWith(FILE_EXTENSION) || f.name.endsWith(".tmp"))
        }?.forEach { it.delete() }
        // Remove directory if empty
        if (incDir.listFiles()?.isEmpty() == true) incDir.delete()
    }

    /**
     * Extracts session timestamp from an increment filename.
     * e.g., "2024-03-01_14-30-00_0001.inc" -> "2024-03-01_14-30-00"
     */
    private fun extractSessionTimestamp(fileName: String): String {
        val withoutExt = fileName.removeSuffix(FILE_EXTENSION)
        val lastUnderscore = withoutExt.lastIndexOf('_')
        return if (lastUnderscore > 0) withoutExt.substring(0, lastUnderscore) else ""
    }
}
