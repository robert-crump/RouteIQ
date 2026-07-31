# Route IQ

![License](https://img.shields.io/badge/license-MIT-blue.svg)

## About

Route IQ is an Android app that rates `.gpx` cycling routes before you ride them — either for **fueling** (how well the route is served by resupply points like food/water stops) or for **optimization potential** (flagging segments with a high probability of unplanned stops or hard braking).

It's being spun out of [Velometrics](https://github.com/robert-crump/Velometrics), which is narrowing its own scope to analyzing past ride sessions. Route creation/generation is separately moving to a standalone Python web app. Route IQ owns the third piece: rating a candidate route before you commit to riding it.

## Planned rating dimensions

- **Fueling** — resupply availability (POI density: food, water, cafes) along the route
- **Optimization** — stop-probability / braking-probability at junctions along the route
- **Elevation / climb difficulty** — grade profile, total climb, steepness
- **Safety / hazard** — road-type and hazard exposure along the route
- **Discovery** — how much of the route is new terrain vs. already-ridden roads

Output is multiple sub-scores plus an annotated map view flagging the specific segments driving each score — not a single composite number.

## Data source

Route IQ consumes map-graph data (OSM-derived nodes, edges, and turns, with calibrated stop-penalty, braking-probability, and POI data) exported as a Room-compatible SQLite database by [Ride-Graph](https://github.com/robert-crump/Ride-Graph), a separate Python project that builds this graph from OpenStreetMap data and the rider's own ride history. Route IQ v1 is scoped to the map coverage Ride-Graph already has for the rider's home region.

## Status

Early planning / scaffolding stage. No rating logic has been implemented yet.

## Built With

- Kotlin / Jetpack Compose (Android)

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

## Development

This project was developed with assistance from [Claude Code](https://claude.ai/code) by Anthropic.
