package com.example.routeiq.domain.matching

import com.example.routeiq.data.graph.GraphAssetRepository
import com.example.routeiq.domain.model.BoundingBox
import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GpxTrack
import com.example.routeiq.domain.model.GraphEdge
import com.example.routeiq.domain.model.GraphNode
import com.example.routeiq.domain.model.GraphTurn

/**
 * Snaps an imported [GpxTrack] onto the bundled map graph, producing a single connected, ordered
 * [GraphEdge] sequence plus turns and distance coverage - the representation every downstream
 * scoring dimension (issue #1's RouteRatingEngine) will consume.
 *
 * Ported from Velometrics' `domain/service/MapMatcher.kt`: greedy nearest-edge snapping via
 * [RTreeSpatialIndex] (rejecting perpendicular spurs/reverse twins by GPS heading), anchor-by-
 * GPS-order pruning (only runs of consecutive snaps with enough points count as anchors), bounded
 * local-adjacency-graph gap repair, and leaf-edge pruning to drop dead-end spurs the greedy snap
 * picked up. [matchTrackChunked]'s per-chunk matching is Velometrics' own design for `.gpx` import
 * (see its doc comment there) rather than the single-shot `matchTrack` clustering uses, because a
 * single unmatchable stretch shouldn't blank the whole result - exactly issue #4's "partial
 * coverage" requirement.
 *
 * What's new here (no Velometrics equivalent): [match] wraps that ported core with an explicit
 * [MatchResult.OutsideCoverage] state - Velometrics has no such distinction (`matchTrack` simply
 * returns null on any failure) - using [GraphAssetRepository.getBounds] for a cheap pre-check
 * before touching the (500K+ edge) graph at all.
 */
class RouteGraphMatcher(private val repository: GraphAssetRepository) {

    private data class Run(val edgeIdx: Int, val pointCount: Int)

    companion object {
        // Ported from Velometrics' CyclingConstants (INTERVAL_*/GPX_ANALYSIS_MATCH_CHUNK_M) - kept
        // as the same values since they were tuned against the same bundled graph.
        private const val EDGE_SNAP_RADIUS_M = 20.0
        private const val MATCH_MAX_REPAIR_DEPTH = 6
        private const val MATCH_MIN_ANCHOR_POINTS = 2
        private const val SNAP_BEARING_MAX_DIFF_DEG = 45.0
        private const val MATCH_CHUNK_M = 5_000.0

        // Not from Velometrics: its region-load buffer reused INTERVAL_EDGE_SNAP_RADIUS_M
        // (20m) for both the DB slice window and the per-point RTree query radius. That's too
        // tight for the DB slice: a graph node can legitimately sit a bit further from the
        // nearest GPS sample than the snap radius (samples don't land exactly on every node),
        // and an edge whose *only* geometry is a from/to node fallback (empty geometryEncoded)
        // would then get corrupted (0,0) geometry if that node fell just outside the loaded
        // slice. Widening just the region-load window (not the snap radius, which stays 20m and
        // still drives the heading-rejection tuning) avoids that without changing match behavior.
        private const val REGION_LOAD_BUFFER_M = 200.0
    }

    /**
     * Matches [track] against the bundled graph. See [MatchResult] for what each outcome means.
     * [chunkSizeM] defaults to Velometrics' tuned [MATCH_CHUNK_M] - callers shouldn't normally
     * override it; it's exposed mainly so tests can exercise partial-coverage chunking at a
     * scale smaller than a real 5km chunk.
     */
    suspend fun match(track: GpxTrack, chunkSizeM: Double = MATCH_CHUNK_M): MatchResult {
        val points = track.points
        if (points.size < 2) {
            return MatchResult.OutsideCoverage("Route has too few points to match.")
        }

        val trackBounds = GeoUtils.computeBoundingBox(points, EDGE_SNAP_RADIUS_M)
        val graphBounds = repository.getBounds()
        if (graphBounds == null || !trackBounds.overlaps(graphBounds)) {
            return MatchResult.OutsideCoverage("This route falls outside the bundled map graph's covered territory.")
        }

        val chunked = matchTrackChunked(points, chunkSizeM)
        if (chunked.matchedEdges.isEmpty()) {
            return MatchResult.OutsideCoverage("No part of this route could be matched to the bundled graph's road network.")
        }

        val turnLookupBounds = GeoUtils.computeBoundingBox(points, REGION_LOAD_BUFFER_M)
        return MatchResult.Matched(
            matchedEdges = chunked.matchedEdges,
            matchedTurns = lookupMatchedTurns(chunked.matchedEdges, turnLookupBounds),
            totalDistanceM = chunked.totalDistanceM,
            matchedDistanceM = chunked.matchedDistanceM,
        )
    }

