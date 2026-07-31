package com.example.routeiq.ui.gpx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.routeiq.domain.model.GeoPoint
import kotlin.math.cos
import kotlin.math.max

/**
 * Minimal offline route view: projects the track's lat/lon onto a 2D canvas and draws it as a
 * polyline, scaled to fit. No basemap tiles - just enough to verify the import pipeline renders
 * end-to-end (points + shape) without pulling in a map-tile dependency for v1.
 */
@Composable
fun TrackMapView(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) {
        Text("Not enough points to render a track")
        return
    }

    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    // Longitude degrees shrink toward the poles - correct for that so the track isn't stretched.
    val lonScale = cos(Math.toRadians((minLat + maxLat) / 2.0))
    val span = max(max((maxLon - minLon) * lonScale, maxLat - minLat), MIN_SPAN_DEGREES)

    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        val padding = size.minDimension * 0.05f
        val drawable = size.minDimension - 2 * padding

        fun toOffset(p: GeoPoint): Offset {
            val x = (p.longitude - minLon) * lonScale / span * drawable + padding
            val y = (1.0 - (p.latitude - minLat) / span) * drawable + padding
            return Offset(x.toFloat(), y.toFloat())
        }

        val path = Path().apply {
            val start = toOffset(points.first())
            moveTo(start.x, start.y)
            for (p in points.drop(1)) {
                val o = toOffset(p)
                lineTo(o.x, o.y)
            }
        }
        drawPath(path, color = Color(0xFF1976D2), style = Stroke(width = 4f))
    }
}

private const val MIN_SPAN_DEGREES = 1e-6
