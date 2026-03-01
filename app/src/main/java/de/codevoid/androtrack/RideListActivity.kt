package de.codevoid.androtrack

import android.Manifest
import android.content.BroadcastReceiver
import android.net.Uri
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import de.codevoid.androtrack.databinding.ActivityRideListBinding
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

    /**
     * Receives three distinct tracking lifecycle broadcasts:
     * - ACTION_TRACKING_STARTED: GPS recording began (new file opened). Show the card.
     * - ACTION_TRACKING_PAUSED: No-motion timeout fired; GPS stopped, file finalised,
     *   but the service is still alive waiting for motion. Keep the card visible so
     *   the user can see the paused state; reload the ride list because a new GPX file
     *   was just written.  The statsUpdater continues broadcasting ACTION_STATS_UPDATE
     *   every second so the card keeps updating.
     * - ACTION_TRACKING_STOPPED: User pressed Stop (full stop). Hide the card and
     *   reload the ride list.
     */
    private val trackingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TrackingService.ACTION_TRACKING_STARTED -> {
                    isTracking = true
                    updateFab()
                    binding.statsCard.visibility = View.VISIBLE
                }
                TrackingService.ACTION_TRACKING_PAUSED -> {
                    isTracking = false
                    updateFab()
                    // Card stays visible — service is still running, statsUpdater keeps ticking
                    loadRides()
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
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
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
                } else {
                    val item = adapter.getItemAt(position)
                    if (item != null) {
                        val intent = Intent(this, TrackDetailActivity::class.java)
                        intent.putExtra(TrackDetailActivity.EXTRA_FILE_PATH, item.file.absolutePath)
                        startActivity(intent)
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
        checkForOrphanedIncrements()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(TrackingService.ACTION_TRACKING_STARTED)
            addAction(TrackingService.ACTION_TRACKING_PAUSED)
            addAction(TrackingService.ACTION_TRACKING_STOPPED)
            addAction(TrackingService.ACTION_STATS_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackingReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackingReceiver, filter)
        }
        isTracking = TrackingService.isRunning
        if (isTracking &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            stopTrackingService()
        }
        updateFab()
        if (isTracking) {
            binding.statsCard.visibility = View.VISIBLE
        }
        loadRides()
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, getString(R.string.location_permission_required), Toast.LENGTH_LONG).show()
            checkAndRequestPermissions()
            return
        }
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
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

    /**
     * Updates all fields of the stats card from an ACTION_STATS_UPDATE broadcast.
     *
     * Three always-visible rows show the power/pause state at a glance:
     * - tvStatsAutoPause: power connection status and session-finalize countdown when paused.
     * - tvStatsLastMotion: how long the service has been paused on battery.
     * - tvStatsReason: what will trigger the next state change.
     */
    private fun updateStatsPanel(intent: Intent) {
        val distanceM = intent.getDoubleExtra(TrackingService.EXTRA_DISTANCE_M, 0.0)
        val durationMs = intent.getLongExtra(TrackingService.EXTRA_DURATION_MS, 0L)
        val recording = intent.getBooleanExtra(TrackingService.EXTRA_IS_RECORDING, false)
        val fileName = intent.getStringExtra(TrackingService.EXTRA_FILE_NAME) ?: ""
        val pauseTimeoutMs = intent.getLongExtra(TrackingService.EXTRA_PAUSE_TIMEOUT_MS, 0L)
        val pausedForMs = intent.getLongExtra(TrackingService.EXTRA_PAUSED_FOR_MS, 0L)
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

        // Power row: connection status and session-finalize countdown when paused
        val emulatePower = getSharedPreferences("androtrack_settings", Context.MODE_PRIVATE)
            .getBoolean("pref_emulate_power", false)
        binding.tvStatsAutoPause.text = if (emulatePower) {
            "Power: emulated (always on)"
        } else if (recording) {
            "Power: connected"
        } else if (pauseTimeoutMs > 0) {
            "Power: disconnected – same file if reconnected within ${formatDuration(pauseTimeoutMs)}"
        } else {
            "Power: disconnected – next ride starts new file"
        }

        // Pause duration row
        binding.tvStatsLastMotion.text = if (recording) {
            if (emulatePower) "Pauses: manual stop only" else "Pauses: when charger disconnects"
        } else {
            "Paused for: ${formatDuration(pausedForMs)}"
        }

        // Reason row
        binding.tvStatsReason.text = if (recording) {
            if (emulatePower) "Recording – power emulated" else "Recording – charger connected"
        } else {
            if (emulatePower) "Resumes: automatically" else "Resumes: when charger connected"
        }

        binding.tvStatsAccuracy.text = String.format("Accuracy: %.1fm", curAccuracy)
        binding.tvStatsAvgAccuracy.text = String.format("Avg: %.1fm", avgAccuracy)
        binding.tvStatsUpdateRate.text = String.format("Rate: %.1f Hz", curRate)
        binding.tvStatsAvgUpdateRate.text = String.format("Avg: %.1f Hz", avgRate)
    }

    /**
     * Scans the app's external files directory for *.gpx files and refreshes the list.
     * There is no file-system watcher; this is called explicitly on:
     * - ACTION_TRACKING_PAUSED (a new GPX file was just finalised),
     * - ACTION_TRACKING_STOPPED (full stop),
     * - the manual Refresh menu action.
     */
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

    private fun checkForOrphanedIncrements() {
        if (TrackingService.isRunning) return
        val dir = getExternalFilesDir(null) ?: filesDir
        val sessions = IncrementManager.findOrphanedSessions(dir)
        if (sessions.isEmpty()) return

        var recoveredCount = 0
        for ((sessionTimestamp, files) in sessions) {
            val gpxFile = IncrementManager.mergeIncrementsToGpx(dir, sessionTimestamp, files)
            if (gpxFile != null) {
                IncrementManager.deleteSessionIncrements(dir, sessionTimestamp)
                recoveredCount++
            }
        }

        if (recoveredCount > 0) {
            loadRides()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.recording_recovered_title))
                .setMessage(getString(R.string.recording_recovered_message, recoveredCount))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val prefs = getSharedPreferences("androtrack_settings", Context.MODE_PRIVATE)

        val switchEmulatePower = dialogView.findViewById<SwitchCompat>(R.id.switchEmulatePower)
        val switchSensorRecording = dialogView.findViewById<SwitchCompat>(R.id.switchSensorRecording)
        val seekUpdateInterval = dialogView.findViewById<SeekBar>(R.id.seekUpdateInterval)
        val tvUpdateIntervalValue = dialogView.findViewById<TextView>(R.id.tvUpdateIntervalValue)
        val seekMinDistance = dialogView.findViewById<SeekBar>(R.id.seekMinDistance)
        val tvMinDistanceValue = dialogView.findViewById<TextView>(R.id.tvMinDistanceValue)
        val seekMaxAccuracy = dialogView.findViewById<SeekBar>(R.id.seekMaxAccuracy)
        val tvMaxAccuracyValue = dialogView.findViewById<TextView>(R.id.tvMaxAccuracyValue)

        // Load current values
        switchEmulatePower.isChecked = prefs.getBoolean("pref_emulate_power", false)
        switchSensorRecording.isChecked = prefs.getBoolean("pref_sensor_recording", true)
        val currentIntervalSec = prefs.getFloat("pref_update_interval_sec", 0.2f)
        val currentMinDistanceM = prefs.getFloat("pref_min_distance_m", 0f)
        val currentMaxAccuracyM = prefs.getFloat("pref_max_accuracy_m", 20f)

        // Map interval (0.1s–10s) to seekbar (0–99)
        val intervalProgress = ((currentIntervalSec - 0.1f) / 9.9f * 99f).toInt().coerceIn(0, 99)
        seekUpdateInterval.progress = intervalProgress
        tvUpdateIntervalValue.text = String.format("%.1f s", currentIntervalSec)

        // Map distance (0m–20m) to seekbar (0–99); 0 = off (no filter)
        val distProgress = (currentMinDistanceM / 20f * 99f).toInt().coerceIn(0, 99)
        seekMinDistance.progress = distProgress
        tvMinDistanceValue.text = if (currentMinDistanceM == 0f) "Off (no filter)"
            else String.format("%.1f m", currentMinDistanceM)

        // Max accuracy: seekbar 0–100, each tick = 1m, 0 = off
        seekMaxAccuracy.progress = currentMaxAccuracyM.toInt().coerceIn(0, 100)
        tvMaxAccuracyValue.text = if (currentMaxAccuracyM == 0f) "Off (no filter)"
            else String.format("%d m", currentMaxAccuracyM.toInt())

        seekUpdateInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = 0.1f + (progress / 99f) * 9.9f
                tvUpdateIntervalValue.text = String.format("%.1f s", value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekMinDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 99f * 20f
                tvMinDistanceValue.text = if (value < 0.05f) "Off (no filter)"
                    else String.format("%.1f m", value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekMaxAccuracy.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvMaxAccuracyValue.text = if (progress == 0) "Off (no filter)"
                    else String.format("%d m", progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val intervalSec = 0.1f + (seekUpdateInterval.progress / 99f) * 9.9f
                val distanceM = seekMinDistance.progress / 99f * 20f
                val finalDistanceM = if (distanceM < 0.05f) 0f else distanceM

                prefs.edit()
                    .putBoolean("pref_emulate_power", switchEmulatePower.isChecked)
                    .putBoolean("pref_sensor_recording", switchSensorRecording.isChecked)
                    .putFloat("pref_update_interval_sec", intervalSec)
                    .putFloat("pref_min_distance_m", finalDistanceM)
                    .putFloat("pref_max_accuracy_m", seekMaxAccuracy.progress.toFloat())
                    .apply()

                if (TrackingService.isRunning) {
                    val intent = Intent(this, TrackingService::class.java).apply {
                        action = TrackingService.ACTION_RELOAD_SETTINGS
                    }
                    startService(intent)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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
            R.id.action_coffee -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/codevoid")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_refresh -> {
                loadRides()
                true
            }
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
