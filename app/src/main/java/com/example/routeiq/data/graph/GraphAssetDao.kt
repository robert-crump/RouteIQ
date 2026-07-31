package com.example.routeiq.data.graph

import android.database.Cursor
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Raw-query access to the bundled, externally-produced `cycling_graph.db`.
 *
 * The real schema (exact columns, types, indices) is owned by Ride-Graph and
 * isn't available to validate against at compile time, so this DAO
 * deliberately avoids Room `@Entity`/`@Query` (which requires the runtime
 * table schema to match the declared entity's schema exactly). `@RawQuery`
 * skips that validation and lets [GraphAssetRepository] build queries
 * against known table/column names instead.
 */
@Dao
interface GraphAssetDao {
    @RawQuery
    fun rawQuery(query: SupportSQLiteQuery): Cursor
}
