"""
Subdivide the real per-nation chunk sets (nation_chunks.json, computed by paint_borders.py
against the real Underilla-downloaded map's actual biome data + real country border polygons)
into ~4-chunk-radius territories, and emit a real world.json/towns.json in the exact schema
net.aechronis.nodes.serdes.Deserializer expects.

Design choice: nations pre-reserve their territories (Nation.reservedTerritories) rather than
each getting a placeholder capital town -- this matches the just-shipped "nation can exist and
reserve territory ahead of any town" feature (see docs/HANDOFF.md), so a real town created later
(e.g. via the planned Discord bot) can claim territory that's already earmarked for its nation.
No towns are created here at all.

Deliberately skips subdividing "wilderness" (land inside the crop box not claimed by any of the
10 nations) -- that would need a full biome scan of the ~165k-chunk box, which is a real chunk of
extra runtime for territories nobody can claim yet anyway. Land outside a nation's real border
polygon is simply left with no territory (plain unclaimed land, same as any vanilla Nodes map
before painting reaches it) -- can be painted in later the same way this session did it.
"""
import json
import os
import random

HERE = os.path.dirname(os.path.abspath(__file__))
NATION_CHUNKS_PATH = os.path.join(HERE, "nation_chunks.json")
NODES_DATA_DIR = os.path.join(HERE, "..", "..", "server", "nodisium-data", "nodes")

AVERAGE_RADIUS = 4.0
CELL_AVG_AREA = 3.14159265 * AVERAGE_RADIUS * AVERAGE_RADIUS  # ~50 chunks
LLOYD_ITERATIONS = 2
SEED = 20260912

NATION_COLOR_RGB = {
    "Germany": [80, 80, 80], "France": [70, 110, 200], "UK": [200, 60, 60], "Spain": [220, 190, 60],
    "Morocco": [180, 120, 60], "Italy": [60, 160, 90], "Switzerland": [200, 60, 200], "Portugal": [90, 190, 190],
    "Netherlands": [230, 140, 30], "Belgium": [30, 30, 130],
}

DIRS = [(1, 0), (-1, 0), (0, 1), (0, -1)]


def connected_components(chunk_set):
    visited = set()
    comps = []
    for start in chunk_set:
        if start in visited:
            continue
        comp = [start]
        visited.add(start)
        stack = [start]
        while stack:
            cx, cz = stack.pop()
            for dx, dz in DIRS:
                nb = (cx + dx, cz + dz)
                if nb in chunk_set and nb not in visited:
                    visited.add(nb)
                    comp.append(nb)
                    stack.append(nb)
        comps.append(comp)
    return comps


def voronoi_partition(chunks, rng):
    """Nearest-seed partition with Lloyd relaxation, seeded from real member chunks
    (not the bbox) so seeds never land in a concave notch outside the shape."""
    n = len(chunks)
    npoints = max(1, round(n / CELL_AVG_AREA))
    if npoints <= 1:
        return [chunks]

    seeds = [chunks[i] for i in rng.sample(range(n), min(npoints, n))]

    assign = None
    for _ in range(LLOYD_ITERATIONS + 1):
        cellLists = [[] for _ in seeds]
        assign = {}
        for (x, z) in chunks:
            best_i, best_d = 0, None
            for i, (sx, sz) in enumerate(seeds):
                d = (x - sx) ** 2 + (z - sz) ** 2
                if best_d is None or d < best_d:
                    best_d, best_i = d, i
            cellLists[best_i].append((x, z))
            assign[(x, z)] = best_i
        # recompute seeds as centroids of actual member chunks (skip on last pass)
        new_seeds = []
        for i, cell in enumerate(cellLists):
            if not cell:
                new_seeds.append(seeds[i])
                continue
            avgx = sum(c[0] for c in cell) / len(cell)
            avgz = sum(c[1] for c in cell) / len(cell)
            # snap to nearest real member chunk
            best = min(cell, key=lambda c: (c[0] - avgx) ** 2 + (c[1] - avgz) ** 2)
            new_seeds.append(best)
        seeds = new_seeds

    cellLists = [[] for _ in seeds]
    for (x, z), i in assign.items():
        cellLists[i].append((x, z))
    return [c for c in cellLists if c]


def split_disconnected(cell):
    """A voronoi cell can end up split across a strait/gap -- break it back into
    connected pieces so no single territory silently spans open water."""
    return connected_components(set(cell))


