package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphNode

/** A flagged junction resolved to its map coordinates, for drawing as a point marker. */
data class SafetyMarker(val point: GeoPoint, val tier: SafetyScore.Tier)

/**
 * Resolves each [flaggedJunctions] entry to its coordinates via [nodes] (from
 * [com.example.routeiq.data.graph.GraphAssetRepository.getNodesNear]) - a junction whose node
 * isn't present in [nodes] (e.g. outside the queried box) is silently dropped rather than
 * crashing, same defensive shape as [matchedRouteSegments][com.example.routeiq.domain.matching.matchedRouteSegments]
 * skipping edges with no usable geometry.
 */
fun safetyMarkers(flaggedJunctions: List<SafetyScore.FlaggedJunction>, nodes: List<GraphNode>): List<SafetyMarker> {
    if (flaggedJunctions.isEmpty() || nodes.isEmpty()) return emptyList()
    val pointsByNode = nodes.associate { it.id to it.point }
    return flaggedJunctions.mapNotNull { junction ->
        pointsByNode[junction.junctionNode]?.let { SafetyMarker(it, junction.tier) }
    }
}
