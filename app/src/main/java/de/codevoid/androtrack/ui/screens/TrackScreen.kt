package de.codevoid.androtrack.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.codevoid.androtrack.ui.components.RecordingIndicator
import de.codevoid.androtrack.ui.components.StatTile
import de.codevoid.androtrack.ui.theme.Destructive
import de.codevoid.androtrack.ui.theme.Orange600
import de.codevoid.androtrack.ui.theme.PausedAmber
import de.codevoid.androtrack.ui.theme.SurfaceCard
import de.codevoid.androtrack.viewmodel.TrackingState
import de.codevoid.androtrack.viewmodel.TrackingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackScreen(trackingViewModel: TrackingViewModel) {
    val trackingState by trackingViewModel.state.collectAsState()
    val context = LocalContext.current

    var permissionDenied by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        if (granted) {
            requestBatteryOptIfNeeded(context, trackingViewModel)
            trackingViewModel.startTracking()
        } else {
            permissionDenied = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Track") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        AnimatedContent(
            targetState = trackingState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "track_state"
        ) { state ->
            when (state) {
                is TrackingState.Idle -> {
                    IdleContent(
                        permissionDenied = permissionDenied,
                        onStart = {
                            permissionDenied = false
                            if (trackingViewModel.hasLocationPermission()) {
                                requestBatteryOptIfNeeded(context, trackingViewModel)
                                trackingViewModel.startTracking()
                            } else {
                                val perms = buildList {
                                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }.toTypedArray()
                                permLauncher.launch(perms)
                            }
                        }
                    )
                }
                is TrackingState.Active -> {
                    ActiveContent(
                        state = state,
                        onStop = { trackingViewModel.stopTracking() }
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleContent(
    permissionDenied: Boolean,
    onStart: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "AndroTrack",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "GPS recording for every ride",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Large play button
            Button(
                onClick = onStart,
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange600,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Start recording",
                    modifier = Modifier.size(56.dp)
                )
            }

            Text(
                text = "Start Ride",
                style = MaterialTheme.typography.titleMedium,
                color = Orange600
            )

            if (permissionDenied) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = "Location permission is required to record rides.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveContent(
    state: TrackingState.Active,
    onStop: () -> Unit
) {
    val stats = state.stats
    val isPaused = state.isPaused

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: recording indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                RecordingIndicator(isPaused = isPaused)
            }

            // Hero: speed display
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // We don't have current speed from broadcast; show distance as hero while riding
                // Current speed is not in stats broadcast — show distance prominently
                Text(
                    text = String.format("%.2f", stats.distanceM / 1000.0),
                    fontSize = 80.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Orange600,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "km",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Secondary stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(
                    label = "Duration",
                    value = formatDuration(stats.durationMs),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "GPS ±",
                    value = String.format("%.0fm", stats.curAccuracy),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Rate",
                    value = String.format("%.1f Hz", stats.curRate),
                    modifier = Modifier.weight(1f)
                )
            }

            // Power/pause status band
            if (!stats.isRecording) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PausedAmber.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val message = if (stats.pauseTimeoutMs > 0) {
                        "Paused — reconnect charger within ${formatDuration(stats.pauseTimeoutMs)}"
                    } else {
                        "Paused — waiting for charger"
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = PausedAmber,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Orange600.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Recording — charger connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = Orange600,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Stop button
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Destructive,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Stop Recording",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun requestBatteryOptIfNeeded(context: Context, vm: TrackingViewModel) {
    if (!vm.isIgnoringBatteryOptimizations()) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).also { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: Exception) {}
    }
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
