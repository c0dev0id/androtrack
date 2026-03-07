package de.codevoid.androtrack.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import de.codevoid.androtrack.ui.theme.SurfaceDark
import de.codevoid.androtrack.ui.theme.TrackLine
import de.codevoid.androtrack.ui.theme.TrackStart

private const val COORDINATE_EPSILON = 1e-9

@Composable
fun TrackPreviewCanvas(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier
) {
    val paddingDp = 8.dp
    val strokeWidthDp = 3.dp
    val dotRadiusDp = 4.dp

    Canvas(modifier = modifier.background(SurfaceDark)) {
        val w = size.width
        val h = size.height
        val padding = paddingDp.toPx()

        if (points.size < 2) {
            if (points.size == 1) {
                drawCircle(
                    color = TrackLine,
                    radius = dotRadiusDp.toPx(),
                    center = Offset(w / 2f, h / 2f)
                )
            }
            return@Canvas
        }

        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }

        val latRange = if (maxLat - minLat < COORDINATE_EPSILON) COORDINATE_EPSILON else maxLat - minLat
        val lonRange = if (maxLon - minLon < COORDINATE_EPSILON) COORDINATE_EPSILON else maxLon - minLon

        val availW = w - 2 * padding
        val availH = h - 2 * padding

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

        drawPath(
            path = path,
            color = TrackLine,
            style = Stroke(
                width = strokeWidthDp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        drawCircle(
            color = TrackStart,
            radius = dotRadiusDp.toPx(),
            center = Offset(toX(first.second), toY(first.first))
        )

        val last = points.last()
        drawCircle(
            color = TrackLine,
            radius = dotRadiusDp.toPx(),
            center = Offset(toX(last.second), toY(last.first))
        )
    }
}
