#!/usr/bin/env python3
"""
天気予報の「地方ごとの一覧」の背景に使う、日本の都道府県の境界データ（weather_map.json）を作る。
元データ：Natural Earth（パブリックドメイン）の admin-1 境界 ne_10m_admin_1_states_provinces.geojson
使い方: python3 scripts/build-weather-map.py <ne_10m_admin_1_states_provinces.geojson>
出力:   android/app/src/main/assets/weather_map.json（windows/web/ にも同じものを置く）
        各地方の、経緯度 → 画面の位置（％）の変換を、weather_regions.json に書き込む
"""
import json
import math
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "android/app/src/main/assets"
SCALE = 100  # 経緯度を 1/100 度の整数にして保存する


def simplify(ring, tol):
    """Douglas–Peucker。ring は [(x, y), ...]（閉じていても、いなくても可）"""
    if len(ring) < 5:
        return ring
    def dist(p, a, b):
        (x, y), (x1, y1), (x2, y2) = p, a, b
        if (x1, y1) == (x2, y2):
            return math.hypot(x - x1, y - y1)
        return abs((y2 - y1) * x - (x2 - x1) * y + x2 * y1 - y2 * x1) / math.hypot(x2 - x1, y2 - y1)
    keep = [False] * len(ring)
    keep[0] = keep[-1] = True
    stack = [(0, len(ring) - 1)]
    while stack:
        i, j = stack.pop()
        far, k = 0, -1
        for m in range(i + 1, j):
            d = dist(ring[m], ring[i], ring[j])
            if d > far:
                far, k = d, m
        if far > tol and k >= 0:
            keep[k] = True
            stack += [(i, k), (k, j)]
    return [p for p, kp in zip(ring, keep) if kp]


def main(src):
    feats = [f for f in json.load(open(src))["features"] if f["properties"].get("admin") == "Japan"]
    prefs = {}
    for f in feats:
        code = f["properties"]["iso_3166_2"].split("-")[1]  # "JP-27" → "27"
        g = f["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        rings = []
        for poly in polys:
            ring = simplify([tuple(p) for p in poly[0]], 0.012)  # 外周のみ
            if len(ring) < 4:
                continue
            xs = [p[0] for p in ring]; ys = [p[1] for p in ring]
            if (max(xs) - min(xs)) * (max(ys) - min(ys)) < 0.0004:  # 小さすぎる島は省く
                continue
            rings.append([int(round(v * SCALE)) for p in ring for v in p])
        prefs[code] = rings
    out = {"scale": SCALE, "prefs": prefs}
    p = ASSETS / "weather_map.json"
    p.write_text(json.dumps(out, separators=(",", ":")), encoding="utf-8")
    shutil.copy(p, ROOT / "windows/web/weather_map.json")
    print("points:", sum(len(r) // 2 for rs in prefs.values() for r in rs), "bytes:", p.stat().st_size)


if __name__ == "__main__":
    main(sys.argv[1])
