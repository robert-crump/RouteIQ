package com.example.routeiq.domain.matching

import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphEdge
import com.example.routeiq.domain.model.GraphNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported verbatim from Velometrics' `domain/service/RTreeSpatialIndexTest.kt`, swapping LatLng for GeoPoint. */
class RTreeSpatialIndexTest {

    private fun node(id: Long, lat: Double, lon: Double) = GraphNode(id, GeoPoint(lat, lon))

    private fun edge(from: GraphNode, to: GraphNode): GraphEdge {
        val lengthM = GeoUtils.haversineDistance(from.point, to.point)
        return GraphEdge(
            fromNode = from.id, toNode = to.id,
            lengthM = lengthM, highway = "residential", name = null,
            isTraversed = false, geometryEncoded = "",
        )
    }

    @Test
    fun `a point on a long edge ranks above a shorter edge with a closer bbox center`() = runTest {
        // Long edge: a horizontal segment the query point lies directly on.
        val a0 = node(0, 50.7800, 6.0800)
        val a1 = node(1, 50.7800, 6.1000)
        val longEdge = edge(a0, a1)

        // Short edge: its bbox center is much closer to the query point than the long edge's
        // bbox center (50.7800, 6.0900), but the point is not actually near its segment.
        val b0 = node(2, 50.7807, 6.0812)
        val b1 = node(3, 50.7809, 6.0812)
        val shortEdge = edge(b0, b1)

        val index = RTreeSpatialIndex()
        index.rebuildIndex(listOf(longEdge, shortEdge), mapOf(0L to a0, 1L to a1, 2L to b0, 3L to b1))

        // Point lies exactly on the long edge's line.
        val candidates = index.queryEdgesNear(50.7800, 6.0810, radiusM = 700.0)

        assertTrue("expected both edges within radius", candidates.size == 2)
        assertEquals(0L, candidates.first().edgeKey)
        assertEquals(0.0, candidates.first().distanceM, 0.5)
    }

    @Test
    fun `candidate bearing reflects the nearest segment's direction`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800) // due north of n0
        val northEdge = edge(n0, n1)

        val index = RTreeSpatialIndex()
        index.rebuildIndex(listOf(northEdge), mapOf(0L to n0, 1L to n1))

        val candidates = index.queryEdgesNear(50.7805, 6.0800, radiusM = 50.0)

        assertEquals(1, candidates.size)
        assertEquals(0.0, candidates.first().bearingDeg, 1.0)
    }
}
