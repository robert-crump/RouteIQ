package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphNode

/**
 * Resolves each [flaggedJunctions] entry to its coordinates via [nodes] (from
 * [com.example.routeiq.data.graph.GraphAssetRepository.getNodesNear]) - a junction whose node
 * isn't present in [nodes] (e.g. outside the queried box) is silently dropped rather than
 * crashing, same defensive shape as [safetyMarkers].
 */
fun optimizationMarkers(flaggedJunctions: List<OptimizationScore.FlaggedJunction>, nodes: List<GraphNode>): List<GeoPoint> {
    if (flaggedJunctions.isEmpty() || nodes.isEmpty()) return emptyList()
    val pointsByNode = nodes.associate { it.id to it.point }
    return flaggedJunctions.mapNotNull { pointsByNode[it.junctionNode] }
}
