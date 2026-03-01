package de.codevoid.androtrack

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RideAdapter(
    private val items: MutableList<RideItem>,
    private val onItemClick: (Int) -> Unit,
    private val onItemLongClick: (Int, View) -> Unit
) : RecyclerView.Adapter<RideAdapter.RideViewHolder>() {

    private val selectedPositions = mutableSetOf<Int>()
    var selectionMode = false

    inner class RideViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        val tvStartTime: TextView = itemView.findViewById(R.id.tvStartTime)
        val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        val tvDistance: TextView = itemView.findViewById(R.id.tvDistance)
        val trackPreview: TrackPreviewView = itemView.findViewById(R.id.trackPreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RideViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ride, parent, false)
        return RideViewHolder(view)
    }

    override fun onBindViewHolder(holder: RideViewHolder, position: Int) {
        val item = items[position]

        holder.tvDate.text = item.date
        holder.tvStartTime.text = "Start: ${item.startTime}"
        holder.tvDuration.text = "Duration: ${formatDuration(item.durationMs)}"
        holder.tvDistance.text = String.format("%.1f km", item.distanceKm)
        holder.trackPreview.setTrackPoints(item.trackPoints)

        val isSelected = selectedPositions.contains(position)
        holder.itemView.setBackgroundColor(
            if (isSelected)
                holder.itemView.context.getColor(R.color.selection_highlight)
            else
                holder.itemView.context.getColor(android.R.color.transparent)
        )

        holder.itemView.setOnClickListener { onItemClick(position) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(position, it)
            true
        }
    }

    override fun getItemCount() = items.size

    fun getItemAt(position: Int): RideItem? =
        if (position in items.indices) items[position] else null

    fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
    }

    fun selectAll() {
        selectedPositions.clear()
        for (i in items.indices) selectedPositions.add(i)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedPositions.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<RideItem> =
        selectedPositions.sorted().map { items[it] }

    fun getSelectedCount() = selectedPositions.size

    fun updateItems(newItems: List<RideItem>) {
        items.clear()
        items.addAll(newItems)
        clearSelection()
        notifyDataSetChanged()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0m"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
