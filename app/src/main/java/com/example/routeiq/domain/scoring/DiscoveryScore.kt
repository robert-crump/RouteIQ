package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GraphEdge
import kotlin.math.roundToInt

/**
 * Discovery score (0-100): the length-weighted percentage of [matchedEdges] where
 * `isTraversed == false`, i.e. roads not yet ridden according to the graph's traversal history.
 * Returns null if [matchedEdges] is empty or has zero total length (nothing to score) - ported
 * from Velometrics' `GpxAnalysisUtils.discoveryScore`, minus its `surrogates` parameter (a
 * speed/power-estimate mechanism unrelated to discovery, deferred to the future DurationEstimate
 * work that actually needs it).
 */
object DiscoveryScore {

    fun compute(matchedEdges: List<GraphEdge>): Int? {
        val totalLengthM = matchedEdges.sumOf { it.lengthM }
        if (totalLengthM <= 0.0) return null
        val undiscoveredLengthM = matchedEdges.filterNot { it.isTraversed }.sumOf { it.lengthM }
        return (100 * undiscoveredLengthM / totalLengthM).roundToInt().coerceIn(0, 100)
    }
}

/** Qualitative read of a [DiscoveryScore.compute] result, for display alongside the raw percentage. */
enum class DiscoveryBucket(val label: String) {
    FAMILIAR("Familiar"),
    SOME_NEW("Some new"),
    MOSTLY_NEW("Mostly new"),
    ALL_NEW("All new");

    companion object {
        fun forScore(score: Int): DiscoveryBucket = when {
            score <= 24 -> FAMILIAR
            score <= 49 -> SOME_NEW
            score <= 74 -> MOSTLY_NEW
            else -> ALL_NEW
        }
    }
}
