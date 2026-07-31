package com.example.routeiq.data.graph

import android.database.sqlite.SQLiteException
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getStringOrNull
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.routeiq.domain.model.BoundingBox
import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphEdge
import com.example.routeiq.domain.model.GraphNode
import com.example.routeiq.domain.model.GraphTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Row counts across the bundled map graph's tables. Individual counts are
 * null if that table couldn't be queried - e.g. `nodes_rtree` is a SQLite
 * `rtree`-module virtual table, and not every SQLite build has that module
 * compiled in, so one unsupported table shouldn't blank out the rest.
 */
data class GraphStats(
    val nodeCount: Long?,
    val edgeCount: Long?,
    val turnCount: Long?,
    val poiCount: Long?,
    val metadataCount: Long?,
    val corridorCount: Long?,
    val corridorConnectorCount: Long?,
    val nodesRtreeCount: Long?,
)

/**
 * Basic read queries over the bundled map graph asset, for downstream
 * matching/scoring modules (RouteGraphMatcher, RouteRatingEngine, ...) to
 * build on.
 */
class GraphAssetRepository(private val dao: GraphAssetDao) {

    suspend fun getGraphStats(): GraphStats = withContext(Dispatchers.IO) {
        GraphStats(
            nodeCount = rowCountOrNull(GraphTable.MAP_NODES),
            edgeCount = rowCountOrNull(GraphTable.MAP_EDGES),
            turnCount = rowCountOrNull(GraphTable.MAP_TURNS),
            poiCount = rowCountOrNull(GraphTable.POIS),
            metadataCount = rowCountOrNull(GraphTable.METADATA),
            corridorCount = rowCountOrNull(GraphTable.CORRIDORS),
            corridorConnectorCount = rowCountOrNull(GraphTable.CORRIDOR_CONNECTORS),
            nodesRtreeCount = rowCountOrNull(GraphTable.NODES_RTREE),
        )
    }

    /** Throws if the table can't be queried - use [getGraphStats] for a best-effort read of all tables. */
    suspend fun rowCount(table: GraphTable): Long =
        withContext(Dispatchers.IO) { rowCountBlocking(table) }

    private fun rowCountOrNull(table: GraphTable): Long? =
        try {
            rowCountBlocking(table)
        } catch (e: SQLiteException) {
            null
        }

    /** Reads every row of `metadata` as column-name -> string-value, whatever its actual shape. */
    suspend fun getMetadataRows(): List<Map<String, String?>> = withContext(Dispatchers.IO) {
        dao.rawQuery(SimpleSQLiteQuery("SELECT * FROM ${GraphTable.METADATA.tableName}")).use { cursor ->
            val columnNames = cursor.columnNames
            buildList {
                while (cursor.moveToNext()) {
                    add(columnNames.associateWith { name -> cursor.getString(cursor.getColumnIndexOrThrow(name)) })
                }
            }
        }
    }

    private fun rowCountBlocking(table: GraphTable): Long =
        dao.rawQuery(SimpleSQLiteQuery("SELECT COUNT(*) FROM ${table.tableName}")).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    /** The bundled graph's overall coverage box (Ride-Graph's export bbox), or null if `metadata` is empty. */
    suspend fun getBounds(): BoundingBox? = withContext(Dispatchers.IO) {
        dao.rawQuery(
            SimpleSQLiteQuery("SELECT bbox_south, bbox_west, bbox_north, bbox_east FROM ${GraphTable.METADATA.tableName} LIMIT 1"),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                BoundingBox(
                    minLat = cursor.getDouble(0),
                    minLon = cursor.getDouble(1),
                    maxLat = cursor.getDouble(2),
                    maxLon = cursor.getDouble(3),
                )
            }
        }
    }

    /** Nodes whose coordinates fall inside [box] - mirrors Velometrics' `MapNodeDao.getNear`. */
    suspend fun getNodesNear(box: BoundingBox): List<GraphNode> = withContext(Dispatchers.IO) {
        val sql = "SELECT id, lat, lon FROM ${GraphTable.MAP_NODES.tableName} " +
            "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"
        dao.rawQuery(SimpleSQLiteQuery(sql, arrayOf(box.minLat, box.maxLat, box.minLon, box.maxLon))).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(GraphNode(cursor.getLong(0), GeoPoint(cursor.getDouble(1), cursor.getDouble(2))))
                }
            }
        }
    }

    /**
     * Edges with at least one endpoint inside [box] - mirrors Velometrics' `MapEdgeDao.getNear`
     * (an OR on both endpoints, not AND, so edges crossing the box's boundary aren't dropped).
     */
    suspend fun getEdgesNear(box: BoundingBox): List<GraphEdge> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT e.from_node, e.to_node, e.length_m, e.highway, e.name, e.is_traversed, e.geometry_encoded
            FROM ${GraphTable.MAP_EDGES.tableName} e
            INNER JOIN ${GraphTable.MAP_NODES.tableName} nf ON e.from_node = nf.id
            INNER JOIN ${GraphTable.MAP_NODES.tableName} nt ON e.to_node = nt.id
            WHERE (nf.lat BETWEEN ? AND ? AND nf.lon BETWEEN ? AND ?)
               OR (nt.lat BETWEEN ? AND ? AND nt.lon BETWEEN ? AND ?)
        """.trimIndent()
        val args = arrayOf(box.minLat, box.maxLat, box.minLon, box.maxLon, box.minLat, box.maxLat, box.minLon, box.maxLon)
        dao.rawQuery(SimpleSQLiteQuery(sql, args)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        GraphEdge(
                            fromNode = cursor.getLong(0),
                            toNode = cursor.getLong(1),
                            lengthM = cursor.getDouble(2),
                            highway = cursor.getStringOrNull(3),
                            name = cursor.getStringOrNull(4),
                            isTraversed = cursor.getInt(5) != 0,
                            geometryEncoded = cursor.getStringOrNull(6),
                        ),
                    )
                }
            }
        }
    }

    /** Turns whose junction falls inside [box] - mirrors Velometrics' `MapTurnDao.getNear`. */
    suspend fun getTurnsNear(box: BoundingBox): List<GraphTurn> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT t.from_node, t.junction_node, t.to_node, t.hazard_score, t.hazard_source,
                   t.stop_penalty, t.stop_penalty_source, t.braking_probability, t.median_ke_delta, t.stop_penalty_confidence
            FROM ${GraphTable.MAP_TURNS.tableName} t
            INNER JOIN ${GraphTable.MAP_NODES.tableName} n ON t.junction_node = n.id
            WHERE n.lat BETWEEN ? AND ? AND n.lon BETWEEN ? AND ?
        """.trimIndent()
        dao.rawQuery(SimpleSQLiteQuery(sql, arrayOf(box.minLat, box.maxLat, box.minLon, box.maxLon))).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        GraphTurn(
                            fromNode = cursor.getLong(0),
                            junctionNode = cursor.getLong(1),
                            toNode = cursor.getLong(2),
                            hazardScore = cursor.getDouble(3),
                            hazardSource = cursor.getStringOrNull(4),
                            stopPenalty = cursor.getDouble(5),
                            stopPenaltySource = cursor.getStringOrNull(6),
                            brakingProbability = cursor.getDoubleOrNull(7),
                            medianKeDelta = cursor.getDoubleOrNull(8),
                            stopPenaltyConfidence = cursor.getDoubleOrNull(9),
                        ),
                    )
                }
            }
        }
    }
}
