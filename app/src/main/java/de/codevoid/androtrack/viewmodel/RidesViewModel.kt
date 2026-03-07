package de.codevoid.androtrack.viewmodel

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.androtrack.GpxMerger
import de.codevoid.androtrack.GpxParser
import de.codevoid.androtrack.IncrementManager
import de.codevoid.androtrack.RideItem
import de.codevoid.androtrack.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class RidesUiState {
    object Loading : RidesUiState()
    data class Success(val rides: List<RideItem>) : RidesUiState()
}

class RidesViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<RidesUiState>(RidesUiState.Loading)
    val uiState: StateFlow<RidesUiState> = _uiState.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    init {
        recoverOrphansAndLoad()
    }

    private fun getDir(): File {
        val ctx = getApplication<Application>()
        return ctx.getExternalFilesDir(null) ?: ctx.filesDir
    }

    fun loadRides() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = getDir()
            val gpxFiles = dir.listFiles { f -> f.name.endsWith(".gpx") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            val rides = gpxFiles.mapNotNull { GpxParser.parse(it) }
                .sortedByDescending { it.date + it.startTime }
            _uiState.value = RidesUiState.Success(rides)
        }
    }

    private fun recoverOrphansAndLoad() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!TrackingService.isRunning) {
                val dir = getDir()
                val sessions = IncrementManager.findOrphanedSessions(dir)
                var recoveredCount = 0
                for ((sessionTimestamp, files) in sessions) {
                    val gpxFile = IncrementManager.mergeIncrementsToGpx(dir, sessionTimestamp, files)
                    if (gpxFile != null) {
                        IncrementManager.deleteSessionIncrements(dir, sessionTimestamp)
                        recoveredCount++
                    }
                }
                if (recoveredCount > 0) {
                    _toastMessage.value = "Recovered $recoveredCount interrupted recording(s)"
                }
            }
            loadRides()
        }
    }

    fun deleteRide(item: RideItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (item.file.delete()) {
                _toastMessage.value = "Track deleted"
            } else {
                _toastMessage.value = "Delete failed"
            }
            loadRides()
        }
    }

    fun renameRide(item: RideItem, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentName = item.file.nameWithoutExtension
            if (newName.isNotEmpty() && newName != currentName) {
                val newFile = File(item.file.parent, "$newName.gpx")
                if (item.file.renameTo(newFile)) {
                    _toastMessage.value = "Track renamed"
                } else {
                    _toastMessage.value = "Rename failed"
                }
                loadRides()
            }
        }
    }

    fun deleteRides(items: List<RideItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            var deleted = 0
            for (ride in items) {
                if (ride.file.delete()) deleted++
            }
            _toastMessage.value = "Deleted $deleted track(s)"
            loadRides()
        }
    }

    fun mergeRides(items: List<RideItem>) {
        if (items.size < 2) {
            _toastMessage.value = "Select at least 2 rides to merge"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val sorted = items.sortedWith(compareBy({ it.date }, { it.startTime }))
            val outputDir = getDir()
            val merged = GpxMerger.merge(sorted.map { it.file }, outputDir)
            if (merged != null) {
                for (ride in sorted) ride.file.delete()
                _toastMessage.value = "Merged: ${merged.name}"
            } else {
                _toastMessage.value = "Merge failed: no valid track points"
            }
            loadRides()
        }
    }

    fun downloadZip(items: List<RideItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                if (items.size == 1) {
                    val srcFile = items.first().file
                    val destFile = File(downloadsDir, srcFile.name)
                    srcFile.copyTo(destFile, overwrite = true)
                    _toastMessage.value = "Saved to Downloads/${srcFile.name}"
                } else {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                    val zipName = "androtrack_$timestamp.zip"
                    val zipFile = File(downloadsDir, zipName)
                    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                        for (ride in items) {
                            zos.putNextEntry(ZipEntry(ride.file.name))
                            FileInputStream(ride.file).use { fis -> fis.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    _toastMessage.value = "Saved to Downloads/$zipName"
                }
            } catch (e: Exception) {
                _toastMessage.value = "Download failed: ${e.message}"
            }
        }
    }

    fun getShareUri(context: Context, item: RideItem) = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
    } catch (_: Exception) {
        null
    }

    fun clearToast() {
        _toastMessage.value = null
    }
}
