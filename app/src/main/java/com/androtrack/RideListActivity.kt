package com.androtrack

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.androtrack.databinding.ActivityRideListBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RideListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRideListBinding
    private lateinit var adapter: RideAdapter
    private var actionMode: ActionMode? = null
    private var isTracking = false

    private val trackingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TrackingService.ACTION_TRACKING_STARTED -> {
                    isTracking = true
                    updateFab()
                    binding.statsCard.visibility = View.VISIBLE
                }
                TrackingService.ACTION_TRACKING_STOPPED -> {
                    isTracking = false
                    updateFab()
                    binding.statsCard.visibility = View.GONE
                    loadRides()
                }
                TrackingService.ACTION_STATS_UPDATE -> {
                    updateStatsPanel(intent)
                }
            }
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_download_zip -> {
                    downloadSelectedAsZip()
                    true
                }
                R.id.action_merge -> {
                    mergeSelected()
                    true
                }
                R.id.action_select_all -> {
                    adapter.selectAll()
                    mode.title = "${adapter.getSelectedCount()} selected"
                    true
                }
                R.id.action_delete -> {
                    deleteSelected()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter.clearSelection()
            actionMode = null
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRideListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = RideAdapter(
            mutableListOf(),
            onItemClick = { position ->
                if (adapter.selectionMode) {
                    adapter.toggleSelection(position)
                    val count = adapter.getSelectedCount()
                    if (count == 0) {
                        adapter.selectionMode = false
                        actionMode?.finish()
                    } else {
                        actionMode?.title = "$count selected"
                    }
                }
            },
            onItemLongClick = { position, anchorView ->
                if (adapter.selectionMode) {
                    adapter.toggleSelection(position)
                    val count = adapter.getSelectedCount()
                    actionMode?.title = "$count selected"
                } else {
                    showItemContextMenu(position, anchorView)
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fab.setOnClickListener { toggleTracking() }

        checkAndRequestPermissions()
        loadRides()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(TrackingService.ACTION_TRACKING_STARTED)
            addAction(TrackingService.ACTION_TRACKING_STOPPED)
            addAction(TrackingService.ACTION_STATS_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackingReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackingReceiver, filter)
        }
        isTracking = TrackingService.isRunning
        updateFab()
        if (isTracking) {
            binding.statsCard.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(trackingReceiver) } catch (e: Exception) { /* ignore */ }
    }

    private fun checkAndRequestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Some permissions denied. Tracking may not work.", Toast.LENGTH_LONG).show()
            }
            // Request background location separately (requires foreground location first)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQUEST_PERMISSIONS + 1
                )
            }
        }
    }

    private fun toggleTracking() {
        if (isTracking) {
            stopTrackingService()
        } else {
            startTrackingService()
        }
    }

    private fun startTrackingService() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        isTracking = true
        updateFab()
        binding.statsCard.visibility = View.VISIBLE
    }

    private fun stopTrackingService() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(intent)
        isTracking = false
        updateFab()
        binding.statsCard.visibility = View.GONE
    }

    private fun updateFab() {
        binding.fab.setImageResource(
            if (isTracking) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_menu_mylocation
        )
        binding.fab.contentDescription = if (isTracking) "Stop tracking" else "Start tracking"
    }

    private fun updateStatsPanel(intent: Intent) {
        val distanceM = intent.getDoubleExtra(TrackingService.EXTRA_DISTANCE_M, 0.0)
        val durationMs = intent.getLongExtra(TrackingService.EXTRA_DURATION_MS, 0L)
        val recording = intent.getBooleanExtra(TrackingService.EXTRA_IS_RECORDING, false)
        val fileName = intent.getStringExtra(TrackingService.EXTRA_FILE_NAME) ?: ""
        val pauseTimeoutMs = intent.getLongExtra(TrackingService.EXTRA_PAUSE_TIMEOUT_MS, 0L)
        val curAccuracy = intent.getFloatExtra(TrackingService.EXTRA_CURRENT_ACCURACY, 0f)
        val avgAccuracy = intent.getFloatExtra(TrackingService.EXTRA_AVG_ACCURACY, 0f)
        val curRate = intent.getFloatExtra(TrackingService.EXTRA_CURRENT_UPDATE_RATE, 0f)
        val avgRate = intent.getFloatExtra(TrackingService.EXTRA_AVG_UPDATE_RATE, 0f)

        binding.statsCard.visibility = View.VISIBLE

        binding.tvStatsStatus.text = if (recording)
            getString(R.string.status_recording)
        else
            getString(R.string.status_paused)

        binding.tvStatsFileName.text = "File: $fileName"
        binding.tvStatsDistance.text = String.format("Distance: %.2f km", distanceM / 1000.0)
        binding.tvStatsDuration.text = "Time: ${formatDuration(durationMs)}"

        if (!recording && pauseTimeoutMs > 0) {
            binding.tvStatsPauseTimeout.visibility = View.VISIBLE
            binding.tvStatsPauseTimeout.text = "New file in: ${formatDuration(pauseTimeoutMs)}"
        } else {
            binding.tvStatsPauseTimeout.visibility = View.GONE
        }

        binding.tvStatsAccuracy.text = String.format("Accuracy: %.1fm", curAccuracy)
        binding.tvStatsAvgAccuracy.text = String.format("Avg: %.1fm", avgAccuracy)
        binding.tvStatsUpdateRate.text = String.format("Rate: %.1f Hz", curRate)
        binding.tvStatsAvgUpdateRate.text = String.format("Avg: %.1f Hz", avgRate)
    }

    private fun loadRides() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val gpxFiles = dir.listFiles { f -> f.name.endsWith(".gpx") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        val rides = gpxFiles.mapNotNull { GpxParser.parse(it) }
            .sortedByDescending { it.date + it.startTime }

        adapter.updateItems(rides)

        binding.emptyView.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (rides.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showItemContextMenu(position: Int, anchor: View) {
        val item = adapter.getItemAt(position) ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.select))
        popup.menu.add(0, 2, 1, getString(R.string.share))
        popup.menu.add(0, 3, 2, getString(R.string.rename))
        popup.menu.add(0, 4, 3, getString(R.string.delete))
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    adapter.selectionMode = true
                    actionMode = startActionMode(actionModeCallback)
                    adapter.toggleSelection(position)
                    actionMode?.title = "${adapter.getSelectedCount()} selected"
                    true
                }
                2 -> {
                    shareTrack(item)
                    true
                }
                3 -> {
                    showRenameDialog(item)
                    true
                }
                4 -> {
                    showDeleteDialog(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun shareTrack(item: RideItem) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                item.file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteDialog(item: RideItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_confirm_message, item.file.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                if (item.file.delete()) {
                    Toast.makeText(this, getString(R.string.track_deleted), Toast.LENGTH_SHORT).show()
                    loadRides()
                } else {
                    Toast.makeText(this, getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRenameDialog(item: RideItem) {
        val currentName = item.file.nameWithoutExtension
        val editText = EditText(this).apply {
            setText(currentName)
            selectAll()
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_track))
            .setView(editText)
            .setPositiveButton(getString(R.string.rename)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentName) {
                    val newFile = File(item.file.parent, "$newName.gpx")
                    if (item.file.renameTo(newFile)) {
                        Toast.makeText(this, getString(R.string.track_renamed), Toast.LENGTH_SHORT).show()
                        loadRides()
                    } else {
                        Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteSelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_rides_selected), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_tracks_message, selected.size))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                var deleted = 0
                for (ride in selected) {
                    if (ride.file.delete()) deleted++
                }
                Toast.makeText(this, getString(R.string.tracks_deleted, deleted), Toast.LENGTH_SHORT).show()
                actionMode?.finish()
                loadRides()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun downloadSelectedAsZip() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_rides_selected), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()

            if (selected.size == 1) {
                val srcFile = selected.first().file
                val destFile = File(downloadsDir, srcFile.name)
                srcFile.copyTo(destFile, overwrite = true)
                Toast.makeText(this, "Saved to Downloads/${srcFile.name}", Toast.LENGTH_LONG).show()
            } else {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val zipName = "androtrack_$timestamp.zip"
                val zipFile = File(downloadsDir, zipName)

                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    for (ride in selected) {
                        zos.putNextEntry(ZipEntry(ride.file.name))
                        FileInputStream(ride.file).use { fis -> fis.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                Toast.makeText(this, "Saved to Downloads/$zipName", Toast.LENGTH_LONG).show()
            }
            actionMode?.finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun mergeSelected() {
        val selected = adapter.getSelectedItems()
        if (selected.size < 2) {
            Toast.makeText(this, "Select at least 2 rides to merge", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val outputDir = getExternalFilesDir(null) ?: filesDir
            val merged = GpxMerger.merge(selected.map { it.file }, outputDir)
            if (merged != null) {
                // Delete source track files after successful merge
                for (ride in selected) {
                    ride.file.delete()
                }
                Toast.makeText(this, "Merged: ${merged.name}", Toast.LENGTH_LONG).show()
                actionMode?.finish()
                loadRides()
            } else {
                Toast.makeText(this, "Merge failed: no valid track points", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Merge failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                loadRides()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
