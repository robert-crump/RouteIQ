package com.example.routeiq.domain.matching

import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphEdge
import com.example.routeiq.domain.model.GraphNode
import com.github.davidmoten.rtree2.Entries
import com.github.davidmoten.rtree2.RTree
import com.github.davidmoten.rtree2.geometry.Geometries
import com.github.davidmoten.rtree2.geometry.Rectangle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ported verbatim from Velometrics' `domain/service/RTreeSpatialIndex.kt`, swapping MapLibre's
 * `LatLng` for [GeoPoint]. Bulk-loads a static R-tree (via the same `com.github.davidmoten:rtree2`
 * library) over each edge's decoded polyline bounding box, then answers "which edges are near this
 * point" with per-segment perpendicular distance + bearing so [RouteGraphMatcher] can snap GPS
 * points to the right edge (and reject perpendicular spurs/reverse twins by heading).
 */
class RTreeSpatialIndex {

    private var tree: RTree<Long, Rectangle> = RTree.create()
    private val mutex = Mutex()

    // Decoded polyline geometry per edge, indexed by edgeKey (== edge index). Decoded once per
    // rebuildIndex so per-point queries don't re-decode the same polylines repeatedly.
    private var edgeGeometries: List<List<GeoPoint>> = emptyList()

    data class EdgeCandidate(
        val edgeKey: Long,
        val distanceM: Double,
        val bearingDeg: Double,
    )

    suspend fun rebuildIndex(edges: List<GraphEdge>, nodes: Map<Long, GraphNode>) {
        mutex.withLock {
            edgeGeometries = edges.map { edge -> decodeGeometry(edge, nodes) }

            // Bulk-load via STR rather than sequential .add() calls: the latter clones and
            // re-splits the immutable tree on every insert, which OOMs on large road graphs.
            val entries = edgeGeometries.mapIndexed { index, geometry ->
                Entries.entry(index.toLong(), createBoundingBox(geometry))
            }
            tree = RTree.create(entries)
        }
    }

    suspend fun queryEdgesNear(lat: Double, lon: Double, radiusM: Double): List<EdgeCandidate> {
        val latDelta = GeoUtils.metersToLat(radiusM)
        val lonDelta = GeoUtils.metersToLon(radiusM, lat)

        val searchBox = Geometries.rectangle(
            lon - lonDelta,
            lat - latDelta,
            lon + lonDelta,
            lat + latDelta,
        )

        return mutex.withLock {
            tree.search(searchBox)
                .map { entry ->
                    val edgeKey = entry.value()
                    val geometry = edgeGeometries[edgeKey.toInt()]
                    val (distance, bearing) = nearestSegment(lat, lon, geometry)
                    EdgeCandidate(edgeKey, distance, bearing)
                }
                .toList()
                .sortedBy { it.distanceM }
        }
    }

    /**
     * Finds the segment of [geometry] closest to (lat, lon) and returns its perpendicular
     * distance and bearing. [geometry] always has at least 2 points (see [decodeGeometry]).
     */
    private fun nearestSegment(lat: Double, lon: Double, geometry: List<GeoPoint>): Pair<Double, Double> {
        var bestDist = Double.MAX_VALUE
        var bestBearing = 0.0
        for (i in 0 until geometry.size - 1) {
            val p1 = geometry[i]
            val p2 = geometry[i + 1]
            val dist = GeoUtils.pointToSegmentDistance(lat, lon, p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            if (dist < bestDist) {
                bestDist = dist
                bestBearing = GeoUtils.computeBearing(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            }
        }
        return bestDist to bestBearing
    }

    /** Decodes an edge's polyline geometry, falling back to the straight fromNode-toNode line. */
    private fun decodeGeometry(edge: GraphEdge, nodes: Map<Long, GraphNode>): List<GeoPoint> {
        val decoded = edge.geometryEncoded?.let { PolylineDecoder.decode(it) } ?: emptyList()
        if (decoded.size >= 2) return decoded

        val fromNode = nodes[edge.fromNode]
        val toNode = nodes[edge.toNode]
        return if (fromNode != null && toNode != null) {
            listOf(fromNode.point, toNode.point)
        } else {
            listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0))
        }
    }

    private fun createBoundingBox(geometry: List<GeoPoint>): Rectangle {
        val minLat = geometry.minOf { it.latitude }
        val maxLat = geometry.maxOf { it.latitude }
        val minLon = geometry.minOf { it.longitude }
        val maxLon = geometry.maxOf { it.longitude }

        // Ensure non-zero area
        val epsilon = 0.00001
        val finalMaxLat = if (maxLat == minLat) maxLat + epsilon else maxLat
        val finalMaxLon = if (maxLon == minLon) maxLon + epsilon else maxLon

        return Geometries.rectangle(minLon, minLat, finalMaxLon, finalMaxLat)
    }
}
