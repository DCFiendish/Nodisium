import json
import math
import os

import anvil
from shapely.geometry import shape
from shapely.ops import transform as shp_transform

HERE = os.path.dirname(os.path.abspath(__file__))
WORLD_DIR = os.path.join(HERE, "..", "..", "server", "nodisium-data", "world")

with open(os.path.join(HERE, "transform.json")) as f:
    t = json.load(f)
cx = t["coefX"]
cz = t["coefZ"]

def lonlat_to_block(lon, lat):
    return (cx[0] * lon + cx[1] * lat + cx[2], cz[0] * lon + cz[1] * lat + cz[2])

# world border box (block space)
BOX_MIN_X, BOX_MIN_Z = -4000, -2000
BOX_MAX_X, BOX_MAX_Z = 2500, 4500

# priority order: majors first, so they win chunk-overlap ties
COUNTRIES = [
    ("DEU", "Germany"),
    ("FRA", "France"),
    ("GBR", "UK"),
    ("ESP", "Spain"),
    ("MAR", "Morocco"),
    ("ITA", "Italy"),
    ("CHE", "Switzerland"),
    ("PRT", "Portugal"),
    ("NLD", "Netherlands"),
    ("BEL", "Belgium"),
]

region_cache = {}
chunk_cache = {}

def get_chunk(bx, bz):
    rx, rz = math.floor(bx / 512), math.floor(bz / 512)
    key = (rx, rz)
    if key not in region_cache:
        path = os.path.join(WORLD_DIR, "region", f"r.{rx}.{rz}.mca")
        region_cache[key] = anvil.Region.from_file(path) if os.path.exists(path) else None
    region = region_cache[key]
    if region is None:
        return None
    cx_, cz_ = (bx // 16) % 32, (bz // 16) % 32
    ckey = (rx, rz, cx_, cz_)
    if ckey not in chunk_cache:
        try:
            chunk_cache[ckey] = anvil.Chunk.from_region(region, cx_, cz_)
        except Exception:
            chunk_cache[ckey] = None
    return chunk_cache[ckey]

def classify(chunk_x, chunk_z):
    bx, bz = chunk_x * 16 + 8, chunk_z * 16 + 8
    chunk = get_chunk(bx, bz)
    if chunk is None:
        return "unknown"
    try:
        biome = str(chunk.get_biome(bx % 16, 64, bz % 16))
    except Exception:
        return "unknown"
    if "ocean" in biome:
        return "ocean"
    return "land"

assigned = {}  # (chunk_x, chunk_z) -> iso code
results = {}

for iso, name in COUNTRIES:
    path = os.path.join(HERE, "borders", f"{iso}.geo.json")
    with open(path) as f:
        gj = json.load(f)
    geoms = [shape(feat["geometry"]) for feat in gj["features"]]

    def proj(lon, lat, geoms=geoms):
        return lonlat_to_block(lon, lat)

    block_geoms = [shp_transform(lambda lon, lat: lonlat_to_block(lon, lat), g) for g in geoms]

    country_chunks = []
    for bg in block_geoms:
        minx, minz, maxx, maxz = bg.bounds
        minx = max(minx, BOX_MIN_X)
        maxx = min(maxx, BOX_MAX_X)
        minz = max(minz, BOX_MIN_Z)
        maxz = min(maxz, BOX_MAX_Z)
        if minx >= maxx or minz >= maxz:
            continue
        cx_min, cx_max = math.floor(minx / 16), math.ceil(maxx / 16)
        cz_min, cz_max = math.floor(minz / 16), math.ceil(maxz / 16)

        from shapely.geometry import Point
        for chx in range(cx_min, cx_max + 1):
            for chz in range(cz_min, cz_max + 1):
                if (chx, chz) in assigned:
                    continue
                px, pz = chx * 16 + 8, chz * 16 + 8
                if not bg.contains(Point(px, pz)):
                    continue
                cls = classify(chx, chz)
                if cls != "land":
                    continue
                assigned[(chx, chz)] = iso
                country_chunks.append((chx, chz))

    # keep only connected components above a minimum size -- point-in-polygon +
    # per-chunk ocean exclusion produces lots of 1-4 chunk noise islands that
    # fragment the border tracer; real islands (Corsica, Sicily, Ireland, ...)
    # are hundreds of chunks so a small threshold only drops noise.
    chunk_set = set(country_chunks)
    seen = set()
    components = []
    for start in country_chunks:
        if start in seen:
            continue
        stack = [start]
        comp = []
        seen.add(start)
        while stack:
            cx_, cz_ = stack.pop()
            comp.append((cx_, cz_))
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nb = (cx_ + dx, cz_ + dz)
                if nb in chunk_set and nb not in seen:
                    seen.add(nb)
                    stack.append(nb)
        components.append(comp)

    MIN_COMPONENT_SIZE = 10
    kept = [c for c in components if len(c) >= MIN_COMPONENT_SIZE]
    dropped_chunks = sum(len(c) for c in components if len(c) < MIN_COMPONENT_SIZE)
    country_chunks = [pt for comp in kept for pt in comp]
    # unassign dropped noise chunks so a later, lower-priority country can claim them
    for comp in components:
        if len(comp) < MIN_COMPONENT_SIZE:
            for pt in comp:
                del assigned[pt]

    results[iso] = {"name": name, "chunks": country_chunks, "count": len(country_chunks)}
    print(f"{name:12s} ({iso}): {len(country_chunks)} chunks kept, "
          f"{len(components)} components ({len(kept)} kept), {dropped_chunks} noise chunks dropped")

with open(os.path.join(HERE, "nation_chunks.json"), "w") as f:
    json.dump(results, f)

print("\nTotal chunks assigned:", sum(r["count"] for r in results.values()))
