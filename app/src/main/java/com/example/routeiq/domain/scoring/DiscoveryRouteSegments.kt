package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.matching.matchedRouteSegments
import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphEdge

/** Which side of [GraphEdge.isTraversed] a [DiscoverySegment] falls on. */
enum class DiscoveryTraversal { TRAVERSED, UNDISCOVERED }

/** One decoded polyline segment for the discovery map overlay, tagged by traversal status. */
data class DiscoverySegment(val points: List<GeoPoint>, val traversal: DiscoveryTraversal)

/**
 * Splits [matchedEdges] into traversed/undiscovered polyline segments for the discovery map
 * overlay (issue #11's resolved design) - parity with [fuelingGapSegments]/[safetyMarkers]/
 * [optimizationMarkers], which each have their own dedicated, unit-testable overlay-resolution
 * function rather than the split living inline in the screen (as it did pre-#11, calling
 * [matchedRouteSegments] separately on `matchedEdges.partition { it.isTraversed }`'s two halves).
 * Same edges-with-no-usable-geometry-are-dropped contract as [matchedRouteSegments] itself.
 */
fun discoverySegments(matchedEdges: List<GraphEdge>): List<DiscoverySegment> {
    val (traversed, undiscovered) = matchedEdges.partition { it.isTraversed }
    return matchedRouteSegments(traversed).map { DiscoverySegment(it, DiscoveryTraversal.TRAVERSED) } +
        matchedRouteSegments(undiscovered).map { DiscoverySegment(it, DiscoveryTraversal.UNDISCOVERED) }
}
