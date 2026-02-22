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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
                }
                TrackingService.ACTION_TRACKING_STOPPED -> {
                    isTracking = false
                    updateFab()
                    loadRides()
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
            onItemLongClick = { position ->
                if (!adapter.selectionMode) {
                    adapter.selectionMode = true
                    actionMode = startActionMode(actionModeCallback)
                }
                adapter.toggleSelection(position)
                val count = adapter.getSelectedCount()
                actionMode?.title = "$count selected"
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackingReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackingReceiver, filter)
        }
        updateFab()
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
    }

    private fun stopTrackingService() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(intent)
        isTracking = false
        updateFab()
    }

    private fun updateFab() {
        binding.fab.setImageResource(
            if (isTracking) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_menu_mylocation
        )
        binding.fab.contentDescription = if (isTracking) "Stop tracking" else "Start tracking"
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

    private fun downloadSelectedAsZip() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, "No rides selected", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val zipName = "androtrack_$timestamp.zip"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val zipFile = File(downloadsDir, zipName)

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (ride in selected) {
                    zos.putNextEntry(ZipEntry(ride.file.name))
                    FileInputStream(ride.file).use { fis -> fis.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            Toast.makeText(this, "Saved to Downloads/$zipName", Toast.LENGTH_LONG).show()
            actionMode?.finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to create ZIP: ${e.message}", Toast.LENGTH_LONG).show()
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
