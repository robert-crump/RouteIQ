package com.example.routeiq.domain.matching

import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphEdge

/**
 * Decodes each matched edge's own geometry into a renderable polyline, for drawing the matched
 * route distinctly from the raw imported track (issue #4's UI acceptance criterion). Each edge is
 * decoded independently rather than stitched into one continuous, direction-corrected path: a
 * plain stroke renders identically regardless of which end an edge's geometry starts from, so
 * there's nothing to gain from resolving travel direction here.
 */
fun matchedRouteSegments(edges: List<GraphEdge>): List<List<GeoPoint>> =
    edges.mapNotNull { edge ->
        edge.geometryEncoded
            ?.takeIf { it.isNotEmpty() }
            ?.let { PolylineDecoder.decode(it) }
            ?.takeIf { it.size >= 2 }
    }
