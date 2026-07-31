package com.example.routeiq.data.graph

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * Read-only wrapper around the bundled Ride-Graph map export.
 *
 * No entities are declared: the database is pre-populated by Ride-Graph and
 * opened as-is via [createFromAsset], so there's nothing for Room to create
 * or migrate. [GraphAssetDao] only exposes raw SELECT access - this class
 * has no path to writing to the asset.
 */
@Database(entities = [RoomBookkeepingMarker::class], version = 1, exportSchema = false)
abstract class GraphDatabase : RoomDatabase() {
    abstract fun graphAssetDao(): GraphAssetDao

    companion object {
        const val ASSET_FILE_NAME = "cycling_graph.db"

        @Volatile
        private var instance: GraphDatabase? = null

        /** Opens the app's bundled [ASSET_FILE_NAME] asset, creating it on first call. */
        fun getInstance(context: Context): GraphDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GraphDatabase::class.java,
                    ASSET_FILE_NAME,
                )
                    .createFromAsset(ASSET_FILE_NAME)
                    .build()
                    .also { instance = it }
            }

        /** Opens an arbitrary, already-populated SQLite file directly - used by tests. */
        fun forDatabaseFile(context: Context, databaseFile: File): GraphDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GraphDatabase::class.java,
                databaseFile.absolutePath,
            ).build()
    }
}
