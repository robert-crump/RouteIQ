package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.matching.GeoUtils
import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphEdge
import kotlin.math.roundToInt

/**
 * Elevation/climb read of a route (issue #7), computed purely from elevation data - no map
 * matching required unless the `.gpx` itself has no `<ele>` data (see [computeFromMatchedEdges]).
 * Resolved via a `/grill-me` session (see issue #7's comment thread); key decisions:
 *
 * - Gain/loss is computed on the *full* smoothed elevation series (ported from Velometrics'
 *   `GpxAnalysisUtils.smoothElevations`/`elevationGainLoss`), not the resampled grade profile
 *   below, so short rollers between 100m samples aren't undercounted.
 * - The grade profile (and the climb detection built on it) resamples that smoothed series into
 *   fixed [SEGMENT_LENGTH_M] segments - stable across `.gpx` sources with varying point density.
 * - Climbs are contiguous net-ascending runs over the grade profile, tolerating dips of up to
 *   [MAX_DIP_TOLERANCE_M] before ending the climb, and only counted if they clear both
 *   [MIN_CLIMB_LENGTH_M] and [MIN_CLIMB_GAIN_M] (filters GPS noise and driveway ramps).
 * - Climb categorization (`difficulty = length_km * avg_grade^2`) uses the Tour de France's
 *   category thresholds, attributed to race director Thierry Gouvenou via biketips.com - not
 *   self-invented - and cross-checked against Col du Tourmalet's own worked example
 *   (18.3km at 7.7% -> difficulty 1085 -> HC).
 * - Deliberately no synthesized 0-100 score (diverges from Discovery/Fueling's shape): the chart
 *   plus climb categories and gain figures stand on their own per the issue's resolved design.
 */
object ElevationScore {

    const val ELEVATION_SMOOTHING_WINDOW = 5
    const val SEGMENT_LENGTH_M = 100.0
    const val MAX_DIP_TOLERANCE_M = 500.0
    const val MIN_CLIMB_LENGTH_M = 1_000.0
    const val MIN_CLIMB_GAIN_M = 30.0

    enum class Source { GPX, DEM_FALLBACK }

    enum class ClimbCategory(val label: String) {
        CAT_4("Cat 4"),
        CAT_3("Cat 3"),
        CAT_2("Cat 2"),
        CAT_1("Cat 1"),
        HC("HC");

        companion object {
            /** TdF category thresholds: Cat 4 <=75, Cat 3 75+, Cat 2 150+, Cat 1 300+, HC 600+. */
            fun forDifficulty(difficulty: Double): ClimbCategory = when {
                difficulty >= 600.0 -> HC
                difficulty >= 300.0 -> CAT_1
                difficulty >= 150.0 -> CAT_2
                difficulty >= 75.0 -> CAT_3
                else -> CAT_4
            }
        }
    }

    data class Climb(
        val startM: Double,
        val endM: Double,
        val gainM: Double,
        val avgGradePercent: Double,
        val difficulty: Double,
        val category: ClimbCategory,
    )

    /** [profile] is the full smoothed (distanceM, elevationM) series, in order - the chart's plotting source. */
    data class Result(
        val profile: List<Pair<Double, Double>>,
        val totalDistanceM: Double,
        val gainM: Int,
        val lossM: Int,
        val gainPer100km: Int,
        val climbs: List<Climb>,
        val source: Source,
    )

    /**
     * Computes from the `.gpx`'s own elevation series. Returns null if fewer than 2 points have
     * non-null elevation, or the covered distance is zero (nothing to score) - matching points
     * with null elevation are skipped, same as Velometrics' `elevationProfile`.
     */
    fun computeFromGpx(points: List<GeoPoint>, elevations: List<Double?>): Result? {
        val profile = elevationProfile(points, elevations)
        if (profile.size < 2 || profile.last().first <= 0.0) return null
        return computeFromProfile(profile, Source.GPX)
    }

    /**
     * Fallback for `.gpx` files with no elevation data at all: builds a synthetic, relative
     * elevation profile from each matched edge's own [GraphEdge.slopePercent] (a Copernicus
     * DEM-derived grade) times its length, walked in matched order. The resulting elevation
     * values are relative (an arbitrary baseline), not absolute - fine here since gain/loss and
     * climb detection only look at deltas. Edges with a null `slopePercent` contribute no rise
     * (treated as flat) rather than being skipped, so distance accounting stays correct.
     * Returns null if [matchedEdges] is empty or has zero total length.
     */
    fun computeFromMatchedEdges(matchedEdges: List<GraphEdge>): Result? {
        if (matchedEdges.isEmpty()) return null
        var cumulativeM = 0.0
        var elevation = 0.0
        val profile = mutableListOf(0.0 to 0.0)
        for (edge in matchedEdges) {
            val riseM = edge.lengthM * ((edge.slopePercent ?: 0.0) / 100.0)
            elevation += riseM
            cumulativeM += edge.lengthM
            profile.add(cumulativeM to elevation)
        }
        if (cumulativeM <= 0.0) return null
        return computeFromProfile(profile, Source.DEM_FALLBACK)
    }

    private fun computeFromProfile(profile: List<Pair<Double, Double>>, source: Source): Result {
        val smoothedElevations = smoothElevations(profile.map { it.second })
        val smoothedProfile = profile.mapIndexed { i, (distanceM, _) -> distanceM to smoothedElevations[i] }
        val (gainM, lossM) = elevationGainLoss(smoothedElevations)
        val totalDistanceM = profile.last().first
        val gainPer100km = elevationGainPer100km(gainM, totalDistanceM)
        val gradeProfile = resampleToFixedSegments(smoothedProfile, SEGMENT_LENGTH_M)
        val climbs = detectClimbs(gradeProfile)
        return Result(smoothedProfile, totalDistanceM, gainM, lossM, gainPer100km, climbs, source)
    }

