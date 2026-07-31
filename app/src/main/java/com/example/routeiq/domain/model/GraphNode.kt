package com.example.routeiq.domain.model

/** A single node in the bundled map graph (`map_nodes`), identified by Ride-Graph's own node id. */
data class GraphNode(val id: Long, val point: GeoPoint)
