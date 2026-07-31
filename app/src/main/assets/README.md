# Bundled map graph asset

Drop the Ride-Graph export here as `cycling_graph.db`.

`GraphDatabase` (see `com.example.routeiq.data.graph`) opens this file at app
startup via Room's `createFromAsset("cycling_graph.db")`. It expects a
Room-compatible SQLite file with (at least) the following tables:
`map_nodes`, `map_edges`, `map_turns`, `pois`, `metadata`, `corridors`,
`corridor_connectors`, `nodes_rtree`.

This file is gitignored (`app/src/main/assets/*.db`) and never committed -
it's ~220MB, well past what belongs in this repo's git history. Copy the
real export from Ride-Graph/Velometrics into this directory yourself before
running the app — without it, `GraphDatabase.getInstance()` will throw when
it tries to open the asset.
