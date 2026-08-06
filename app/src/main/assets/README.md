# Bundled map graph asset

Drop the Ride-Graph export here as `route_iq.db`.

`GraphDatabase` (see `com.example.routeiq.data.graph`) opens this file at app
startup via Room's `createFromAsset("route_iq.db")`. It expects a
Room-compatible SQLite file with (at least) the following tables:
`map_nodes`, `map_edges`, `map_turns`, `pois`, `metadata`, `nodes_rtree`.
(Ride-Graph's trimmed `route_iq.db` export has no `corridors`/
`corridor_connectors` tables - nothing in this repo reads them.)

This file is gitignored (`app/src/main/assets/route_iq.db`) and never
committed - it's ~220MB, well past what belongs in this repo's git history.
Copy the real export from Ride-Graph into this directory yourself before
running the app — without it, `GraphDatabase.getInstance()` will throw when
it tries to open the asset.

`route_iq_fixture.db` (~48KB) sits alongside it and **is** committed - a
small, real Ride-Graph export (`tests/fixtures/route_iq_fixture.db` from
Ride-Graph#99) used by `GraphAssetFixtureTest` to open the actual trimmed
schema through Room, mirroring Velometrics'
`cycling_graph_fixture.db`/`CyclingAssetDatabaseFixtureTest` pattern.
Regenerate it by copying Ride-Graph's `tests/fixtures/route_iq_fixture.db`
here whenever `ROUTE_IQ_SCHEMA_VERSION` bumps.
