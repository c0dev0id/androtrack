package com.androtrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class TrackPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val COORDINATE_EPSILON = 1e-9
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#43A047")
        style = Paint.Style.FILL
    }

    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")
        style = Paint.Style.FILL
    }

    private var points: List<Pair<Double, Double>> = emptyList()

    fun setTrackPoints(pts: List<Pair<Double, Double>>) {
        points = pts
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = resources.displayMetrics.density * 8f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (points.size < 2) {
            if (points.size == 1) {
                val cx = w / 2f
                val cy = h / 2f
                canvas.drawCircle(cx, cy, 4f, endPaint)
            }
            return
        }

        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }

        val latRange = if (maxLat - minLat < COORDINATE_EPSILON) COORDINATE_EPSILON else maxLat - minLat
        val lonRange = if (maxLon - minLon < COORDINATE_EPSILON) COORDINATE_EPSILON else maxLon - minLon

        val availW = w - 2 * padding
        val availH = h - 2 * padding

        // Maintain aspect ratio to avoid distortion
        val scaleX = availW / lonRange
        val scaleY = availH / latRange
        val scale = minOf(scaleX, scaleY)

        val scaledW = lonRange * scale
        val scaledH = latRange * scale
        val offsetX = padding + (availW - scaledW) / 2f
        val offsetY = padding + (availH - scaledH) / 2f

        fun toX(lon: Double) = (offsetX + (lon - minLon) * scale).toFloat()
        fun toY(lat: Double) = (offsetY + (maxLat - lat) * scale).toFloat()

        val path = Path()
        val first = points.first()
        path.moveTo(toX(first.second), toY(first.first))
        for (i in 1 until points.size) {
            path.lineTo(toX(points[i].second), toY(points[i].first))
        }
        canvas.drawPath(path, trackPaint)

        canvas.drawCircle(toX(first.second), toY(first.first), 5f, startPaint)

        val last = points.last()
        canvas.drawCircle(toX(last.second), toY(last.first), 5f, endPaint)
    }
}
