package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers issue #11's resolved design for [discoverySegments] - the discovery map overlay's own
 * unit-testable helper, parity with [fuelingGapSegments]/[safetyMarkers]/[optimizationMarkers]
 * (empty / fully-traversed / fully-untraversed / mixed matched edges).
 */
class DiscoveryRouteSegmentsTest {

    /** Google polyline algorithm (fixed 1e5 precision) encoder - the inverse of [com.example.routeiq.domain.matching.PolylineDecoder.decode], for building test fixtures. */
    private fun encodePolyline(points: List<GeoPoint>): String {
        val sb = StringBuilder()
        var prevLat = 0
        var prevLng = 0
        fun encodeValue(value: Int) {
            var v = value shl 1
            if (value < 0) v = v.inv()
            while (v >= 0x20) {
                sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
                v = v shr 5
            }
            sb.append((v + 63).toChar())
        }
        for (p in points) {
            val lat = Math.round(p.latitude * 1e5).toInt()
            val lng = Math.round(p.longitude * 1e5).toInt()
            encodeValue(lat - prevLat)
            encodeValue(lng - prevLng)
            prevLat = lat
            prevLng = lng
        }
        return sb.toString()
    }

    private fun edge(isTraversed: Boolean, points: List<GeoPoint>?): GraphEdge = GraphEdge(
        fromNode = 1, toNode = 2, lengthM = 100.0, highway = "residential", name = null,
        isTraversed = isTraversed, geometryEncoded = points?.let { encodePolyline(it) },
    )

    private val segmentA = listOf(GeoPoint(50.0, 6.0), GeoPoint(50.001, 6.0))
    private val segmentB = listOf(GeoPoint(51.0, 7.0), GeoPoint(51.001, 7.0))

    @Test
    fun `empty matched edges returns empty`() {
        assertEquals(emptyList<DiscoverySegment>(), discoverySegments(emptyList()))
    }

    @Test
    fun `fully traversed edges are all TRAVERSED segments`() {
        val edges = listOf(edge(true, segmentA), edge(true, segmentB))

        val segments = discoverySegments(edges)

        assertEquals(2, segments.size)
        assertTrue(segments.all { it.traversal == DiscoveryTraversal.TRAVERSED })
    }

    @Test
    fun `fully untraversed edges are all UNDISCOVERED segments`() {
        val edges = listOf(edge(false, segmentA), edge(false, segmentB))

        val segments = discoverySegments(edges)

        assertEquals(2, segments.size)
        assertTrue(segments.all { it.traversal == DiscoveryTraversal.UNDISCOVERED })
    }

    @Test
    fun `mixed edges split into TRAVERSED segments followed by UNDISCOVERED segments`() {
        val edges = listOf(edge(false, segmentA), edge(true, segmentB))

        val segments = discoverySegments(edges)

        assertEquals(2, segments.size)
        assertEquals(DiscoveryTraversal.TRAVERSED, segments[0].traversal)
        assertEquals(segmentB, segments[0].points)
        assertEquals(DiscoveryTraversal.UNDISCOVERED, segments[1].traversal)
        assertEquals(segmentA, segments[1].points)
    }

    @Test
    fun `edges with no usable geometry are dropped, same as matchedRouteSegments`() {
        val edges = listOf(
            edge(true, points = null),
            edge(true, points = listOf(GeoPoint(50.0, 6.0))), // single-point geometry, decodes to <2 points
            edge(true, segmentA),
        )

        val segments = discoverySegments(edges)

        assertEquals(1, segments.size)
        assertEquals(segmentA, segments[0].points)
    }
}
