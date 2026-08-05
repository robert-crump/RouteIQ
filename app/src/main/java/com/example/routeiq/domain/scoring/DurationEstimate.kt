package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GraphEdge
import kotlin.math.floor

/**
 * Estimated ride duration for a matched route, summed edge-by-edge. A traversed edge
 * ([GraphEdge.isTraversed]) uses the rider's own historical speed on that exact edge
 * ([GraphEdge.speedMedianKmh], falling back to [GraphEdge.speedMeanKmh] when the median is
 * missing). An untraversed edge falls back to the typical speed of a [SLOPE_BUCKET_WIDTH_PERCENT]-
 * wide inclination bucket, built from [historicalTraversedEdges] - the rider's *whole* ride
 * history graph-wide, not just whichever traversed edges happen to be part of this route - so the
 * fallback doesn't depend on a nearby traversed edge existing. That's a deliberate departure from
 * Velometrics' nearest-parallel-edge "surrogate" approach (see issue #1's PRD); there's no direct
 * port here, just the same bucket width Ride-Graph's own flow/energy predictors use for
 * slope-binned lookups (2%).
 *
 * Three-tier fallback per edge, in order: (1) the edge's own history, (2) its slope bucket's
 * historical average, (3) the rider's graph-wide average speed across all historical edges -
 * or [DEFAULT_FALLBACK_SPEED_KMH] if there's no ride history at all yet. Unlike Ride-Graph's own
 * flow predictor, an empty exact-match bucket does *not* fall through to a nearby bucket - it
 * drops straight to the graph-wide average - a deliberate simplification, since a single
 * mis-estimated edge only nudges the route total slightly, whereas nearest-bucket interpolation
 * would add real complexity for a marginal accuracy gain.
 */
object DurationEstimate {

    const val SLOPE_BUCKET_WIDTH_PERCENT = 2.0

    /** Last-resort speed when there's no ride history anywhere in the graph to fall back on. */
    const val DEFAULT_FALLBACK_SPEED_KMH = 20.0

    /** Which tier of the fallback chain produced a given edge's speed estimate. */
    enum class EstimateSource { OWN_HISTORY, SLOPE_BUCKET, GRAPH_AVERAGE }

    data class Result(
        val totalDurationS: Double,
        val ownHistoryEdgeCount: Int,
        val slopeBucketEdgeCount: Int,
        val graphAverageEdgeCount: Int,
    )

    /**
     * Returns null if [matchedEdges] is empty - same empty-input contract as [DiscoveryScore.compute].
     * [historicalTraversedEdges] may be empty (a brand-new rider with no ride history at all); every
     * untraversed edge then falls through to [DEFAULT_FALLBACK_SPEED_KMH].
     */
    fun compute(matchedEdges: List<GraphEdge>, historicalTraversedEdges: List<GraphEdge>): Result? {
        if (matchedEdges.isEmpty()) return null

        val bucketSpeeds = buildBucketSpeeds(historicalTraversedEdges)
        val graphAverageSpeedKmh = averageOwnSpeedKmh(historicalTraversedEdges)

        var totalDurationS = 0.0
        var ownHistoryCount = 0
        var slopeBucketCount = 0
        var graphAverageCount = 0

        for (edge in matchedEdges) {
            val ownSpeedKmh = ownSpeedKmh(edge)
            val bucketSpeedKmh = edge.slopePercent?.let { bucketSpeeds[bucketFor(it)] }
            val speedKmh: Double
            when {
                ownSpeedKmh != null -> {
                    speedKmh = ownSpeedKmh
                    ownHistoryCount++
                }
                bucketSpeedKmh != null -> {
                    speedKmh = bucketSpeedKmh
                    slopeBucketCount++
                }
                else -> {
                    speedKmh = graphAverageSpeedKmh ?: DEFAULT_FALLBACK_SPEED_KMH
                    graphAverageCount++
                }
            }
            totalDurationS += secondsFor(edge.lengthM, speedKmh)
        }

        return Result(
            totalDurationS = totalDurationS,
            ownHistoryEdgeCount = ownHistoryCount,
            slopeBucketEdgeCount = slopeBucketCount,
            graphAverageEdgeCount = graphAverageCount,
        )
    }

    /** An edge's own historical speed, only trusted when it's actually been ridden. */
    private fun ownSpeedKmh(edge: GraphEdge): Double? =
        if (edge.isTraversed) edge.speedMedianKmh ?: edge.speedMeanKmh else null

    private fun secondsFor(lengthM: Double, speedKmh: Double): Double {
        if (speedKmh <= 0.0) return 0.0
        val speedMs = speedKmh * 1000.0 / 3600.0
        return lengthM / speedMs
    }

    /** Floors to the bucket's lower bound, e.g. a 3.1% slope with a 2%-wide bucket lands in the [2, 4) bucket, keyed by 2. */
    private fun bucketFor(slopePercent: Double): Int = floor(slopePercent / SLOPE_BUCKET_WIDTH_PERCENT).toInt()

    /** Per-bucket average of each historical edge's own speed (median, falling back to mean) - edges with neither are skipped. */
    private fun buildBucketSpeeds(historicalTraversedEdges: List<GraphEdge>): Map<Int, Double> =
        historicalTraversedEdges
            .mapNotNull { edge ->
                val slope = edge.slopePercent ?: return@mapNotNull null
                val speed = ownSpeedKmh(edge) ?: return@mapNotNull null
                bucketFor(slope) to speed
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, speeds) -> speeds.average() }

    /** Graph-wide average of every historical edge's own speed, regardless of slope - the last-resort fallback tier. */
    private fun averageOwnSpeedKmh(historicalTraversedEdges: List<GraphEdge>): Double? =
        historicalTraversedEdges.mapNotNull(::ownSpeedKmh).takeIf { it.isNotEmpty() }?.average()
}
