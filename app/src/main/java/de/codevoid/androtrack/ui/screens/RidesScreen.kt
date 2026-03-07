package de.codevoid.androtrack.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.codevoid.androtrack.RideItem
import de.codevoid.androtrack.ui.components.TrackPreviewCanvas
import de.codevoid.androtrack.ui.theme.Orange600
import de.codevoid.androtrack.ui.theme.SurfaceCard
import de.codevoid.androtrack.viewmodel.RidesUiState
import de.codevoid.androtrack.viewmodel.RidesViewModel
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RidesScreen(
    onOpenTrack: (String) -> Unit,
    ridesViewModel: RidesViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by ridesViewModel.uiState.collectAsState()
    val toastMessage by ridesViewModel.toastMessage.collectAsState()

    val selectedItems = remember { mutableStateListOf<RideItem>() }
    var selectionMode by remember { mutableStateOf(false) }

    // Dialog states
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RideItem?>(null) }
    var renameTarget by remember { mutableStateOf<RideItem?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            ridesViewModel.clearToast()
        }
    }

    fun exitSelection() {
        selectionMode = false
        selectedItems.clear()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = selectionMode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "toolbar_anim"
        ) { inSelection ->
            if (inSelection) {
                TopAppBar(
                    title = { Text("${selectedItems.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val all = (uiState as? RidesUiState.Success)?.rides ?: return@IconButton
                            selectedItems.clear()
                            selectedItems.addAll(all)
                        }) {
                            Icon(Icons.Default.CheckBox, contentDescription = "Select all")
                        }
                        IconButton(onClick = { ridesViewModel.downloadZip(selectedItems.toList()) }) {
                            Icon(Icons.Default.Archive, contentDescription = "Download ZIP")
                        }
                        IconButton(onClick = { showMergeDialog = true }) {
                            Icon(Icons.Default.CallMerge, contentDescription = "Merge")
                        }
                        IconButton(onClick = {
                            showDeleteDialog = true
                            deleteTarget = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("Rides") },
                    actions = {
                        IconButton(onClick = { ridesViewModel.loadRides() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }

        val rides = (uiState as? RidesUiState.Success)?.rides ?: emptyList()

        if (rides.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsBike,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "No rides yet",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tap Track to start your first ride",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(rides, key = { it.file.absolutePath }) { ride ->
                    val isSelected = ride in selectedItems
                    RideCard(
                        ride = ride,
                        isSelected = isSelected,
                        onClick = {
                            if (selectionMode) {
                                if (isSelected) selectedItems.remove(ride) else selectedItems.add(ride)
                                if (selectedItems.isEmpty()) exitSelection()
                            } else {
                                onOpenTrack(ride.file.absolutePath)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedItems.add(ride)
                            }
                        },
                        onShare = {
                            val uri = ridesViewModel.getShareUri(context, ride) ?: return@RideCard
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/gpx+xml"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share track"))
                        },
                        onRename = {
                            renameTarget = ride
                            renameText = ride.file.nameWithoutExtension
                            showRenameDialog = true
                        },
                        onDelete = {
                            deleteTarget = ride
                            showDeleteDialog = true
                        },
                        onSelect = {
                            selectionMode = true
                            selectedItems.add(ride)
                        }
                    )
                }
            }
        }
    }

    // Delete dialog
    if (showDeleteDialog) {
        val isBatch = deleteTarget == null
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete") },
            text = {
                Text(
                    if (isBatch) "Delete ${selectedItems.size} selected track(s)?"
                    else "Delete ${deleteTarget?.file?.name}?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    if (isBatch) {
                        ridesViewModel.deleteRides(selectedItems.toList())
                        exitSelection()
                    } else {
                        deleteTarget?.let { ridesViewModel.deleteRide(it) }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename track") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    renameTarget?.let { ridesViewModel.renameRide(it, renameText.trim()) }
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Merge dialog
    if (showMergeDialog) {
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            title = { Text("Merge ${selectedItems.size} rides?") },
            text = { Text("The original files will be deleted after merging.") },
            confirmButton = {
                TextButton(onClick = {
                    showMergeDialog = false
                    ridesViewModel.mergeRides(selectedItems.toList())
                    exitSelection()
                }) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = { showMergeDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RideCard(
    ride: RideItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                SurfaceCard
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Track mini-map
            TrackPreviewCanvas(
                points = ride.trackPoints,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Info column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = ride.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = ride.startTime,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = String.format("%.1f km", ride.distanceKm),
                    style = MaterialTheme.typography.titleLarge,
                    color = Orange600,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatDuration(ride.durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Context menu
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    DropdownMenuItem(
                        text = { Text("Select") },
                        leadingIcon = { Icon(Icons.Default.CheckBox, null) },
                        onClick = { menuExpanded = false; onSelect() }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = { menuExpanded = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
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
