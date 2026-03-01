package com.androtrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { ALTITUDE, SPEED }

    var mode: Mode = Mode.ALTITUDE
        set(value) {
            field = value
            invalidate()
        }

    var onPositionChanged: ((index: Int) -> Unit)? = null
    var onTouchReleased: (() -> Unit)? = null

    private var values: FloatArray = FloatArray(0)
    private var colors: IntArray = IntArray(0)
    private var touchIndex: Int = -1

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val indicatorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")
        style = Paint.Style.FILL
    }

    fun setData(values: FloatArray, colors: IntArray) {
        this.values = values
        this.colors = colors
        touchIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val dp = resources.displayMetrics.density
        val padH = dp * 8f
        val padV = dp * 8f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (values.size < 2) return

        val drawW = w - 2 * padH
        val drawH = h - 2 * padV

        val maxVal = values.maxOrNull() ?: 0f
        val minVal = values.minOrNull() ?: 0f
        val range = if (maxVal > minVal) maxVal - minVal else 1f

        val stepX = drawW / (values.size - 1)

        fun xAt(i: Int) = padH + i * stepX
        fun yAt(i: Int): Float {
            val norm = (values[i] - minVal) / range
            return padV + drawH * (1f - norm)
        }

        // Draw filled area under the curve using vertical strips
        for (i in 0 until values.size - 1) {
            val x1 = xAt(i)
            val x2 = xAt(i + 1)
            val y1 = yAt(i)
            val y2 = yAt(i + 1)
            val bottom = padV + drawH

            val path = Path().apply {
                moveTo(x1, bottom)
                lineTo(x1, y1)
                lineTo(x2, y2)
                lineTo(x2, bottom)
                close()
            }

            fillPaint.color = colors.getOrElse(i) { Color.GRAY }
            fillPaint.alpha = 80
            canvas.drawPath(path, fillPaint)
        }

        // Draw the curve line on top with segment colors
        for (i in 0 until values.size - 1) {
            linePaint.color = colors.getOrElse(i) { Color.GRAY }
            linePaint.alpha = 255
            canvas.drawLine(xAt(i), yAt(i), xAt(i + 1), yAt(i + 1), linePaint)
        }

        // Draw indicator
        if (touchIndex in values.indices) {
            val ix = xAt(touchIndex)
            val iy = yAt(touchIndex)

            indicatorLinePaint.strokeWidth = dp * 1.5f
            indicatorLinePaint.color = Color.argb(180, 255, 255, 255)
            canvas.drawLine(ix, padV, ix, padV + drawH, indicatorLinePaint)

            // Dot
            canvas.drawCircle(ix, iy, dp * 5f, dotFillPaint)
            dotBorderPaint.color = colors.getOrElse(touchIndex) { Color.GRAY }
            dotBorderPaint.strokeWidth = dp * 2f
            canvas.drawCircle(ix, iy, dp * 5f, dotBorderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.size < 2) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val dp = resources.displayMetrics.density
                val padH = dp * 8f
                val drawW = width - 2 * padH
                val stepX = drawW / (values.size - 1)
                val index = ((event.x - padH) / stepX).toInt().coerceIn(0, values.size - 1)
                if (index != touchIndex) {
                    touchIndex = index
                    onPositionChanged?.invoke(index)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                touchIndex = -1
                onTouchReleased?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
