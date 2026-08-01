package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.matching.GeoUtils
import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.Poi
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Fueling score (0-100): how well-supplied [FuelingScore.compute]'s route is with food/water/fuel
 * resupply points, bucketed along the raw imported track (not the matched graph edges - a resupply
 * gap is a fixed physical distance regardless of how much of the route the map graph covers).
 * Higher = better supplied, the inverse of [DiscoveryScore]'s "higher = more of the flagged
 * condition" convention, since a fueling *score* reads more naturally as bigger-is-better.
 *
 * Two independent corridor widths are computed per bucket: [Result.onRoute] (POIs within
 * [ON_ROUTE_CORRIDOR_M]) drives the score itself, while [Result.withDetour] (POIs within
 * [DETOUR_CORRIDOR_M], inclusive of the on-route ones) is a looser, secondary read shown alongside
 * it - a bucket can be sparse on-route but fine with a short detour.
 */
object FuelingScore {

    const val BUCKET_SIZE_M = 5_000.0
    const val ON_ROUTE_CORRIDOR_M = 100.0
    const val DETOUR_CORRIDOR_M = 500.0
    const val BORDERLINE_MAX_COUNT = 5
    const val EXTENDED_GAP_MIN_CONSECUTIVE_BUCKETS = 3

    /** Resupply-relevant POI categories - excludes `bicycle` (repair, not food/water/fuel resupply). */
    val RESUPPLY_CATEGORIES = setOf(
        "restaurant", "fast_food", "cafe", "bakery", "drinking_water", "vending_machine", "fuel",
    )

    enum class BucketSeverity { DENSE, BORDERLINE, SPARSE }

    /** A bucket run of consecutive [BucketSeverity.SPARSE] buckets, as a distance range along the track. */
    data class DistanceRange(val startM: Double, val endM: Double)

    /** One corridor width's read of the route: per-bucket counts/severities plus derived gap ranges. */
    data class CorridorResult(
        val counts: List<Int>,
        val severities: List<BucketSeverity>,
        val sparseRanges: List<DistanceRange>,
        val extendedGaps: List<DistanceRange>,
    )

    data class Result(
        val score: Int,
        val bucketSizeM: Double,
        val totalDistanceM: Double,
        val onRoute: CorridorResult,
        val withDetour: CorridorResult,
    )

    /**
     * Returns null if [points] has fewer than 2 points or zero total length (nothing to score) -
     * same empty-input contract as [DiscoveryScore.compute].
     */
    fun compute(points: List<GeoPoint>, pois: List<Poi>): Result? {
        if (points.size < 2) return null
        val cumulativeM = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulativeM[i] = cumulativeM[i - 1] + GeoUtils.haversineDistance(points[i - 1], points[i])
        }
        val totalDistanceM = cumulativeM.last()
        if (totalDistanceM <= 0.0) return null

        val bucketCount = if (totalDistanceM <= BUCKET_SIZE_M) 1 else ceil(totalDistanceM / BUCKET_SIZE_M).toInt()
        val onRouteCounts = MutableList(bucketCount) { 0 }
        val detourCounts = MutableList(bucketCount) { 0 }

        for (poi in pois) {
            if (poi.category !in RESUPPLY_CATEGORIES) continue
            val (distanceAlongM, perpendicularM) = projectOntoTrack(poi.location, points, cumulativeM)
            if (perpendicularM > DETOUR_CORRIDOR_M) continue
            val bucket = (distanceAlongM / BUCKET_SIZE_M).toInt().coerceIn(0, bucketCount - 1)
            detourCounts[bucket]++
            if (perpendicularM <= ON_ROUTE_CORRIDOR_M) onRouteCounts[bucket]++
        }

        fun bucketLengthM(index: Int) =
            if (index == bucketCount - 1) totalDistanceM - index * BUCKET_SIZE_M else BUCKET_SIZE_M

        val onRoute = buildCorridorResult(onRouteCounts, totalDistanceM, ::bucketLengthM)
        val withDetour = buildCorridorResult(detourCounts, totalDistanceM, ::bucketLengthM)

        val sparseLengthM = onRoute.severities.indices
            .filter { onRoute.severities[it] == BucketSeverity.SPARSE }
            .sumOf(::bucketLengthM)
        val score = (100 - (100 * sparseLengthM / totalDistanceM).roundToInt()).coerceIn(0, 100)

        return Result(
            score = score,
            bucketSizeM = BUCKET_SIZE_M,
            totalDistanceM = totalDistanceM,
            onRoute = onRoute,
            withDetour = withDetour,
        )
    }

    private fun buildCorridorResult(
        counts: List<Int>,
        totalDistanceM: Double,
        bucketLengthM: (Int) -> Double,
    ): CorridorResult {
        val severities = counts.map { count ->
            when {
                count == 0 -> BucketSeverity.SPARSE
                count <= BORDERLINE_MAX_COUNT -> BucketSeverity.BORDERLINE
                else -> BucketSeverity.DENSE
            }
        }
        val sparseRuns = consecutiveRuns(severities, BucketSeverity.SPARSE)
        fun rangeFor(run: IntRange) = DistanceRange(
            startM = run.first * BUCKET_SIZE_M,
            endM = minOf(run.last * BUCKET_SIZE_M + bucketLengthM(run.last), totalDistanceM),
        )
        return CorridorResult(
            counts = counts,
            severities = severities,
            sparseRanges = sparseRuns.map(::rangeFor),
            extendedGaps = sparseRuns.filter { it.count() >= EXTENDED_GAP_MIN_CONSECUTIVE_BUCKETS }.map(::rangeFor),
        )
    }

    /** Maximal runs of consecutive buckets matching [target], as inclusive index ranges. */
    private fun consecutiveRuns(severities: List<BucketSeverity>, target: BucketSeverity): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var runStart = -1
        for (i in severities.indices) {
            if (severities[i] == target) {
                if (runStart == -1) runStart = i
            } else if (runStart != -1) {
                runs.add(runStart until i)
                runStart = -1
            }
        }
        if (runStart != -1) runs.add(runStart..severities.lastIndex)
        return runs
    }

    /** Nearest point on [points] to [poi], as (distance along the track from its start, perpendicular distance to it), in meters. */
    private fun projectOntoTrack(poi: GeoPoint, points: List<GeoPoint>, cumulativeM: DoubleArray): Pair<Double, Double> {
        var bestPerpendicularM = Double.MAX_VALUE
        var bestAlongM = 0.0
        for (i in 0 until points.size - 1) {
            val (fraction, perpendicularM) = GeoUtils.projectOntoSegment(poi, points[i], points[i + 1])
            if (perpendicularM < bestPerpendicularM) {
                bestPerpendicularM = perpendicularM
                bestAlongM = cumulativeM[i] + fraction * (cumulativeM[i + 1] - cumulativeM[i])
            }
        }
        return bestAlongM to bestPerpendicularM
    }
}

/** Qualitative read of a [FuelingScore.Result.score], for display alongside the raw number. */
enum class FuelingBucket(val label: String) {
    POOR("Poor"),
    RISKY("Risky"),
    ADEQUATE("Adequate"),
    WELL_FUELED("Well-fueled");

    companion object {
        fun forScore(score: Int): FuelingBucket = when {
            score <= 24 -> POOR
            score <= 49 -> RISKY
            score <= 74 -> ADEQUATE
            else -> WELL_FUELED
        }
    }
}
