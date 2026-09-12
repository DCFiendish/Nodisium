# Real-border node/territory painting for the Agadir Crisis map

Classifies the real Underilla-downloaded Europe map (`server/nodisium-data/world`, loaded by
`AgadirWorld.kt`) into `nodes` territories using actual country border polygons and the real
terrain's own biome data — not hand-tuned coordinate boxes. Supersedes the abandoned WorldPainter
pipeline (see `docs/HANDOFF.md` for that history) for the border/territory-painting task
specifically; WorldPainter itself was a terrain generator and is unrelated to this once the real
downloaded map replaced it as the terrain source.

## Pipeline

1. **`fit_transform.py`** — fits a bilinear `blockX = a*lon + b*lat + c` / `blockZ = d*lon + e*lat
   + f` transform (least squares) from 5 real anchor points (city centers + the Straits of
   Gibraltar/Messina) with known real lon/lat and known in-game block coordinates. Writes
   `transform.json`. Only needs re-running if the map is re-imported at a different position/scale
   — the checked-in `transform.json` is already fit to the current `nodisium-data/world`.
2. **`paint_borders.py`** — for each of the 10 launch nations (`borders/*.geo.json`, real country
   border polygons), transforms the polygon into block space via `transform.json`, then for every
   chunk inside the polygon's bounding box does a real point-in-polygon test AND reads the actual
   biome from the real `.mca` region files (via the `anvil` library) to exclude ocean. Drops
   components under 10 chunks (point-in-polygon + per-chunk ocean exclusion produces small noise
   islands otherwise). Writes `nation_chunks.json` (per-nation real chunk lists).
3. **`build_real_nodes.py`** — subdivides each nation's chunk set into ~4-chunk-radius territories
   (nearest-seed Voronoi + 2 rounds of Lloyd relaxation, per connected component so no territory
   spans open water), builds `world.json` in `Deserializer`'s exact schema, and writes both
   `world.json` and `towns.json` straight into `server/nodisium-data/nodes/` (backing up whatever
   was there first). **Nations pre-reserve their territories** (`Nation.reservedTerritories`) —
   no placeholder towns are created, matching the "nation can exist and reserve territory ahead of
   any town" feature so a real town (e.g. via the planned Discord bot) can claim already-earmarked
   land later.

Re-run just `python build_real_nodes.py` to regenerate `world.json`/`towns.json` from the already-
computed `nation_chunks.json` (e.g. after tuning `AVERAGE_RADIUS`). Re-run the whole chain only if
the real map or the border polygons change.

## Known gaps

- **Land inside the crop box not covered by one of the 10 nations' borders is left with no
  territory at all** (not painted as "Wilderness" either) — deliberately skipped rather than
  running a full ~165k-chunk biome scan of the whole box for territories nobody can claim yet.
  Painting that in later is the same per-landmass Voronoi approach, just against the leftover land.
- Small islands/exclaves right at a border (a country's own outlying islands, enclaves) can land on
  the wrong side of the `MIN_COMPONENT_SIZE = 10` cutoff in `paint_borders.py` — check the printed
  per-country component counts if a bordering nation's expected islands look thin.
- `AUT.geo.json`/`TUN.geo.json` are present but **not in `COUNTRIES`** in `paint_borders.py` —
  kept only so Austria/Tunisia land doesn't get misclassified into a neighboring launch nation by
  accident; add them to `COUNTRIES` if either is ever added as a real launch nation.
- The real map itself has an open, undiagnosed bug per `LAUNCH_CHECKLIST.md`: "several chunks on it
  are corrupted — root cause not yet diagnosed." This pipeline reads real biome data from that same
  map, so a corrupted chunk could misclassify as ocean/land — not specifically checked for here.
