package com.example.routeiq.data.graph

/**
 * The tables present in Ride-Graph's exports. The app bundles `route_iq.db` (issue #13), a
 * trimmed export that drops `corridors`/`corridor_connectors` (route-search-only, nothing here
 * reads them) - [GraphAssetRepository.getGraphStats] already degrades those two counts to `null`
 * on a missing table rather than crashing, so this enum still lists them for that debug screen.
 *
 * Kept as an enum (rather than raw strings) so callers can't build a
 * [GraphAssetDao] raw query against an arbitrary/unsanitized table name.
 */
enum class GraphTable(val tableName: String) {
    MAP_NODES("map_nodes"),
    MAP_EDGES("map_edges"),
    MAP_TURNS("map_turns"),
    POIS("pois"),
    METADATA("metadata"),
    CORRIDORS("corridors"),
    CORRIDOR_CONNECTORS("corridor_connectors"),
    NODES_RTREE("nodes_rtree"),
}