    /**
     * Pairs each point with non-null elevation to its cumulative distance (m) along [points].
     * Ported from Velometrics' `GpxAnalysisUtils.elevationProfile`.
     */
    private fun elevationProfile(points: List<GeoPoint>, elevations: List<Double?>): List<Pair<Double, Double>> {
        val profile = mutableListOf<Pair<Double, Double>>()
        var cumulativeM = 0.0
        for (i in points.indices) {
            if (i > 0) cumulativeM += GeoUtils.haversineDistance(points[i - 1], points[i])
            val elevation = elevations.getOrNull(i) ?: continue
            profile.add(cumulativeM to elevation)
        }
        return profile
    }

    /**
     * Centered moving-average smoothing over [windowSize] points, to avoid inflated gain/loss
     * totals from GPS/DEM jitter. Ported from Velometrics' `GpxAnalysisUtils.smoothElevations`.
     */
    private fun smoothElevations(elevations: List<Double>, windowSize: Int = ELEVATION_SMOOTHING_WINDOW): List<Double> {
        if (elevations.isEmpty()) return elevations
        val halfWindow = windowSize / 2
        return elevations.indices.map { i ->
            val from = (i - halfWindow).coerceAtLeast(0)
            val to = (i + halfWindow).coerceAtMost(elevations.lastIndex)
            elevations.subList(from, to + 1).average()
        }
    }

    /** Total elevation gain and loss (m), as non-negative integers. Ported from Velometrics' `elevationGainLoss`. */
    private fun elevationGainLoss(smoothed: List<Double>): Pair<Int, Int> {
        var gainM = 0.0
        var lossM = 0.0
        smoothed.zipWithNext().forEach { (prev, next) ->
            val delta = next - prev
            if (delta > 0) gainM += delta else lossM -= delta
        }
        return gainM.roundToInt() to lossM.roundToInt()
    }

    /** Elevation gain normalized to a 100km distance. Ported from Velometrics' `elevationGainPer100km`. */
    private fun elevationGainPer100km(gainM: Int, totalDistanceM: Double): Int {
        if (totalDistanceM <= 0.0) return 0
        return (gainM * 100_000.0 / totalDistanceM).roundToInt()
    }

    /**
     * Resamples [profile] (sorted ascending by distance) onto a fixed [segmentLengthM] grid via
     * linear interpolation, from 0 up to the profile's total distance (the final segment may be
     * shorter). The grade profile climb detection operates on - separate from the full-resolution
     * [profile] used for gain/loss, per the issue's resolved design.
     */
    private fun resampleToFixedSegments(profile: List<Pair<Double, Double>>, segmentLengthM: Double): List<Pair<Double, Double>> {
        val totalM = profile.last().first
        val boundaries = generateSequence(0.0) { it + segmentLengthM }.takeWhile { it < totalM }.toMutableList()
        boundaries.add(totalM)
        return boundaries.map { distanceM -> distanceM to interpolateElevation(profile, distanceM) }
    }

    /** Package-visible (not private) so [elevationGradeSegments] can reuse it against [Result.profile] without duplicating the lookup. */
    internal fun interpolateElevation(profile: List<Pair<Double, Double>>, distanceM: Double): Double {
        if (profile.size == 1) return profile[0].second
        var i = 0
        while (i < profile.size - 2 && profile[i + 1].first < distanceM) i++
        val (d0, e0) = profile[i]
        val (d1, e1) = profile[i + 1]
        if (d1 <= d0) return e0
        val t = ((distanceM - d0) / (d1 - d0)).coerceIn(0.0, 1.0)
        return e0 + t * (e1 - e0)
    }

    /**
     * Contiguous net-ascending runs over [gradeProfile], tolerating dips of up to
     * [MAX_DIP_TOLERANCE_M] of distance before ending a climb (so a short rolling dip mid-climb
     * doesn't split it in two), only kept if they clear [MIN_CLIMB_LENGTH_M] and [MIN_CLIMB_GAIN_M].
     */
    private fun detectClimbs(gradeProfile: List<Pair<Double, Double>>): List<Climb> {
        if (gradeProfile.size < 2) return emptyList()
        val climbs = mutableListOf<Climb>()
        var climbing = false
        var startIdx = 0
        var peakIdx = 0
        var dipAccumM = 0.0

        fun finalizeClimb() {
            val startM = gradeProfile[startIdx].first
            val endM = gradeProfile[peakIdx].first
            val lengthM = endM - startM
            val gainM = gradeProfile[peakIdx].second - gradeProfile[startIdx].second
            if (lengthM >= MIN_CLIMB_LENGTH_M && gainM >= MIN_CLIMB_GAIN_M) {
                val avgGradePercent = (gainM / lengthM) * 100.0
                val difficulty = (lengthM / 1000.0) * avgGradePercent * avgGradePercent
                climbs.add(Climb(startM, endM, gainM, avgGradePercent, difficulty, ClimbCategory.forDifficulty(difficulty)))
            }
        }

        for (i in 1 until gradeProfile.size) {
            val delta = gradeProfile[i].second - gradeProfile[i - 1].second
            if (delta > 0) {
                if (!climbing) {
                    climbing = true
                    startIdx = i - 1
                }
                peakIdx = i
                dipAccumM = 0.0
            } else if (climbing) {
                dipAccumM += gradeProfile[i].first - gradeProfile[i - 1].first
                if (dipAccumM > MAX_DIP_TOLERANCE_M) {
                    finalizeClimb()
                    climbing = false
                    dipAccumM = 0.0
                }
            }
        }
        if (climbing) finalizeClimb()
        return climbs
    }
}
