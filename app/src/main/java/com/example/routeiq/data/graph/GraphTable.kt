package com.example.routeiq.data.graph

/**
 * The tables present in the bundled `cycling_graph.db` Ride-Graph export.
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
