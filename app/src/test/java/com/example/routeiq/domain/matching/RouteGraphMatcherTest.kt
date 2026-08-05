package com.example.routeiq.domain.matching

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.routeiq.data.graph.GraphAssetRepository
import com.example.routeiq.data.graph.GraphDatabase
import com.example.routeiq.domain.model.BoundingBox
import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GpxTrack
import com.example.routeiq.domain.model.GraphEdge
import com.example.routeiq.domain.model.GraphNode
import com.example.routeiq.domain.model.GraphTurn
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Ported from Velometrics' `domain/service/MapMatcherTest.kt` (see
 * C:\Users\bob22\StudioProjects\Velometrics), adapted from a fake in-memory `MapGraphRepository`
 * to a real SQLite fixture read through [GraphAssetRepository] - the same swap
 * `GraphAssetRepositoryTest` made for the graph-stats tests. Same node/edge coordinates as the
 * original tests, since they were already tuned to exercise each scenario (heading rejection,
 * fork disambiguation, out-and-back peeling, etc.).
 *
 * The "outside coverage" tests (no Velometrics equivalent) exercise issue #4's own addition on
 * top of the ported core: [MatchResult.OutsideCoverage] for tracks whose bounding box doesn't
 * overlap the bundled graph's covered territory, or that overlap it but snap to no nearby edges.
 */
@RunWith(RobolectricTestRunner::class)
class RouteGraphMatcherTest {

    private lateinit var databaseFile: File
    private lateinit var database: GraphDatabase
    private lateinit var repository: GraphAssetRepository
    private lateinit var matcher: RouteGraphMatcher

    @Before
    fun setUp() {
        databaseFile = File.createTempFile("cycling_graph_matcher_fixture", ".db")
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        databaseFile.delete()
    }

    private fun node(id: Long, lat: Double, lon: Double) = GraphNode(id, GeoPoint(lat, lon))

    private fun edge(from: GraphNode, to: GraphNode): GraphEdge {
        val lengthM = GeoUtils.haversineDistance(from.point, to.point)
        return GraphEdge(
            fromNode = from.id, toNode = to.id,
            lengthM = lengthM, highway = "residential", name = null,
            isTraversed = false, geometryEncoded = "",
        )
    }

    /** Round-trips [GraphEdge.speedMedianKmh]/[speedMeanKmh] back into the `metadata` JSON blob column - none of these matching tests set either, so this is null for all of them today. */
    private fun edgeMetadataJson(e: GraphEdge): String? {
        if (e.speedMedianKmh == null && e.speedMeanKmh == null) return null
        val fields = buildList {
            e.speedMedianKmh?.let { add(""""speed_median": $it""") }
            e.speedMeanKmh?.let { add(""""speed_mean": $it""") }
        }
        return "{${fields.joinToString(", ")}}"
    }

    /** A GPX point at the given latitude, fixed longitude - matches the ported tests' north-bound chains. */
    private fun pt(lat: Double, lon: Double = 6.0800): GeoPoint = GeoPoint(lat, lon)

    private fun track(points: List<GeoPoint>) = GpxTrack(name = "test", points = points)

    private val defaultBounds = BoundingBox(minLat = 50.0, minLon = 5.5, maxLat = 51.5, maxLon = 6.5)

