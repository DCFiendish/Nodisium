import numpy as np

# (lon, lat, blockX, blockZ) -- user-confirmed real anchor points
anchors = [
    ("UK center",        -2.55, 54.00, -1592, -552),
    ("France center",     2.44, 46.85,  -728,   68),
    ("Spain center",     -3.71, 40.00, -2048, 2096),
    ("Strait of Gibraltar", -5.60, 35.96, -2108, 2824),
    ("Strait of Messina",  15.63, 38.25,  1051, 2436),
]

lon = np.array([a[1] for a in anchors])
lat = np.array([a[2] for a in anchors])
bx = np.array([a[3] for a in anchors])
bz = np.array([a[4] for a in anchors])

# blockX = a*lon + b*lat + c ; blockZ = d*lon + e*lat + f
A = np.column_stack([lon, lat, np.ones_like(lon)])
coefX, resX, *_ = np.linalg.lstsq(A, bx, rcond=None)
coefZ, resZ, *_ = np.linalg.lstsq(A, bz, rcond=None)

print("blockX = %.4f*lon + %.4f*lat + %.4f" % tuple(coefX))
print("blockZ = %.4f*lon + %.4f*lat + %.4f" % tuple(coefZ))

# residuals per point (fit quality check)
print("\nResiduals (predicted - actual):")
for name, lo, la, x, z in anchors:
    px = coefX[0]*lo + coefX[1]*la + coefX[2]
    pz = coefZ[0]*lo + coefZ[1]*la + coefZ[2]
    print(f"  {name:24s} predicted=({px:8.1f},{pz:8.1f})  actual=({x:6d},{z:6d})  err=({px-x:7.1f},{pz-z:7.1f})")

import json
import os
with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "transform.json"), "w") as f:
    json.dump({"coefX": coefX.tolist(), "coefZ": coefZ.tolist()}, f)
