package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.matching.GeoUtils
import com.example.routeiq.domain.model.GeoPoint
import kotlin.math.abs

/**
 * 4-tier grade bucket for the elevation map overlay (issue #11's resolved design), by absolute
 * grade percent - a steep descent is exactly as visually notable to a rider as a climb of the
 * same steepness, so the bucket ignores sign.
 */
enum class GradeBucket(val label: String) {
    FLAT("<3%"),
    MODERATE("3-6%"),
    STEEP("6-9%"),
    VERY_STEEP(">9%");

    companion object {
        fun forGradePercent(absGradePercent: Double): GradeBucket = when {
            absGradePercent < 3.0 -> FLAT
            absGradePercent < 6.0 -> MODERATE
            absGradePercent < 9.0 -> STEEP
            else -> VERY_STEEP
        }
    }
}

/** One contiguous same-[bucket] stretch of the route, ready to draw as a colored polyline segment. */
data class GradeSegment(val points: List<GeoPoint>, val bucket: GradeBucket)

/**
 * Resamples [routePoints] (ordered route geometry - the raw imported track for a GPX-sourced
 * [ElevationScore.Result], or the matched route's own decoded geometry for a DEM-fallback one)
 * against [profile] (an [ElevationScore.Result.profile]'s (distanceM, elevationM) series) into
 * contiguous [GradeBucket]-tagged polyline segments, for the elevation map overlay (issue #11's
 * grade-shaded route requirement) - the "new distance-to-point grade resampling function" its
 * resolved design calls for.
 *
 * Each [routePoints] entry is assigned its own cumulative haversine distance along the route (same
 * approach [fuelingGapSegments] uses to place its ranges), the elevation at that distance is
 * linearly interpolated from [profile] via [ElevationScore.interpolateElevation], and the local
 * grade between each consecutive pair of points is bucketed by its absolute value. Consecutive
 * same-bucket point-pairs are merged into one segment, sharing the boundary point with its
 * neighbor so the drawn polyline stays visually continuous across bucket changes.
 *
 * Returns an empty list if [routePoints] has fewer than 2 points or [profile] is empty - same
 * empty-input contract as the other overlay-resolution functions ([fuelingGapSegments],
 * [safetyMarkers], [optimizationMarkers]).
 */
fun elevationGradeSegments(routePoints: List<GeoPoint>, profile: List<Pair<Double, Double>>): List<GradeSegment> {
    if (routePoints.size < 2 || profile.isEmpty()) return emptyList()

    val cumulativeM = DoubleArray(routePoints.size)
    for (i in 1 until routePoints.size) {
        cumulativeM[i] = cumulativeM[i - 1] + GeoUtils.haversineDistance(routePoints[i - 1], routePoints[i])
    }
    val elevations = DoubleArray(routePoints.size) { ElevationScore.interpolateElevation(profile, cumulativeM[it]) }

    val stepBuckets = (1 until routePoints.size).map { i ->
        val deltaM = cumulativeM[i] - cumulativeM[i - 1]
        val gradePercent = if (deltaM > 0.0) (elevations[i] - elevations[i - 1]) / deltaM * 100.0 else 0.0
        GradeBucket.forGradePercent(abs(gradePercent))
    }

    val segments = mutableListOf<GradeSegment>()
    var segmentStartPointIdx = 0
    stepBuckets.forEachIndexed { stepIdx, bucket ->
        val isLastStep = stepIdx == stepBuckets.lastIndex
        val bucketChangesNext = !isLastStep && stepBuckets[stepIdx + 1] != bucket
        if (bucketChangesNext || isLastStep) {
            val segmentEndPointIdx = stepIdx + 1
            segments.add(GradeSegment(routePoints.subList(segmentStartPointIdx, segmentEndPointIdx + 1), bucket))
            segmentStartPointIdx = segmentEndPointIdx
        }
    }
    return segments
}