    private data class ChunkedMatchResult(
        val matchedEdges: List<GraphEdge>,
        val totalDistanceM: Double,
        val matchedDistanceM: Double,
    )

    /**
     * Matches the track in [chunkSizeM]-long pieces instead of all at once: each chunk is matched
     * independently, so a stretch outside the graph's coverage just doesn't contribute instead of
     * blanking the whole result. Matched edges are deduped by node pair across chunk boundaries.
     */
    private suspend fun matchTrackChunked(points: List<GeoPoint>, chunkSizeM: Double = MATCH_CHUNK_M): ChunkedMatchResult {
        val matchedEdges = LinkedHashMap<Pair<Long, Long>, GraphEdge>()
        var totalDistanceM = 0.0
        var matchedDistanceM = 0.0
        for (chunk in splitIntoChunks(points, chunkSizeM)) {
            val chunkDistanceM = chunk.zipWithNext().sumOf { (a, b) -> GeoUtils.haversineDistance(a, b) }
            totalDistanceM += chunkDistanceM
            val edges = matchChunk(chunk)
            if (!edges.isNullOrEmpty()) {
                matchedDistanceM += chunkDistanceM
                for (edge in edges) matchedEdges.putIfAbsent(edge.fromNode to edge.toNode, edge)
            }
        }
        return ChunkedMatchResult(matchedEdges.values.toList(), totalDistanceM, matchedDistanceM)
    }

