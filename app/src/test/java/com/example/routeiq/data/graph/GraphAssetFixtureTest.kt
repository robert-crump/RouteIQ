package com.example.routeiq.data.graph

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.routeiq.domain.model.BoundingBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * Opens the checked-in fixture `route_iq_fixture.db` (Ride-Graph#99's trimmed export, regenerated
 * for issue #98's `braking_penalty_s` columns) through the real [GraphDatabase]/[GraphAssetDao]
 * stack via Room's `createFromAsset`, mirroring Velometrics'
 * `CyclingAssetDatabaseFixtureTest` - the precedent named on RouteIQ#13's remaining acceptance
 * criterion. Unlike [GraphAssetRepositoryTest]'s hand-built synthetic DB (which still stages
 * `corridors`/`corridor_connectors` tables that the real trimmed export no longer has), this test
 * exercises genuine Ride-Graph output: real column shape, real `map_edges` exclusions, and the
 * real absence of `corridors`/`corridor_connectors`.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GraphAssetFixtureTest {

    private fun openFixture(): GraphDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("fixture_route_iq_unit_test.db")
        return Room.databaseBuilder(context, GraphDatabase::class.java, "fixture_route_iq_unit_test.db")
            .createFromAsset("route_iq_fixture.db")
            .build()
    }

    @Test
    fun `graph stats reflect the real trimmed export - corridors tables are absent, not crashing`() = runBlocking {
        val database = openFixture()
        try {
            val repository = GraphAssetRepository(database.graphAssetDao())
            val stats = repository.getGraphStats()

            assertEquals(4L, stats.nodeCount)
            assertEquals(3L, stats.edgeCount)
            assertEquals(2L, stats.turnCount)
            assertEquals(2L, stats.poiCount)
            assertEquals(1L, stats.metadataCount)

            // route_iq.db is trimmed (Ride-Graph#99): corridors/corridor_connectors don't exist
            // in this export, and getGraphStats() must degrade those counts to null rather than
            // throw - the behavior this fixture test exists to pin down.
            assertNull("corridors table doesn't exist in the trimmed export", stats.corridorCount)
            assertNull("corridor_connectors table doesn't exist in the trimmed export", stats.corridorConnectorCount)
        } finally {
            database.close()
        }
    }

    @Test
    fun `metadata row is readable from the real export`() = runBlocking {
        val database = openFixture()
        try {
            val rows = GraphAssetRepository(database.graphAssetDao()).getMetadataRows()

            assertEquals(1, rows.size)
            assertTrue("real metadata row must carry a schema_version", rows.first()["schema_version"] != null)
        } finally {
            database.close()
        }
    }

    @Test
    fun `edges, turns, and pois are readable near the fixture's real bbox`() = runBlocking {
        val database = openFixture()
        try {
            val repository = GraphAssetRepository(database.graphAssetDao())
            // The fixture's real metadata bbox (tests/fixtures/route_iq_fixture.db, Ride-Graph#99).
            val box = BoundingBox(minLat = 50.774, minLon = 6.083, maxLat = 50.78, maxLon = 6.09)

            assertTrue("getEdgesNear must return rows over the fixture's real bbox", repository.getEdgesNear(box).isNotEmpty())
            assertTrue("getTurnsNear must return rows over the fixture's real bbox", repository.getTurnsNear(box).isNotEmpty())
            assertTrue("getPoisNear must return rows over the fixture's real bbox", repository.getPoisNear(box).isNotEmpty())
        } finally {
            database.close()
        }
    }
}
