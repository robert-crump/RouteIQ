package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.matching.GeoUtils
import com.example.routeiq.domain.model.GeoPoint

/**
 * Slices [points] into point-lists covering each of [ranges] (by cumulative distance along the raw
 * track), for drawing fueling gap segments distinctly on the map - the source geometry here is the
 * raw imported track rather than matched graph-edge polylines, since [FuelingScore] buckets against
 * [points] directly (see [FuelingScore]'s doc comment for why).
 */
fun fuelingGapSegments(points: List<GeoPoint>, ranges: List<FuelingScore.DistanceRange>): List<List<GeoPoint>> {
    if (points.size < 2 || ranges.isEmpty()) return emptyList()
    val cumulativeM = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cumulativeM[i] = cumulativeM[i - 1] + GeoUtils.haversineDistance(points[i - 1], points[i])
    }
    return ranges.map { range ->
        points.filterIndexed { index, _ -> cumulativeM[index] in range.startM..range.endM }
    }.filter { it.size >= 2 }
}