def main():
    with open(NATION_CHUNKS_PATH) as f:
        nation_chunks = json.load(f)

    all_territories = []  # list of dict: nation, chunks (list of [x,z])

    for iso, data in nation_chunks.items():
        nation = data["name"]
        chunk_set = set(tuple(c) for c in data["chunks"])
        rng = random.Random(SEED + sum(ord(c) for c in iso))  # stable across runs; Python's hash() is randomized per-process

        components = connected_components(chunk_set)
        for comp in components:
            cells = voronoi_partition(comp, rng)
            for cell in cells:
                for piece in split_disconnected(cell):
                    all_territories.append({"nation": nation, "chunks": piece})

    # merge slivers (< 30% of average cell area) into the best-bordering same-nation territory
    MIN_SIZE = max(3, round(CELL_AVG_AREA * 0.3))
    chunk_to_idx = {}
    for i, t in enumerate(all_territories):
        for c in t["chunks"]:
            chunk_to_idx[c] = i

    merged_away = set()
    for i, t in enumerate(all_territories):
        if i in merged_away or len(t["chunks"]) >= MIN_SIZE:
            continue
        border_counts = {}
        for (x, z) in t["chunks"]:
            for dx, dz in DIRS:
                nb = (x + dx, z + dz)
                j = chunk_to_idx.get(nb)
                if j is not None and j != i and j not in merged_away and all_territories[j]["nation"] == t["nation"]:
                    border_counts[j] = border_counts.get(j, 0) + 1
        if border_counts:
            best_j = max(border_counts, key=border_counts.get)
            all_territories[best_j]["chunks"].extend(t["chunks"])
            for c in t["chunks"]:
                chunk_to_idx[c] = best_j
            merged_away.add(i)

    final = [t for i, t in enumerate(all_territories) if i not in merged_away]

    # ---- build neighbor graph across ALL territories (political border doesn't matter here) ----
    chunk_owner = {}
    for i, t in enumerate(final):
        for c in t["chunks"]:
            chunk_owner[c] = i

    neighbor_sets = [set() for _ in final]
    is_edge = [False] * len(final)
    for (x, z), i in chunk_owner.items():
        touched_non_land = False
        for dx, dz in DIRS:
            nb = (x + dx, z + dz)
            j = chunk_owner.get(nb)
            if j is None:
                touched_non_land = True
            elif j != i:
                neighbor_sets[i].add(j)
        if touched_non_land:
            is_edge[i] = True

    # ---- greedy 6-coloring (cosmetic) ----
    colors = [0] * len(final)
    for i in range(len(final)):
        used = {colors[j] for j in neighbor_sets[i] if j < i}
        c = 0
        while c in used:
            c += 1
        colors[i] = c % 6

    # ---- build world.json ----
    territories_json = {}
    nation_territory_ids = {}
    for i, t in enumerate(final):
        chunks = t["chunks"]
        avgx = sum(c[0] for c in chunks) / len(chunks)
        avgz = sum(c[1] for c in chunks) / len(chunks)
        core = min(chunks, key=lambda c: (c[0] - avgx) ** 2 + (c[1] - avgz) ** 2)
        chunks_flat = []
        for (x, z) in chunks:
            chunks_flat.extend([x, z])
        territories_json[str(i)] = {
            "name": "",
            "color": colors[i],
            "coreChunk": [core[0], core[1]],
            "chunks": chunks_flat,
            "nodes": [],
            "neighbors": sorted(neighbor_sets[i]),
            "isEdge": is_edge[i],
        }
        nation_territory_ids.setdefault(t["nation"], []).append(i)

    world_json = {
        "meta": {"type": "world"},
        "nodes": {},
        "territories": territories_json,
    }

    # ---- build towns.json: nations pre-reserve their territories, no towns ----
    nations_json = {}
    for nation, ids in nation_territory_ids.items():
        nations_json[nation] = {
            # no "capital" key at all -- Gson's .get("capital") on a JSON `null` literal returns
            # a JsonNull element (not Kotlin null), and Deserializer's ?.asString call on that
            # throws UnsupportedOperationException. Omitting the key makes .get() return real null.
            "color": NATION_COLOR_RGB.get(nation, [200, 200, 200]),
            "towns": [],
            "allies": [],
            "enemies": [],
            "reservedTerritories": sorted(ids),
        }

    towns_json = {
        "meta": {"type": "towns"},
        "residents": {},
        "towns": {},
        "nations": nations_json,
    }

    import datetime
    world_out = os.path.join(NODES_DATA_DIR, "world.json")
    towns_out = os.path.join(NODES_DATA_DIR, "towns.json")
    backup_dir = os.path.join(NODES_DATA_DIR, "backup")
    if os.path.exists(world_out) or os.path.exists(towns_out):
        os.makedirs(backup_dir, exist_ok=True)
        ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        if os.path.exists(world_out):
            os.replace(world_out, os.path.join(backup_dir, f"world.pre-realborders-{ts}.json"))
        if os.path.exists(towns_out):
            os.replace(towns_out, os.path.join(backup_dir, f"towns.pre-realborders-{ts}.json"))

    with open(world_out, "w") as f:
        json.dump(world_json, f)
    with open(towns_out, "w") as f:
        json.dump(towns_json, f, indent=2)

    print("territories:", len(final))
    for nation, ids in sorted(nation_territory_ids.items(), key=lambda kv: -len(kv[1])):
        print(f"  {nation:12s}: {len(ids)} territories, {sum(len(final[i]['chunks']) for i in ids)} chunks")
    print("wrote world.json, towns.json to", NODES_DATA_DIR, "(old ones backed up if present)")


if __name__ == "__main__":
    main()
