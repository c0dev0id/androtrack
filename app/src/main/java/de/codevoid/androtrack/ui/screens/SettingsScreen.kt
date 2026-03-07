package de.codevoid.androtrack.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.codevoid.androtrack.ui.theme.Orange600
import de.codevoid.androtrack.ui.theme.SurfaceCard
import de.codevoid.androtrack.viewmodel.AppSettings
import de.codevoid.androtrack.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by settingsViewModel.settings.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(
                    title = "GPS",
                    icon = Icons.Default.GpsFixed
                ) {
                    // Update interval
                    var intervalProgress by remember(settings.updateIntervalSec) {
                        mutableStateOf((settings.updateIntervalSec - 0.1f) / 9.9f)
                    }
                    val intervalValue = 0.1f + intervalProgress * 9.9f
                    SliderRow(
                        label = "Update interval",
                        valueLabel = String.format("%.1f s", intervalValue),
                        value = intervalProgress,
                        onValueChange = { intervalProgress = it },
                        onValueChangeFinished = {
                            settingsViewModel.updateSettings(
                                settings.copy(updateIntervalSec = intervalValue)
                            )
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))

                    // Min distance
                    var distProgress by remember(settings.minDistanceM) {
                        mutableStateOf(settings.minDistanceM / 20f)
                    }
                    val distValue = distProgress * 20f
                    SliderRow(
                        label = "Min distance filter",
                        valueLabel = if (distValue < 0.05f) "Off" else String.format("%.1f m", distValue),
                        value = distProgress,
                        onValueChange = { distProgress = it },
                        onValueChangeFinished = {
                            settingsViewModel.updateSettings(
                                settings.copy(minDistanceM = if (distValue < 0.05f) 0f else distValue)
                            )
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))

                    // Max accuracy
                    var accProgress by remember(settings.maxAccuracyM) {
                        mutableStateOf(settings.maxAccuracyM / 100f)
                    }
                    val accValue = (accProgress * 100f).toInt()
                    SliderRow(
                        label = "Max accuracy filter",
                        valueLabel = if (accValue == 0) "Off" else "${accValue} m",
                        value = accProgress,
                        onValueChange = { accProgress = it },
                        onValueChangeFinished = {
                            settingsViewModel.updateSettings(
                                settings.copy(maxAccuracyM = accValue.toFloat())
                            )
                        }
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Sensors",
                    icon = Icons.Default.Sensors
                ) {
                    ToggleRow(
                        label = "Record lean & acceleration",
                        description = "Use device sensors to capture motion data",
                        checked = settings.sensorRecording,
                        onCheckedChange = {
                            settingsViewModel.updateSettings(settings.copy(sensorRecording = it))
                        }
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Power",
                    icon = Icons.Default.BatteryChargingFull
                ) {
                    ToggleRow(
                        label = "Emulate power",
                        description = "Always record regardless of charger state",
                        checked = settings.emulatePower,
                        onCheckedChange = {
                            settingsViewModel.updateSettings(settings.copy(emulatePower = it))
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))

                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val ignoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
                    LinkRow(
                        label = "Battery optimization",
                        value = if (ignoringBattery) "Disabled (recommended)" else "Enabled — tap to disable",
                        valueHighlight = !ignoringBattery,
                        onClick = if (!ignoringBattery) {
                            {
                                try {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                } catch (_: Exception) {}
                            }
                        } else null
                    )
                }
            }

            item {
                SettingsSection(
                    title = "About",
                    icon = Icons.Default.Coffee
                ) {
                    LinkRow(
                        label = "Buy me a coffee",
                        value = "buymeacoffee.com/codevoid",
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/codevoid"))
                                )
                            } catch (_: Exception) {}
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Orange600,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Orange600
            )
        }
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Orange600)
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = Orange600
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = Orange600,
                activeTrackColor = Orange600
            )
        )
    }
}

@Composable
private fun LinkRow(
    label: String,
    value: String,
    valueHighlight: Boolean = false,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (valueHighlight) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
