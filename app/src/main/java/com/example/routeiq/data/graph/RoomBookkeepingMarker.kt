package com.example.routeiq.data.graph

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room requires at least one declared `@Entity`. The real graph tables
 * (`map_nodes`, `map_edges`, ...) are owned by Ride-Graph and queried only
 * through [GraphAssetDao]'s raw queries, since their exact schema isn't
 * known here - so this unused marker table exists purely to satisfy that
 * requirement without Room attempting schema validation against a real
 * table it doesn't fully understand. Room creates this table (harmlessly,
 * alongside the real ones) the first time it opens the copied asset file.
 */
@Entity(tableName = "route_iq_room_bookkeeping")
internal data class RoomBookkeepingMarker(
    @PrimaryKey val id: Int = 0,
)
