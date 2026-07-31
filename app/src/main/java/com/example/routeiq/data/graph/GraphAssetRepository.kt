package com.example.routeiq.data.graph

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SimpleSQLiteQuery
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
}