    /** Splits [points] into consecutive runs whose along-track length is roughly [chunkSizeM]. */
    private fun splitIntoChunks(points: List<GeoPoint>, chunkSizeM: Double): List<List<GeoPoint>> {
        val chunks = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf(points.first())
        var accumulated = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            current.add(curr)
            accumulated += GeoUtils.haversineDistance(prev, curr)
            if (accumulated >= chunkSizeM && i != points.lastIndex) {
                chunks.add(current)
                current = mutableListOf(curr)
                accumulated = 0.0
            }
        }
        if (current.size >= 2) chunks.add(current)
        return chunks
    }

    /** Loads the graph slice near [points]' bounding box and matches just this one chunk against it. */
    private suspend fun matchChunk(points: List<GeoPoint>): List<GraphEdge>? {
        if (points.size < 2) return null

        val bbox = GeoUtils.computeBoundingBox(points, REGION_LOAD_BUFFER_M)
        val edges = repository.getEdgesNear(bbox)
        if (edges.size < 2) return null
        val nodes = repository.getNodesNear(bbox).associateBy { it.id }
        val adj = buildAdjacency(edges)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(edges, nodes)

        val snapped = snapPoints(points, index)
        val anchors = collapseConsecutive(snapped).filter { it.pointCount >= MATCH_MIN_ANCHOR_POINTS }
        if (anchors.isEmpty()) return null

        val sequence = dedupeConsecutive(anchors.map { it.edgeIdx })
        val repaired = repairGaps(sequence, adj, edges)?.let(::dedupeConsecutive) ?: return null

        val pruned = pruneLeafEdges(repaired, edges, nodes, points.first(), points.last())
        if (pruned.isEmpty()) return null

        return pruned.mapNotNull { edges.getOrNull(it) }.takeIf { it.size > 1 }
    }

    private fun buildAdjacency(edges: List<GraphEdge>): Map<Int, List<Int>> {
        val edgesByFromNode = mutableMapOf<Long, MutableList<Int>>()
        edges.forEachIndexed { idx, edge ->
            edgesByFromNode.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
        }
        val adj = mutableMapOf<Int, List<Int>>()
        edges.forEachIndexed { idx, edge ->
            val successors = edgesByFromNode[edge.toNode]?.filter { it != idx } ?: emptyList()
            if (successors.isNotEmpty()) adj[idx] = successors
        }
        return adj
    }

    /**
     * Snaps each point to the nearest in-radius edge whose direction is within
     * [SNAP_BEARING_MAX_DIFF_DEG] of the GPS heading at that point (rejecting perpendicular side
     * streets and reverse twins on two-way roads). If no candidate qualifies, falls back to the
     * nearest candidate by distance so a point is never dropped purely due to heading noise.
     */
    private suspend fun snapPoints(points: List<GeoPoint>, index: RTreeSpatialIndex): List<Int?> {
        val headings = computeHeadings(points)
        return points.mapIndexed { i, point ->
            val candidates = index.queryEdgesNear(point.latitude, point.longitude, EDGE_SNAP_RADIUS_M)
            selectSnapCandidate(candidates, headings[i])?.edgeKey?.toInt()
        }
    }

    /**
     * Picks the nearest [candidates] (pre-sorted by distance) whose bearing is within
     * [SNAP_BEARING_MAX_DIFF_DEG] of [heading]. Falls back to the nearest candidate by distance
     * if [heading] is undefined or no candidate qualifies.
     */
    internal fun selectSnapCandidate(
        candidates: List<RTreeSpatialIndex.EdgeCandidate>,
        heading: Double?,
    ): RTreeSpatialIndex.EdgeCandidate? {
        val withinBearing = heading?.let { h ->
            candidates.firstOrNull { GeoUtils.bearingDifference(it.bearingDeg, h) <= SNAP_BEARING_MAX_DIFF_DEG }
        }
        return withinBearing ?: candidates.firstOrNull()
    }

    /**
     * GPS heading at each point as the bearing across a small surrounding window, damping
     * single-point jitter. Null where the window collapses to a single coordinate.
     */
    internal fun computeHeadings(points: List<GeoPoint>): List<Double?> {
        val windowRadius = 2
        return points.indices.map { i ->
            val start = points[maxOf(0, i - windowRadius)]
            val end = points[minOf(points.lastIndex, i + windowRadius)]
            if (start.latitude == end.latitude && start.longitude == end.longitude) {
                null
            } else {
                GeoUtils.computeBearing(start.latitude, start.longitude, end.latitude, end.longitude)
            }
        }
    }

    private fun collapseConsecutive(snapped: List<Int?>): List<Run> {
        val runs = mutableListOf<Run>()
        for (idx in snapped) {
            if (idx == null) continue
            val last = runs.lastOrNull()
            if (last != null && last.edgeIdx == idx) {
                runs[runs.lastIndex] = last.copy(pointCount = last.pointCount + 1)
            } else {
                runs.add(Run(idx, 1))
            }
        }
        return runs
    }

    private fun dedupeConsecutive(sequence: List<Int>): List<Int> {
        val result = mutableListOf<Int>()
        for (idx in sequence) {
            if (result.lastOrNull() != idx) result.add(idx)
        }
        return result
    }

    /**
     * Walks the snapped sequence, splicing in a short connecting path (via [findConnectingPath])
     * wherever consecutive edges aren't graph-adjacent. Returns null if any gap can't be bridged
     * within [MATCH_MAX_REPAIR_DEPTH] hops.
     *
     * If the only connecting path starts by reversing the preceding edge - a side-street snap
     * that would create a U-turn loop - that snap is removed and the gap is re-bridged from its
     * predecessor. If the retry also fails, the track is rejected.
     */
    private fun repairGaps(sequence: List<Int>, adj: Map<Int, List<Int>>, edges: List<GraphEdge>): List<Int>? {
        if (sequence.size <= 1) return sequence

        val result = mutableListOf(sequence.first())
        for (i in 1 until sequence.size) {
            val prev = result.last()
            val curr = sequence[i]
            if (prev == curr || adj[prev]?.contains(curr) == true) {
                result.add(curr)
                continue
            }
            val connector = findConnectingPath(prev, curr, adj) ?: return null
            val firstStep = connector.firstOrNull()
            val isUTurn = firstStep != null &&
                edges.getOrNull(firstStep)?.toNode == edges.getOrNull(prev)?.fromNode
            if (isUTurn) {
                result.removeLastOrNull() ?: return null
                val newPrev = result.lastOrNull() ?: return null
                if (newPrev == curr || adj[newPrev]?.contains(curr) == true) {
                    result.add(curr)
                } else {
                    val retryConnector = findConnectingPath(newPrev, curr, adj) ?: return null
                    result.addAll(retryConnector)
                    result.add(curr)
                }
            } else {
                result.addAll(connector)
                result.add(curr)
            }
        }
        return result
    }

    /** Bounded BFS over [adj] returning the intermediate edge indices between [from] and [to] (exclusive). */
    private fun findConnectingPath(from: Int, to: Int, adj: Map<Int, List<Int>>): List<Int>? {
        if (from == to) return emptyList()

        val queue = ArrayDeque<Int>()
        val cameFrom = mutableMapOf<Int, Int>()
        val visited = mutableSetOf(from)
        queue.add(from)

        var depth = 0
        while (queue.isNotEmpty() && depth < MATCH_MAX_REPAIR_DEPTH) {
            repeat(queue.size) {
                val current = queue.removeFirst()
                for (succ in adj[current].orEmpty()) {
                    if (!visited.add(succ)) continue
                    cameFrom[succ] = current
                    if (succ == to) return reconstructIntermediates(succ, from, cameFrom)
                    queue.add(succ)
                }
            }
            depth++
        }
        return null
    }

    /**
     * Removes edges that are topological "leaves" - one endpoint connects to no other edge in the
     * matched set, forming a dead-end branch. Degree is counted over the undirected graph (an
     * anti-parallel pair, e.g. an out-and-back spur, collapses to a single connection), so a
     * dead-end node touched only by such a pair is still recognized as degree-1. The nodes
     * nearest to the track's first and last points are protected so the route's true start/end is
     * never pruned. Runs iteratively until stable.
     */
    private fun pruneLeafEdges(
        sequence: List<Int>,
        edges: List<GraphEdge>,
        nodes: Map<Long, GraphNode>,
        startPoint: GeoPoint,
        endPoint: GeoPoint,
    ): List<Int> {
        if (sequence.size <= 1) return sequence

        val matchedNodeIds = sequence.flatMapTo(mutableSetOf()) { idx -> listOf(edges[idx].fromNode, edges[idx].toNode) }
        val protectedNodes = setOfNotNull(
            nearestNodeId(startPoint, matchedNodeIds, nodes),
            nearestNodeId(endPoint, matchedNodeIds, nodes),
        )

        val kept = sequence.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            val degree = undirectedDegree(kept, edges)
            val iter = kept.iterator()
            while (iter.hasNext()) {
                val idx = iter.next()
                val edge = edges[idx]
                val fromLeaf = (degree[edge.fromNode] ?: 0) == 1 && edge.fromNode !in protectedNodes
                val toLeaf = (degree[edge.toNode] ?: 0) == 1 && edge.toNode !in protectedNodes
                if (fromLeaf || toLeaf) {
                    iter.remove()
                    changed = true
                }
            }
        }
        return kept
    }

    /**
     * Node degree over the undirected graph formed by [sequence]: each edge's endpoints are
     * collapsed to an unordered pair, so an anti-parallel pair (A->B and B->A) counts as a single
     * connection between A and B.
     */
    private fun undirectedDegree(sequence: List<Int>, edges: List<GraphEdge>): Map<Long, Int> {
        val pairs = sequence.mapTo(mutableSetOf()) { idx ->
            val edge = edges[idx]
            if (edge.fromNode <= edge.toNode) edge.fromNode to edge.toNode else edge.toNode to edge.fromNode
        }
        val degree = mutableMapOf<Long, Int>()
        for ((a, b) in pairs) {
            degree[a] = (degree[a] ?: 0) + 1
            degree[b] = (degree[b] ?: 0) + 1
        }
        return degree
    }

    private fun nearestNodeId(point: GeoPoint, nodeIds: Set<Long>, nodes: Map<Long, GraphNode>): Long? =
        nodeIds
            .mapNotNull { id -> nodes[id]?.let { node -> id to GeoUtils.haversineDistance(point, node.point) } }
            .minByOrNull { it.second }
            ?.first

    private fun reconstructIntermediates(to: Int, from: Int, cameFrom: Map<Int, Int>): List<Int> {
        val path = mutableListOf<Int>()
        var node: Int? = to
        while (node != null && node != from) {
            if (node != to) path.add(0, node)
            node = cameFrom[node]
        }
        return path
    }

    /** Looks up [GraphTurn]s for every junction between consecutive matched edges that actually share a node. */
    private suspend fun lookupMatchedTurns(
        edges: List<GraphEdge>,
        searchBounds: BoundingBox,
    ): List<GraphTurn> {
        if (edges.size < 2) return emptyList()

        val triples = mutableListOf<Triple<Long, Long, Long>>()
        for (i in 0 until edges.size - 1) {
            val a = edges[i]
            val b = edges[i + 1]
            val junction = sharedNode(a, b) ?: continue
            triples.add(Triple(otherNode(a, junction), junction, otherNode(b, junction)))
        }
        if (triples.isEmpty()) return emptyList()

        val candidates = repository.getTurnsNear(searchBounds)
        val byTriple = candidates.associateBy { Triple(it.fromNode, it.junctionNode, it.toNode) }
        return triples.mapNotNull { byTriple[it] }
    }

    private fun sharedNode(a: GraphEdge, b: GraphEdge): Long? =
        listOf(a.fromNode, a.toNode).firstOrNull { it == b.fromNode || it == b.toNode }

    private fun otherNode(edge: GraphEdge, node: Long): Long = if (edge.fromNode == node) edge.toNode else edge.fromNode
}
