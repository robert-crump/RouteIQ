package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GraphTurn
import kotlin.math.roundToInt

/**
 * Junction-only hazard read of a route (issue #9), resolved via a `/grill-me` session (see issue
 * #9's comment thread) grounded in the real bundled `cycling_graph.db` and Ride-Graph's own
 * `hazard_scorer.py` source. Key decisions:
 *
 * - Edge-level highway-tag filtering (the issue's original `HazardFilter` ask) is dropped
 *   entirely - the ported motorway/trunk exclusion set matches zero edges in the bundled graph,
 *   and `hazard_score` already factors in the *crossing* road's class at the junctions that
 *   matter, so there's nothing left to rate about the road being ridden.
 * - Flag threshold is any `hazard_score > 0` - not an arbitrary cutoff: `hazard_scorer.py`'s own
 *   priority-tag gate (no `give_way`/`stop`/`traffic_signals` -> exactly `0.0`) already does the
 *   real filtering.
 * - Severity tiers anticipate Ride-Graph#100's proposed 1.0 cap on `hazard_score` - on today's
 *   pre-recalibration data (observed max 0.2651 in the bundled graph), every flagged junction
 *   falls in [Tier.LOW]. That's expected, not a bug, until the graph is re-exported.
 * - [GraphTurn.hazardSource] is read onto [FlaggedJunction] but deliberately not weighted into
 *   the tier or displayed - it reflects the approach-speed *input*'s provenance (measured vs.
 *   modeled), not a second severity axis.
 * - No synthesized 0-100 composite score, same call as [ElevationScore] - the card built on this
 *   reports the actual flagged count, that count per 100km, and a per-tier breakdown instead.
 */
object SafetyScore {

    /** Buffer for the [com.example.routeiq.data.graph.GraphAssetRepository.getNodesNear] query that resolves marker coordinates - flagged junctions sit exactly on the matched route, so this only needs to cover GPS/snap jitter, not a detour corridor. */
    const val JUNCTION_LOOKUP_BUFFER_M = 50.0

    enum class Tier(val label: String) {
        LOW("Low"),
        MEDIUM("Medium"),
        HIGH("High");

        companion object {
            /** LOW (0, 0.33], MEDIUM (0.33, 0.67], HIGH (0.67, +] - anticipates Ride-Graph#100's proposed 1.0 cap. */
            fun forHazardScore(hazardScore: Double): Tier = when {
                hazardScore > 0.67 -> HIGH
                hazardScore > 0.33 -> MEDIUM
                else -> LOW
            }
        }
    }

    /** One flagged (`hazardScore > 0`) junction crossing along the matched route. */
    data class FlaggedJunction(
        val junctionNode: Long,
        val hazardScore: Double,
        val hazardSource: String?,
        val tier: Tier,
    )

    data class Result(
        val flaggedJunctions: List<FlaggedJunction>,
        val flaggedPer100km: Int,
        val lowCount: Int,
        val mediumCount: Int,
        val highCount: Int,
    ) {
        val totalFlaggedCount: Int get() = flaggedJunctions.size
    }

    /**
     * [matchedDistanceM] normalizes the per-100km figure against the *matched* portion of the
     * route (mirrors [com.example.routeiq.domain.matching.MatchResult.Matched.matchedDistanceM]),
     * not the raw track's full distance - [matchedTurns] only exist for the matched portion in
     * the first place. Never null: unlike [DiscoveryScore]'s zero-length case, "no flagged
     * junctions" is itself a real, displayable answer, not a missing one.
     */
    fun compute(matchedTurns: List<GraphTurn>, matchedDistanceM: Double): Result {
        val flagged = matchedTurns
            .filter { it.hazardScore > 0.0 }
            .map { turn -> FlaggedJunction(turn.junctionNode, turn.hazardScore, turn.hazardSource, Tier.forHazardScore(turn.hazardScore)) }
        val perTier = flagged.groupingBy { it.tier }.eachCount()
        val flaggedPer100km = if (matchedDistanceM <= 0.0) 0 else (flagged.size * 100_000.0 / matchedDistanceM).roundToInt()
        return Result(
            flaggedJunctions = flagged,
            flaggedPer100km = flaggedPer100km,
            lowCount = perTier[Tier.LOW] ?: 0,
            mediumCount = perTier[Tier.MEDIUM] ?: 0,
            highCount = perTier[Tier.HIGH] ?: 0,
        )
    }
}