    private fun seed(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        turns: List<GraphTurn> = emptyList(),
        bounds: BoundingBox = defaultBounds,
    ) {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL("CREATE TABLE map_nodes (id INTEGER PRIMARY KEY, lat REAL, lon REAL)")
            db.execSQL(
                "CREATE TABLE map_edges (from_node INTEGER, to_node INTEGER, length_m REAL, highway TEXT, " +
                    "name TEXT, is_traversed INTEGER NOT NULL DEFAULT 0, geometry_encoded TEXT, slope_percent REAL, metadata TEXT)",
            )
            db.execSQL(
                "CREATE TABLE map_turns (from_node INTEGER, junction_node INTEGER, to_node INTEGER, " +
                    "hazard_score REAL, hazard_source TEXT, stop_penalty REAL, stop_penalty_source TEXT, " +
                    "braking_probability REAL, median_ke_delta REAL, stop_penalty_confidence REAL, " +
                    "braking_penalty_s REAL, braking_penalty_source TEXT, braking_penalty_confidence REAL)",
            )
            db.execSQL("CREATE TABLE metadata (bbox_south REAL, bbox_west REAL, bbox_north REAL, bbox_east REAL)")

            for (n in nodes) {
                db.execSQL("INSERT INTO map_nodes (id, lat, lon) VALUES (?, ?, ?)", arrayOf(n.id, n.point.latitude, n.point.longitude))
            }
            for (e in edges) {
                db.execSQL(
                    "INSERT INTO map_edges (from_node, to_node, length_m, highway, name, is_traversed, geometry_encoded, slope_percent, metadata) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        e.fromNode, e.toNode, e.lengthM, e.highway, e.name, if (e.isTraversed) 1 else 0, e.geometryEncoded,
                        e.slopePercent, edgeMetadataJson(e),
                    ),
                )
            }
            for (t in turns) {
                db.execSQL(
                    "INSERT INTO map_turns (from_node, junction_node, to_node, hazard_score, hazard_source, " +
                        "stop_penalty, stop_penalty_source, braking_probability, median_ke_delta, stop_penalty_confidence, " +
                        "braking_penalty_s, braking_penalty_source, braking_penalty_confidence) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        t.fromNode, t.junctionNode, t.toNode, t.hazardScore, t.hazardSource,
                        t.stopPenalty, t.stopPenaltySource, t.brakingProbability, t.medianKeDelta, t.stopPenaltyConfidence,
                        t.brakingPenaltyS, t.brakingPenaltySource, t.brakingPenaltyConfidence,
                    ),
                )
            }
            db.execSQL(
                "INSERT INTO metadata (bbox_south, bbox_west, bbox_north, bbox_east) VALUES (?, ?, ?, ?)",
                arrayOf(bounds.minLat, bounds.minLon, bounds.maxLat, bounds.maxLon),
            )
        }
        database = GraphDatabase.forDatabaseFile(ApplicationProvider.getApplicationContext(), databaseFile)
        repository = GraphAssetRepository(database.graphAssetDao())
        matcher = RouteGraphMatcher(repository)
    }

    @Test
    fun `clean track snaps to a fully connected edge sequence`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        val n2 = node(2, 50.7820, 6.0800)
        val n3 = node(3, 50.7830, 6.0800)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        seed(listOf(n0, n1, n2, n3), listOf(edge0, edge1, edge2))

        val gpx = track(
            listOf(
                pt(50.7802), pt(50.7805), pt(50.7808),
                pt(50.7812), pt(50.7815), pt(50.7818),
                pt(50.7822), pt(50.7825), pt(50.7828),
            ),
        )

        val result = matcher.match(gpx)

        assertTrue("expected a match, got $result", result is MatchResult.Matched)
        assertEquals(listOf(edge0, edge1, edge2), (result as MatchResult.Matched).matchedEdges)
        assertEquals(100, result.coveragePercent)
    }

    @Test
    fun `noisy track with a missed middle edge is repaired via local adjacency search`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        val n2 = node(2, 50.7820, 6.0800)
        val n3 = node(3, 50.7830, 6.0800)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        seed(listOf(n0, n1, n2, n3), listOf(edge0, edge1, edge2))

        // GPS noise causes a gap: no points land near edge1's stretch, jumping straight from
        // edge0's territory to edge2's - the snapped sequence is [edge0, edge2], a one-hop gap
        // that local adjacency search should bridge by splicing edge1 back in.
        val gpx = track(
            listOf(
                pt(50.7802), pt(50.7805), pt(50.7808),
                pt(50.7822), pt(50.7825), pt(50.7828),
            ),
        )

        val result = matcher.match(gpx)

        assertTrue(result is MatchResult.Matched)
        assertEquals(listOf(edge0, edge1, edge2), (result as MatchResult.Matched).matchedEdges)
    }

    @Test
    fun `snap rejects a perpendicular spur near a junction and keeps the along-travel edge`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        val n2 = node(2, 50.7820, 6.0800)
        val n3 = node(3, 50.7830, 6.0800)
        // Dead-end spur running east from n1, very close to the track but perpendicular to travel.
        val nSpur = node(4, 50.7810, 6.0801)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        val edgeSpur = edge(n1, nSpur)
        seed(listOf(n0, n1, n2, n3, nSpur), listOf(edge0, edge1, edge2, edgeSpur))

        // Travels north along edge0/edge1/edge2. The two points near n1 sit closer to the
        // perpendicular spur than to edge1, but the GPS heading is northbound throughout.
        val gpx = track(
            listOf(
                pt(50.7802), pt(50.7805), pt(50.7808),
                pt(50.781005, 6.080020), pt(50.781010, 6.080020),
                pt(50.7815), pt(50.7818),
                pt(50.7822), pt(50.7825), pt(50.7828),
            ),
        )

        val result = matcher.match(gpx)

        assertTrue(result is MatchResult.Matched)
        assertEquals(listOf(edge0, edge1, edge2), (result as MatchResult.Matched).matchedEdges)
    }

    @Test
    fun `single-point lateral spur is dropped from the matched sequence`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        val n2 = node(2, 50.7820, 6.0800)
        val n3 = node(3, 50.7830, 6.0800)
        // Dead-end node far enough east that only the spur edge is within snap radius there.
        val nSpur = node(4, 50.7810, 6.0810)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        val edgeSpur = edge(n1, nSpur)
        seed(listOf(n0, n1, n2, n3, nSpur), listOf(edge0, edge1, edge2, edgeSpur))

        // A single point sits right on the spur, isolated from the rest of the track.
        val gpx = track(
            listOf(
                pt(50.7802), pt(50.7805), pt(50.7808),
                pt(50.7810, 6.0810),
                pt(50.7812), pt(50.7815), pt(50.7818),
                pt(50.7822), pt(50.7825), pt(50.7828),
            ),
        )

        val result = matcher.match(gpx)

        assertTrue(result is MatchResult.Matched)
        assertEquals(listOf(edge0, edge1, edge2), (result as MatchResult.Matched).matchedEdges)
    }

    @Test
    fun `a single-point connector dropped by the anchor floor is restored as a bridge`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        val n2 = node(2, 50.7820, 6.0800)
        val n3 = node(3, 50.7830, 6.0800)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        seed(listOf(n0, n1, n2, n3), listOf(edge0, edge1, edge2))

        // edge1 gets exactly one snapped point (below MATCH_MIN_ANCHOR_POINTS), so it's dropped
        // as an anchor - but repairGaps must re-insert it to bridge edge0 -> edge2.
        val gpx = track(
            listOf(
                pt(50.7802), pt(50.7805), pt(50.7808),
                pt(50.7815),
                pt(50.7822), pt(50.7825), pt(50.7828),
            ),
        )

        val result = matcher.match(gpx)

        assertTrue(result is MatchResult.Matched)
        assertEquals(listOf(edge0, edge1, edge2), (result as MatchResult.Matched).matchedEdges)
    }

    @Test
    fun `at a fork the high-count branch is kept and the low-count branch is dropped`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        val n2 = node(2, 50.7820, 6.0800)
        val n3 = node(3, 50.7830, 6.0800)
        // A low-count branch off n1, nearly parallel to edge1 but geometrically closer to one of
        // the GPS points near the fork.
        val nLow = node(4, 50.7820, 6.0803)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        val edgeLow = edge(n1, nLow)
        seed(listOf(n0, n1, n2, n3, nLow), listOf(edge0, edge1, edge2, edgeLow))

        // One point near the fork sits closer to edgeLow's line than to edge1's, but edge1
        // collects far more points overall, so it's kept as the anchor and edgeLow is dropped.
        val gpx = track(
            listOf(
                pt(50.7802), pt(50.7805), pt(50.7808),
                pt(50.7813, 6.08015),
                pt(50.7815), pt(50.7817), pt(50.7818),
                pt(50.7822), pt(50.7825), pt(50.7828),
            ),
        )

        val result = matcher.match(gpx)

        assertTrue(result is MatchResult.Matched)
        assertEquals(listOf(edge0, edge1, edge2), (result as MatchResult.Matched).matchedEdges)
    }

    @Test
    fun `an out-and-back spur collapses to a degree-1 leaf and is peeled`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        val n2 = node(2, 50.7820, 6.0800)
        // Dead-end node reached and left along the same physical edge (out-and-back).
        val nDead = node(3, 50.7805, 6.0820)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edgeOut = edge(n1, nDead)
        val edgeBack = edge(nDead, n1)
        seed(listOf(n0, n1, n2, nDead), listOf(edge0, edge1, edgeOut, edgeBack))

        // North along edge0, out to nDead and back (anti-parallel pair, each with >=2 points),
        // then continue north along edge1. nDead has no other connections, so the out-and-back
        // pair should collapse to a degree-1 leaf and be peeled, leaving edge0 -> edge1.
        val gpx = track(
            listOf(
                pt(50.7802), pt(50.7805), pt(50.7808),
                pt(50.7809, 6.0805), pt(50.7808, 6.0810), pt(50.7807, 6.0815),
                pt(50.7806, 6.0816), pt(50.7808, 6.0808), pt(50.7809, 6.0802),
                pt(50.7812), pt(50.7815), pt(50.7818),
            ),
        )

        val result = matcher.match(gpx)

        assertTrue(result is MatchResult.Matched)
        assertEquals(listOf(edge0, edge1), (result as MatchResult.Matched).matchedEdges)
    }

    @Test
    fun `track jumping between disconnected components reports outside coverage`() = runTest {
        // Component A (south) and component B (north) share no nodes/edges.
        val a0 = node(0, 50.7800, 6.0800)
        val a1 = node(1, 50.7810, 6.0800)
        val a2 = node(2, 50.7820, 6.0800)
        val b0 = node(10, 50.9000, 6.0800)
        val b1 = node(11, 50.9010, 6.0800)
        val b2 = node(12, 50.9020, 6.0800)
        val edgeA0 = edge(a0, a1)
        val edgeA1 = edge(a1, a2)
        val edgeB0 = edge(b0, b1)
        val edgeB1 = edge(b1, b2)
        seed(listOf(a0, a1, a2, b0, b1, b2), listOf(edgeA0, edgeA1, edgeB0, edgeB1))

        // Snaps onto component A, then teleports onto component B - no connecting path exists
        // within the repair bound, and it's a single short chunk so nothing else contributes.
        val gpx = track(listOf(pt(50.7802), pt(50.7805), pt(50.7808), pt(50.9012), pt(50.9015), pt(50.9018)))

        val result = matcher.match(gpx)

        assertTrue("expected OutsideCoverage, got $result", result is MatchResult.OutsideCoverage)
    }

    @Test
    fun `route entirely outside the bundled graph's covered territory is reported clearly`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        seed(listOf(n0, n1), listOf(edge(n0, n1)), bounds = BoundingBox(50.77, 6.07, 50.79, 6.09))

        // Nowhere near the bundled graph's covered territory at all.
        val gpx = track(listOf(GeoPoint(10.0, 10.0), GeoPoint(10.001, 10.0), GeoPoint(10.002, 10.0)))

        val result = matcher.match(gpx)

        assertTrue(result is MatchResult.OutsideCoverage)
    }

    @Test
    fun `route inside the graph's bbox but with no nearby roads reports outside coverage`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        // Bounds are broad enough to cover an area with no actual roads in it.
        seed(listOf(n0, n1), listOf(edge(n0, n1)), bounds = BoundingBox(50.0, 5.5, 51.5, 6.5))

        // Well within the overall bbox, but nowhere near the only two nodes that exist.
        val gpx = track(listOf(pt(51.2000, 6.2000), pt(51.2003, 6.2000), pt(51.2006, 6.2000)))

        val result = matcher.match(gpx)

        assertTrue("expected OutsideCoverage, got $result", result is MatchResult.OutsideCoverage)
    }

    @Test
    fun `partial coverage is reported as an accurate percentage rather than failing silently`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        val n2 = node(2, 50.7820, 6.0800)
        val n3 = node(3, 50.7830, 6.0800)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        seed(listOf(n0, n1, n2, n3), listOf(edge0, edge1, edge2))

        // The first stretch follows the real chain; the rest wanders ~2km east into an area
        // with no roads in the fixture at all - still within the graph's overall bbox, so this
        // must come back as a genuine partial Matched, not OutsideCoverage.
        val chainPoints = listOf(
            pt(50.7802), pt(50.7805), pt(50.7808),
            pt(50.7812), pt(50.7815), pt(50.7818),
            pt(50.7822), pt(50.7825), pt(50.7828),
        )
        val farPoints = (1..10).map { i -> pt(50.7828, 6.0800 + i * 0.003) }
        val gpx = track(chainPoints + farPoints)

        val result = matcher.match(gpx, chunkSizeM = 300.0)

        assertTrue("expected a partial match, got $result", result is MatchResult.Matched)
        val matched = result as MatchResult.Matched
        assertTrue("expected the real chain to be matched", matched.matchedEdges.containsAll(listOf(edge0, edge1, edge2)))
        assertTrue("expected coverage well under 100%, was ${matched.coveragePercent}", matched.coveragePercent in 1..70)
    }

    @Test
    fun `matched turns are looked up for junctions between consecutive matched edges`() = runTest {
        val n0 = node(0, 50.7800, 6.0800)
        val n1 = node(1, 50.7810, 6.0800)
        val n2 = node(2, 50.7820, 6.0800)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val turn = GraphTurn(
            fromNode = n0.id, junctionNode = n1.id, toNode = n2.id,
            hazardScore = 0.2, hazardSource = "predicted",
            stopPenalty = 0.5, stopPenaltySource = "predicted",
            brakingProbability = 0.1, medianKeDelta = null, stopPenaltyConfidence = 0.8,
        )
        seed(listOf(n0, n1, n2), listOf(edge0, edge1), turns = listOf(turn))

        val gpx = track(listOf(pt(50.7802), pt(50.7805), pt(50.7808), pt(50.7812), pt(50.7815), pt(50.7818)))

        val result = matcher.match(gpx)

        assertTrue(result is MatchResult.Matched)
        assertEquals(listOf(turn), (result as MatchResult.Matched).matchedTurns)
    }
}
