package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GraphTurn
import kotlin.math.roundToInt

/**
 * Junction-only stop/braking cost read of a route (issue #8). Was blocked pending
 * [Ride-Graph#98](https://github.com/robert-crump/Ride-Graph/issues/98) exporting
 * `braking_penalty_s`/`braking_penalty_source`/`braking_penalty_confidence` to `map_turns` -
 * that issue closed with real calibrated data, unblocking this one. Key decisions (from the
 * original `/grill-me` session's resolution, posted as a comment on issue #8, finished off here
 * now that real data exists):
 *
 * - Model pivoted away from the issue's literal "stop_penalty / braking_probability" wording -
 *   `braking_probability` (issue #36) is dimensionless and mostly null (99.55% of junctions in
 *   the pre-#98 graph); the actual model sums two *seconds*-denominated figures per junction:
 *   [GraphTurn.stopPenalty] (wait-time-only at a full stop) and [GraphTurn.brakingPenaltyS]
 *   (Ride-Graph#98's direct braking-deceleration + indirect re-acceleration elapsed-time loss) -
 *   additive, not overlapping, per that issue's own description of the two figures.
 * - Score direction: higher = better (100 = no meaningful stop-and-go signal), matching
 *   [FuelingScore]/`ElevationScore`'s convention rather than [DiscoveryScore]'s inverted one.
 * - A route with zero junctions, or junctions with zero net penalty, scores 100 - "no junctions"
 *   is one of the acceptance criteria's own test categories, implying a concrete score rather
 *   than a missing/null one (same call as [SafetyScore]'s never-null shape).
 * - Calibration lives in named constants ([ZERO_SCORE_PENALTY_S_PER_100KM], [FLAG_THRESHOLD_S]),
 *   mirroring [FuelingScore]'s `BORDERLINE_MAX_COUNT`-style constants - retuned by editing and
 *   committing new values once real rides can be sanity-checked against them, not a
 *   runtime-adjustable settings surface.
 * - `braking_penalty_s` can be measured slightly negative for a calibrated junction type (e.g.
 *   `osm_priority`/"stop" averaged -0.48s in Ride-Graph#98's own sanity check) - that's
 *   measurement noise, not a real "credit". Per-junction penalty is floored at 0 so one
 *   junction's noise can't offset another's real cost.
 * - `stop_penalty_confidence`/`braking_penalty_confidence` are read onto [FlaggedJunction] but
 *   deliberately not weighted into the score - same "read but not weighted" call [SafetyScore]
 *   made for `hazardSource`. Both are `NULL` for the common `osm_priority`/`none` sources by
 *   design (a static heuristic has no confidence value to report), not a signal of "low
 *   confidence" - so a null value here is expected, not an error case.
 */
object OptimizationScore {

    /**
     * A junction's own penalty must exceed this many seconds to be flagged on the map - roughly
     * Ride-Graph#98's largest common per-type calibrated average (`osm_priority`/`traffic_signals`
     * = 5.11s), i.e. above what an ordinary signalized junction costs.
     */
    const val FLAG_THRESHOLD_S = 5.0

    /**
     * The per-100km total penalty-seconds at which the score bottoms out at 0. Chosen so a route
     * with mostly ordinary junctions (a few seconds each) still scores comfortably high, while one
     * with many high-penalty junctions per 100km (repeated traffic signals, frequent hard stops)
     * scores low.
     */
    const val ZERO_SCORE_PENALTY_S_PER_100KM = 300.0

    /** One junction whose own penalty exceeds [FLAG_THRESHOLD_S], for map display. */
    data class FlaggedJunction(
        val junctionNode: Long,
        val penaltyS: Double,
        val stopPenaltySource: String?,
        val brakingPenaltySource: String?,
        val brakingPenaltyConfidence: Double?,
    )

    data class Result(
        val score: Int,
        val penaltySPer100km: Double,
        val flaggedJunctions: List<FlaggedJunction>,
    )

    /** [GraphTurn.stopPenalty] + [GraphTurn.brakingPenaltyS], floored at 0 - see class doc. */
    private fun penaltySeconds(turn: GraphTurn): Double = (turn.stopPenalty + turn.brakingPenaltyS).coerceAtLeast(0.0)

    /**
     * [matchedDistanceM] normalizes the per-100km figure against the *matched* portion of the
     * route, mirroring [SafetyScore.compute]. Never null: "no junctions" or "no signal at any
     * junction" both resolve to a concrete score of 100, not a missing one.
     */
    fun compute(matchedTurns: List<GraphTurn>, matchedDistanceM: Double): Result {
        val totalPenaltyS = matchedTurns.sumOf(::penaltySeconds)
        val penaltySPer100km = if (matchedDistanceM <= 0.0) 0.0 else totalPenaltyS * 100_000.0 / matchedDistanceM
        val score = (100.0 - penaltySPer100km / ZERO_SCORE_PENALTY_S_PER_100KM * 100.0).coerceIn(0.0, 100.0).roundToInt()
        val flagged = matchedTurns
            .filter { penaltySeconds(it) > FLAG_THRESHOLD_S }
            .map { turn ->
                FlaggedJunction(
                    junctionNode = turn.junctionNode,
                    penaltyS = penaltySeconds(turn),
                    stopPenaltySource = turn.stopPenaltySource,
                    brakingPenaltySource = turn.brakingPenaltySource,
                    brakingPenaltyConfidence = turn.brakingPenaltyConfidence,
                )
            }
        return Result(score = score, penaltySPer100km = penaltySPer100km, flaggedJunctions = flagged)
    }
}

/** Qualitative label for [OptimizationScore.Result.score] - same 4-tier shape as [FuelingBucket]/[DiscoveryBucket]. */
enum class OptimizationBucket(val label: String) {
    HEAVY_STOP_AND_GO("Heavy stop-and-go"),
    FREQUENT_STOPS("Frequent stops"),
    MINOR_STOPS("Minor stop-and-go"),
    EFFICIENT("Efficient");

    companion object {
        fun forScore(score: Int): OptimizationBucket = when {
            score <= 24 -> HEAVY_STOP_AND_GO
            score <= 49 -> FREQUENT_STOPS
            score <= 74 -> MINOR_STOPS
            else -> EFFICIENT
        }
    }
}
