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
 *
 * [matchedSegments] - one point-list per [RouteGraphMatcher][com.example.routeiq.domain.matching.RouteGraphMatcher]-matched,
 * previously-traversed edge, from [com.example.routeiq.domain.matching.matchedRouteSegments] -
 * draws the matched route distinctly (thicker, green) on top of the raw track, per issue #4's map
 * acceptance criterion. [undiscoveredSegments] is the same shape but for matched edges with
 * `isTraversed == false`, drawn gray instead of green so undiscovered stretches read distinctly
 * from both the raw track and the rest of the matched route (issue #5). [fuelingSparseSegments] /
 * [fuelingExtendedGapSegments] (issue #6, from [com.example.routeiq.domain.scoring.fuelingGapSegments])
 * highlight POI-sparse stretches of the raw track in red, with extended (15km+) gaps drawn heavier.
 * [safetyMarkers] (issue #9, from [com.example.routeiq.domain.scoring.safetyMarkers]) draws a
 * colored point at each flagged junction - a point marker rather than a line-segment overlay
 * since junctions are single locations, not spans. Colors are resolved by the caller (a
 * `GeoPoint`-to-`Color` pair) rather than this view depending on `SafetyScore.Tier`, matching how
 * the elevation chart's climb-category colors stay UI-local instead of living in a shared view.
 */
@Composable
fun TrackMapView(
    points: List<GeoPoint>,
    modifier: Modifier = Modifier,
    matchedSegments: List<List<GeoPoint>>? = null,
    undiscoveredSegments: List<List<GeoPoint>>? = null,
    fuelingSparseSegments: List<List<GeoPoint>>? = null,
    fuelingExtendedGapSegments: List<List<GeoPoint>>? = null,
    safetyMarkers: List<Pair<GeoPoint, Color>>? = null,
) {
    if (points.size < 2) {
        Text("Not enough points to render a track")
        return
    }

    val framePoints = points + matchedSegments.orEmpty().flatten() + undiscoveredSegments.orEmpty().flatten() +
        safetyMarkers.orEmpty().map { it.first }
    val minLat = framePoints.minOf { it.latitude }
    val maxLat = framePoints.maxOf { it.latitude }
    val minLon = framePoints.minOf { it.longitude }
    val maxLon = framePoints.maxOf { it.longitude }
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

        fun pathFor(segment: List<GeoPoint>): Path = Path().apply {
            val start = toOffset(segment.first())
            moveTo(start.x, start.y)
            for (p in segment.drop(1)) {
                val o = toOffset(p)
                lineTo(o.x, o.y)
            }
        }

        drawPath(pathFor(points), color = Color(0xFF1976D2), style = Stroke(width = 4f))
        matchedSegments?.forEach { segment ->
            if (segment.size >= 2) {
                drawPath(pathFor(segment), color = Color(0xFF2E7D32), style = Stroke(width = 6f))
            }
        }
        undiscoveredSegments?.forEach { segment ->
            if (segment.size >= 2) {
                drawPath(pathFor(segment), color = Color(0xFF757575), style = Stroke(width = 6f))
            }
        }
        fuelingSparseSegments?.forEach { segment ->
            if (segment.size >= 2) {
                drawPath(pathFor(segment), color = Color(0xFFE53935), style = Stroke(width = 6f))
            }
        }
        fuelingExtendedGapSegments?.forEach { segment ->
            if (segment.size >= 2) {
                drawPath(pathFor(segment), color = Color(0xFFE53935), style = Stroke(width = 9f))
            }
        }
        safetyMarkers?.forEach { (point, color) ->
            drawCircle(color = color, radius = SAFETY_MARKER_RADIUS_PX, center = toOffset(point))
        }
    }
}

private const val MIN_SPAN_DEGREES = 1e-6
private const val SAFETY_MARKER_RADIUS_PX = 7f
