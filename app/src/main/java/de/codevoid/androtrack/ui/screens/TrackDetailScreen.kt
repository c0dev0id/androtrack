package de.codevoid.androtrack.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import de.codevoid.androtrack.GradientTrackOverlay
import de.codevoid.androtrack.HistogramView
import de.codevoid.androtrack.ui.components.StatTile
import de.codevoid.androtrack.ui.theme.Orange600
import de.codevoid.androtrack.ui.theme.SurfaceCard
import de.codevoid.androtrack.viewmodel.TrackDetailData
import de.codevoid.androtrack.viewmodel.TrackDetailUiState
import de.codevoid.androtrack.viewmodel.TrackDetailViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    filePath: String,
    onBack: () -> Unit,
    viewModel: TrackDetailViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val colors by viewModel.colors.collectAsState()
    val histogramValues by viewModel.histogramValues.collectAsState()
    val indicatorIndex by viewModel.indicatorIndex.collectAsState()

    val fileName = remember(filePath) { File(filePath).nameWithoutExtension }

    LaunchedEffect(filePath) {
        viewModel.load(filePath)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = fileName,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        when (val state = uiState) {
            is TrackDetailUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Orange600)
                }
            }
            is TrackDetailUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Could not load track", color = MaterialTheme.colorScheme.error)
                }
            }
            is TrackDetailUiState.Ready -> {
                TrackDetailContent(
                    data = state.data,
                    currentMode = currentMode,
                    colors = colors,
                    histogramValues = histogramValues,
                    indicatorIndex = indicatorIndex,
                    onModeChange = { viewModel.switchMode(it) },
                    onHistogramTouch = { viewModel.onHistogramTouch(it) },
                    onHistogramRelease = { viewModel.onHistogramRelease() },
                    getIndicatorText = { index -> viewModel.getIndicatorText(index) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackDetailContent(
    data: TrackDetailData,
    currentMode: HistogramView.Mode,
    colors: List<Int>,
    histogramValues: FloatArray,
    indicatorIndex: Int,
    onModeChange: (HistogramView.Mode) -> Unit,
    onHistogramTouch: (Int) -> Unit,
    onHistogramRelease: () -> Unit,
    getIndicatorText: (Int) -> Pair<String, String>?
) {
    val context = LocalContext.current
    val geoPoints = remember(data.trackPoints) {
        data.trackPoints.map { GeoPoint(it.lat, it.lon) }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // Map view
        val mapView = remember {
            org.osmdroid.views.MapView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
                Configuration.getInstance().userAgentValue = context.packageName
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
            }
        }

        // Track overlay reference
        val trackOverlayRef = remember { arrayOfNulls<GradientTrackOverlay>(1) }

        // Update overlay when colors change
        LaunchedEffect(colors) {
            if (colors.isNotEmpty() && geoPoints.isNotEmpty()) {
                trackOverlayRef[0]?.let { mapView.overlays.remove(it) }
                val overlay = GradientTrackOverlay(geoPoints, colors)
                trackOverlayRef[0] = overlay
                mapView.overlays.add(overlay)
                mapView.invalidate()
            }
        }

        // Update highlight when indicator index changes
        LaunchedEffect(indicatorIndex) {
            trackOverlayRef[0]?.highlightIndex = indicatorIndex
            mapView.invalidate()
        }

        DisposableEffect(Unit) {
            onDispose { mapView.onPause() }
        }

        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            update = { mv ->
                mv.onResume()
                if (geoPoints.isNotEmpty() && mv.overlays.none { it is GradientTrackOverlay }) {
                    val minLat = data.trackPoints.minOf { it.lat }
                    val maxLat = data.trackPoints.maxOf { it.lat }
                    val minLon = data.trackPoints.minOf { it.lon }
                    val maxLon = data.trackPoints.maxOf { it.lon }
                    val boundingBox = BoundingBox(maxLat, maxLon, minLat, minLon)
                    mv.post { mv.zoomToBoundingBox(boundingBox, true, 60) }
                }
            }
        )

        // Indicator overlay (shown when touching histogram)
        if (indicatorIndex >= 0) {
            val texts = getIndicatorText(indicatorIndex)
            if (texts != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = SurfaceCard
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(texts.first, style = MaterialTheme.typography.bodyMedium, color = Orange600)
                        Text(texts.second, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Mode filter chips
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                HistogramView.Mode.ALTITUDE to "Altitude",
                HistogramView.Mode.SPEED to "Speed",
                HistogramView.Mode.LEAN_ANGLE to "Lean",
                HistogramView.Mode.FORCE to "Force",
                HistogramView.Mode.SIGNAL to "Signal"
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = currentMode == mode,
                    onClick = { onModeChange(mode) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Orange600,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White
                    )
                )
            }
        }

        // Histogram
        AndroidView(
            factory = { ctx ->
                HistogramView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        200.dpToPx(ctx)
                    )
                    onPositionChanged = { index -> onHistogramTouch(index) }
                    onTouchReleased = { onHistogramRelease() }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            update = { histogram ->
                histogram.mode = currentMode
                if (histogramValues.isNotEmpty() && colors.isNotEmpty()) {
                    histogram.setData(histogramValues, colors.toIntArray())
                }
            }
        )

        // Statistics card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Date/time header
                if (data.trackPoints.first().timeMs > 0) {
                    val dateStr = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault())
                        .format(Date(data.trackPoints.first().timeMs))
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                // Stats grid - row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile(
                        label = "Distance",
                        value = String.format("%.1f km", data.totalDistanceKm),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Avg Speed",
                        value = String.format("%.1f km/h", data.avgSpeedKmh),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Ride Time",
                        value = formatDuration(data.ridingTimeMs),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Stats grid - row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile(
                        label = "Pause",
                        value = formatDuration(data.pauseTimeMs),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Max Lean",
                        value = String.format("%.1f°", data.maxLean),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Max Accel",
                        value = String.format("%.2f G", data.maxAccelG),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (data.maxBrakeG < -0.01f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatTile(
                            label = "Max Brake",
                            value = String.format("%.2f G", Math.abs(data.maxBrakeG)),
                            modifier = Modifier.weight(1f)
                        )
                        if (data.hasSignal) {
                            StatTile(
                                label = "Best Signal",
                                value = "${data.maxSignal} dBm",
                                modifier = Modifier.weight(1f)
                            )
                            StatTile(
                                label = "Worst Signal",
                                value = "${data.minSignal} dBm",
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(2f))
                        }
                    }
                } else if (data.hasSignal) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatTile(
                            label = "Best Signal",
                            value = "${data.maxSignal} dBm",
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "Worst Signal",
                            value = "${data.minSignal} dBm",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun Int.dpToPx(context: android.content.Context): Int {
    return (this * context.resources.displayMetrics.density).toInt()
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0s"
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
