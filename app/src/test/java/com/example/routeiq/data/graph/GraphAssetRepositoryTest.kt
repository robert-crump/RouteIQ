package com.example.routeiq.data.graph

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.routeiq.domain.model.BoundingBox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Equivalent to Velometrics' `CyclingAssetDatabaseFixtureTest`: confirms a
 * Room-compatible SQLite file with the Ride-Graph export's table names opens
 * cleanly and that [GraphAssetRepository] can read row counts back out of
 * it. Builds its own tiny fixture DB rather than shipping a binary asset,
 * since the real `cycling_graph.db` export isn't available in this repo yet
 * (see app/src/main/assets/README.md).
 */
@RunWith(RobolectricTestRunner::class)
class GraphAssetRepositoryTest {

    private lateinit var databaseFile: File
    private lateinit var database: GraphDatabase
    private lateinit var repository: GraphAssetRepository

    @Before
    fun setUp() {
        databaseFile = File.createTempFile("cycling_graph_fixture", ".db")
        seedFixtureDatabase(databaseFile)

        database = GraphDatabase.forDatabaseFile(ApplicationProvider.getApplicationContext(), databaseFile)
        repository = GraphAssetRepository(database.graphAssetDao())
    }

    @After
    fun tearDown() {
        database.close()
        databaseFile.delete()
    }

    @Test
    fun `graph stats reflect fixture row counts`() = runBlocking {
        val stats = repository.getGraphStats()

        assertEquals(3L, stats.nodeCount)
        assertEquals(5L, stats.edgeCount)
        assertEquals(2L, stats.turnCount)
        assertEquals(4L, stats.poiCount)
        assertEquals(1L, stats.metadataCount)
        assertEquals(2L, stats.corridorCount)
        assertEquals(1L, stats.corridorConnectorCount)
        assertEquals(3L, stats.nodesRtreeCount)
    }

    @Test
    fun `rowCount queries an individual table`() = runBlocking {
        assertEquals(5L, repository.rowCount(GraphTable.MAP_EDGES))
    }

    @Test
    fun `getPoisNear returns only pois inside the box, with real column names mapped`() = runBlocking {
        val pois = repository.getPoisNear(BoundingBox(minLat = -1.0, minLon = -1.0, maxLat = 1.0, maxLon = 1.0))

        assertEquals(1, pois.size)
        val poi = pois.first()
        assertEquals("poi-1", poi.poiId)
        assertEquals("Trailside Cafe", poi.name)
        assertEquals("cafe", poi.category)
        assertEquals("coffee_shop", poi.cuisine)
        assertEquals(0.5, poi.location.latitude, 1e-9)
        assertEquals(0.5, poi.location.longitude, 1e-9)
        assertEquals("Mo-Fr 08:00-18:00", poi.openingHours)
    }

    @Test
    fun `metadata rows are read regardless of column shape`() = runBlocking {
        val rows = repository.getMetadataRows()

        assertEquals(1, rows.size)
        assertEquals("2026-07-01T00:00:00Z", rows.first()["export_timestamp"])
        assertEquals("home-region", rows.first()["region"])
    }

    private fun seedFixtureDatabase(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE map_nodes (id INTEGER PRIMARY KEY, lat REAL, lon REAL)")
            db.execSQL(
                "CREATE TABLE map_edges (id INTEGER PRIMARY KEY, from_node INTEGER, to_node INTEGER, " +
                    "is_traversed INTEGER, slope_percent REAL, surface TEXT, metadata TEXT)",
            )
            db.execSQL(
                "CREATE TABLE map_turns (id INTEGER PRIMARY KEY, node_id INTEGER, stop_penalty REAL, " +
                    "braking_probability REAL, stop_penalty_confidence REAL, hazard_score REAL, hazard_source TEXT)",
            )
            db.execSQL(
                "CREATE TABLE pois (poi_id TEXT PRIMARY KEY, name TEXT, category TEXT, cuisine TEXT, " +
                    "lat REAL, lon REAL, opening_hours TEXT)",
            )
            db.execSQL("CREATE TABLE metadata (export_timestamp TEXT, region TEXT)")
            db.execSQL("CREATE TABLE corridors (id INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("CREATE TABLE corridor_connectors (id INTEGER PRIMARY KEY, corridor_id INTEGER, node_id INTEGER)")
            // Not declared as a real `rtree` virtual table: the repository only needs the
            // table name to count rows, and rtree module availability shouldn't gate this test.
            db.execSQL("CREATE TABLE nodes_rtree (id INTEGER PRIMARY KEY, minX REAL, maxX REAL, minY REAL, maxY REAL)")

            repeat(3) { i -> db.execSQL("INSERT INTO map_nodes (id, lat, lon) VALUES ($i, 0.0, 0.0)") }
            repeat(5) { i -> db.execSQL("INSERT INTO map_edges (id, from_node, to_node, is_traversed) VALUES ($i, 0, 1, 0)") }
            repeat(2) { i -> db.execSQL("INSERT INTO map_turns (id, node_id) VALUES ($i, 0)") }
            db.execSQL(
                "INSERT INTO pois (poi_id, name, category, cuisine, lat, lon, opening_hours) VALUES " +
                    "('poi-1', 'Trailside Cafe', 'cafe', 'coffee_shop', 0.5, 0.5, 'Mo-Fr 08:00-18:00')",
            )
            repeat(3) { i -> db.execSQL("INSERT INTO pois (poi_id, category, lat, lon) VALUES ('far-$i', 'fuel', 50.0, 50.0)") }
            db.execSQL("INSERT INTO metadata (export_timestamp, region) VALUES ('2026-07-01T00:00:00Z', 'home-region')")
            repeat(2) { i -> db.execSQL("INSERT INTO corridors (id, name) VALUES ($i, 'corridor-$i')") }
            db.execSQL("INSERT INTO corridor_connectors (id, corridor_id, node_id) VALUES (0, 0, 0)")
            repeat(3) { i -> db.execSQL("INSERT INTO nodes_rtree (id, minX, maxX, minY, maxY) VALUES ($i, 0.0, 0.0, 0.0, 0.0)") }
        }
    }
}
