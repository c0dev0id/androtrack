package com.androtrack

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class GradientTrackOverlay(
    private val points: List<GeoPoint>,
    private var segmentColors: List<Int>
) : Overlay() {

    var highlightIndex: Int = -1

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val highlightOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val highlightInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun updateColors(colors: List<Int>) {
        segmentColors = colors
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        if (points.size < 2) return

        val projection = mapView.projection
        val p1 = Point()
        val p2 = Point()

        for (i in 1 until points.size) {
            projection.toPixels(points[i - 1], p1)
            projection.toPixels(points[i], p2)
            trackPaint.color = segmentColors.getOrElse(i) { Color.RED }
            canvas.drawLine(
                p1.x.toFloat(), p1.y.toFloat(),
                p2.x.toFloat(), p2.y.toFloat(),
                trackPaint
            )
        }

        // Draw start marker (green dot)
        if (points.isNotEmpty()) {
            val startPt = Point()
            projection.toPixels(points.first(), startPt)
            val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#43A047")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(startPt.x.toFloat(), startPt.y.toFloat(), 10f, highlightOuterPaint)
            canvas.drawCircle(startPt.x.toFloat(), startPt.y.toFloat(), 7f, startPaint)
        }

        // Draw end marker (red dot)
        if (points.size > 1) {
            val endPt = Point()
            projection.toPixels(points.last(), endPt)
            val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E53935")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(endPt.x.toFloat(), endPt.y.toFloat(), 10f, highlightOuterPaint)
            canvas.drawCircle(endPt.x.toFloat(), endPt.y.toFloat(), 7f, endPaint)
        }

        // Draw highlight indicator
        if (highlightIndex in points.indices) {
            val hp = Point()
            projection.toPixels(points[highlightIndex], hp)
            canvas.drawCircle(hp.x.toFloat(), hp.y.toFloat(), 16f, highlightOuterPaint)
            highlightInnerPaint.color = segmentColors.getOrElse(highlightIndex) { Color.RED }
            canvas.drawCircle(hp.x.toFloat(), hp.y.toFloat(), 12f, highlightInnerPaint)
            canvas.drawCircle(hp.x.toFloat(), hp.y.toFloat(), 16f, highlightBorderPaint)
        }
    }
}
