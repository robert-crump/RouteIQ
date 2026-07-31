package com.example.routeiq.domain.matching

import com.example.routeiq.domain.model.GraphEdge
import com.example.routeiq.domain.model.GraphTurn
import kotlin.math.roundToInt

/** Result of [RouteGraphMatcher.match]. */
sealed interface MatchResult {

    /**
     * A connected, ordered edge/turn sequence was produced for at least part of the track.
     * [coveragePercent] reflects how much of the *imported* track's own distance ended up
     * matched - it can be well under 100 for a track that's only partially within the graph's
     * road network, per issue #4's acceptance criteria (partial matches must report an accurate
     * percentage, not fail silently).
     */
    data class Matched(
        val matchedEdges: List<GraphEdge>,
        val matchedTurns: List<GraphTurn>,
        val totalDistanceM: Double,
        val matchedDistanceM: Double,
    ) : MatchResult {
        val coveragePercent: Int
            get() = if (totalDistanceM <= 0.0) 0 else (100 * matchedDistanceM / totalDistanceM).roundToInt().coerceIn(0, 100)
    }

    /**
     * No usable match could be produced - either the track's bounding box doesn't overlap the
     * bundled graph's covered territory at all, or nothing along it snapped to the graph's road
     * network. Distinguished from a low-[Matched.coveragePercent] result so the UI can show a
     * clear "outside covered territory" state instead of a misleadingly empty/zero score.
     */
    data class OutsideCoverage(val reason: String) : MatchResult
}
