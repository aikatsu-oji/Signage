"""
気象庁の予報データの取得と解析（Android 版 Weather.kt と同じ内容）。
30分以内に取得したデータはそれを使い、通信に失敗したら前回のデータを使う。
"""

import json
import re
import time
import urllib.request
from datetime import datetime, timedelta, timezone

from store import APP_DIR

FORECAST_URL = "https://www.jma.go.jp/bosai/forecast/data/forecast/{}.json"
TIME_SERIES_URL = "https://www.jma.go.jp/bosai/jmatile/data/wdist/VPFD/{}.json"
AREA_URL = "https://www.jma.go.jp/bosai/common/const/area.json"
MAX_AGE = 30 * 60
CACHE = APP_DIR / "cache"
JST = timezone(timedelta(hours=9))


def http_get(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "SignagePlayer/1.0"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.read().decode("utf-8")


def cached(name: str, url: str, validate, max_age=MAX_AGE):
    """(json文字列, 前回のデータか) または None"""
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / name
    fresh = f.exists() and time.time() - f.stat().st_mtime < max_age
    stale = False
    if not fresh:
        try:
            text = http_get(url)
            validate(text)  # 壊れたデータで上書きしない
            f.write_text(text, encoding="utf-8")
        except Exception:
            stale = True
    if not f.exists():
        return None
    return f.read_text(encoding="utf-8"), stale


def parse_time(s: str) -> datetime:
    return datetime.fromisoformat(s).astimezone(JST).replace(tzinfo=None)


# ---------------------------------------------------------------- アイコン

SUN, CLOUD, RAIN, SNOW, MOON = "☀️", "☁️", "☔", "⛄", "🌙"


def code_icon(code: str):
    try:
        n = int(code)
    except (TypeError, ValueError):
        return [CLOUD, None]
    main = {1: SUN, 2: CLOUD, 3: RAIN, 4: SNOW}.get(n // 100, CLOUD)
    sub = None
    if n in (100, 200, 300, 400):
        sub = None
    elif n in (101, 110, 111, 130, 131, 132):
        sub = CLOUD
    elif n in (104, 105, 115, 116, 117, 160, 170, 181):
        sub = SNOW
    elif 102 <= n <= 199:
        sub = RAIN
    elif n in (201, 210, 211, 223, 231):
        sub = SUN
    elif n in (204, 205, 209, 215, 216, 217, 228, 229, 230, 250, 260, 270, 281):
        sub = SNOW
    elif 202 <= n <= 299:
        sub = RAIN
    elif n in (301, 311, 316, 320, 323, 324, 325):
        sub = SUN
    elif n in (302, 313, 350):
        sub = CLOUD
    elif 303 <= n <= 399:
        sub = SNOW
    elif n in (401, 411, 420):
        sub = SUN
    elif n in (402, 413, 450):
        sub = CLOUD
    elif 403 <= n <= 499:
        sub = RAIN
    return [main, sub]


def text_icon(text: str, hour: int):
    night = hour >= 18 or hour < 6
    if "雪" in text and "雨" in text:
        return [RAIN, SNOW]
    if "雪" in text:
        return [SNOW, None]
    if "雨" in text:
        return [RAIN, None]
    if "くもり" in text or "曇" in text:
        return [CLOUD, None]
    if "晴" in text:
        return [MOON if night else SUN, None]
    return [CLOUD, None]


# ---------------------------------------------------------------- 解析

def _find_area(areas, code, hint=None):
    if hint:
        for i, a in enumerate(areas):
            if hint in a.get("area", {}).get("name", ""):
                return i
    if code:
        for i, a in enumerate(areas):
            if a.get("area", {}).get("code") == code:
                return i
    return 0


def _pick(areas, i):
    return areas[min(i, len(areas) - 1)] if areas else {}


def parse_forecast(text: str, area_code, hint=None):
    root = json.loads(text)
    short = root[0]
    report = parse_time(short["reportDatetime"])
    ts = short["timeSeries"]

    ts0 = ts[0]
    ai = _find_area(ts0["areas"], area_code, hint)
    a0 = ts0["areas"][ai]
    area_name = a0["area"]["name"]
    times0 = [parse_time(t) for t in ts0["timeDefines"]]
    codes = a0.get("weatherCodes", [])
    texts = a0.get("weathers", [])

    ts1 = ts[1] if len(ts) > 1 else None
    pop_times = [parse_time(t) for t in ts1["timeDefines"]] if ts1 else []
    pops = _pick(ts1["areas"], ai).get("pops", []) if ts1 else []

    ts2 = ts[2] if len(ts) > 2 else None
    temp_times = [parse_time(t) for t in ts2["timeDefines"]] if ts2 else []
    temps = _pick(ts2["areas"], ai).get("temps", []) if ts2 else []

    days = []
    for i in range(min(2, len(times0), len(codes))):
        date = times0[i].date()
        pops6h = []
        for h in (0, 6, 12, 18):
            v = next((pops[j] for j, t in enumerate(pop_times) if t.date() == date and t.hour == h and j < len(pops)), None)
            pops6h.append(v or None)

        def temp(hour):
            idx = [j for j, t in enumerate(temp_times) if t.date() == date and t.hour == hour and j < len(temps)]
            return (temps[idx[-1]] or None) if idx else None

        days.append({
            "date": date.isoformat(),
            "code": codes[i],
            "icon": code_icon(codes[i]),
            "text": (texts[i].replace("　", "") if i < len(texts) else "") or None,
            "max": temp(9),
            # 当日の 0時の値は最低気温ではない場合があるため、翌日以降のみ使う
            "min": None if date == report.date() else temp(0),
            "pops6h": pops6h,
        })

    week = []
    if len(root) > 1:
        wts = root[1].get("timeSeries", [])
        if wts:
            w0 = wts[0]
            wa = w0["areas"][_find_area(w0["areas"], area_code)]
            wtimes = [parse_time(t) for t in w0["timeDefines"]]
            tarea = wts[1]["areas"][0] if len(wts) > 1 else {}
            last = days[-1]["date"] if days else report.date().isoformat()
            for i, t in enumerate(wtimes):
                if t.date().isoformat() <= last:
                    continue
                code = (wa.get("weatherCodes") or [""] * (i + 1))[i]

                def val(key):
                    arr = tarea.get(key) or []
                    return (arr[i] or None) if i < len(arr) else None

                week.append({
                    "date": t.date().isoformat(),
                    "code": code,
                    "icon": code_icon(code),
                    "max": val("tempsMax"),
                    "min": val("tempsMin"),
                    "pop": ((wa.get("pops") or [])[i] or None) if i < len(wa.get("pops") or []) else None,
                })
    return {"areaName": area_name, "reportTime": report.isoformat(), "days": days, "week": week, "stale": False}


def parse_time_series(text: str):
    root = json.loads(text)
    report = parse_time(root["reportDateTime"])
    area = root["areaTimeSeries"]
    times = [parse_time(t["dateTime"]) for t in area["timeDefines"]]
    weather = area.get("weather", [])
    wind = area.get("wind", [])
    point = root.get("pointTimeSeries")
    if isinstance(point, list):
        point = point[0] if point else None
    point = point or {}
    ptimes = [parse_time(t["dateTime"]) for t in point.get("timeDefines", [])]
    ptemps = point.get("temperature", [])
    now = datetime.now(JST).replace(tzinfo=None)
    slots = []
    for i, t in enumerate(times):
        if t + timedelta(hours=3) <= now:  # 終わった時間帯は除く
            continue
        w = wind[i] if i < len(wind) else {}
        temp = None
        if t in ptimes:
            j = ptimes.index(t)
            temp = str(ptemps[j]) if j < len(ptemps) and ptemps[j] != "" else None
        wtext = weather[i] if i < len(weather) else ""
        slots.append({
            "time": t.isoformat(),
            "weather": wtext,
            "icon": text_icon(wtext, t.hour),
            "temp": temp,
            "windDir": w.get("direction") or None,
            "windRange": (str(w.get("range", "")).strip().replace(" ", "〜") or None),
        })
    return {"pointName": point.get("pointNameJP", ""), "reportTime": report.isoformat(), "slots": slots, "stale": False}


def get_forecast(office, area_code, hint=None):
    r = cached(f"forecast_{office}.json", FORECAST_URL.format(office), lambda t: parse_forecast(t, area_code, hint))
    if not r:
        return None
    try:
        data = parse_forecast(r[0], area_code, hint)
        data["stale"] = r[1]
        return data
    except Exception:
        return None


def get_time_series(area_code):
    r = cached(f"timeseries_{area_code}.json", TIME_SERIES_URL.format(area_code), parse_time_series)
    if not r:
        return None
    try:
        data = parse_time_series(r[0])
        data["stale"] = r[1]
        return data if data["slots"] else None
    except Exception:
        return None


def offices():
    """[{code, name, areas:[[code,name]], cities:[{code,name,areaCode,areaName}]}]"""
    r = cached("area.json", AREA_URL, json.loads, max_age=30 * 24 * 3600)
    if not r:
        return []
    root = json.loads(r[0])
    c10, c15, c20 = root["class10s"], root.get("class15s", {}), root.get("class20s", {})
    result = []
    for code in sorted(root["offices"]):
        o = root["offices"][code]
        areas = [[c, c10.get(c, {}).get("name", c)] for c in o.get("children", [])]
        cities, seen = [], set()
        for ac, an in areas:
            for k15 in c10.get(ac, {}).get("children", []):
                for k20 in c15.get(k15, {}).get("children", []):
                    if k20 in seen or k20 not in c20:
                        continue
                    seen.add(k20)
                    cities.append({"code": k20, "name": c20[k20]["name"], "areaCode": ac, "areaName": an})
        result.append({"code": code, "name": o["name"], "areas": areas, "cities": cities})
    return result


def _place(office, area, city_name, area_name, with_series):
    daily = get_forecast(office, area)
    series = get_time_series(area) if with_series and area else None
    if not daily and not series:
        return None
    return {
        "daily": daily,
        "series": series,
        "cityName": city_name,
        "areaName": (daily or {}).get("areaName") or area_name,
    }


# ---------------------------------------------------------------- 地方ごとの一覧（地図のように、府県の天気をまとめて表示）

def _regions_file():
    import sys
    from pathlib import Path
    base = getattr(sys, "_MEIPASS", None)
    root = Path(base) if base else Path(__file__).resolve().parent
    return root / "web" / "weather_regions.json"


_REGIONS = None


def regions():
    """[{id, name, tiles:[{label, office, hint, x, y}]}]"""
    global _REGIONS
    if _REGIONS is None:
        try:
            _REGIONS = json.loads(_regions_file().read_text(encoding="utf-8"))["regions"]
        except Exception:
            _REGIONS = []
    return _REGIONS


def clean_regions(items):
    """管理画面から受け取った地方の ID の一覧を検証して整える。正しくなければ ValueError"""
    if not isinstance(items, list):
        raise ValueError("地方の指定が正しくありません")
    known = [r["id"] for r in regions()]
    out = []
    for i in items:
        if i not in known:
            raise ValueError("地方の指定が正しくありません")
        if i not in out:
            out.append(i)
    return [i for i in known if i in out]  # 表の順に並べる


def _prefetch(tiles):
    """府県ごとの予報を、並行して取得する（通信できないときに、待ち時間が重ならないように）"""
    from concurrent.futures import ThreadPoolExecutor
    keys = sorted({(t["office"], t.get("hint") or None) for t in tiles}, key=str)

    def one(k):
        try:
            return k, get_forecast(k[0], None, k[1])
        except Exception:
            return k, None
    with ThreadPoolExecutor(max_workers=8) as ex:
        return dict(ex.map(one, keys))


def region_page(region, day, forecasts=None):
    """1 つの地方の、day 日目（0=今日・1=明日）の一覧。取得できた府県がなければ None"""
    tiles, report, stale, date = [], None, False, None
    forecasts = forecasts if forecasts is not None else _prefetch(region["tiles"])
    for t in region["tiles"]:
        d = forecasts.get((t["office"], t.get("hint") or None))
        if not d or day >= len(d["days"]):
            tiles.append({"label": t["label"], "x": t["x"], "y": t["y"], "icon": None})
            continue
        f = d["days"][day]
        pops = [int(p) for p in (f.get("pops6h") or []) if p and str(p).isdigit()]
        tiles.append({"label": t["label"], "x": t["x"], "y": t["y"], "icon": f["icon"], "text": f.get("text"),
                      "max": f.get("max"), "min": f.get("min"), "pop": max(pops) if pops else None})
        report = report or d["reportTime"]
        stale = stale or d["stale"]
        date = f["date"]
    if not report:
        return None
    return {"id": region["id"], "name": region["name"], "day": day, "date": date, "tiles": tiles, "reportTime": report, "stale": stale}


def pages(store):
    """再生画面に渡す天気予報の一式（設定の地域 + 選んだ地方の一覧）"""
    with_series = bool(store.get("weatherTimeSeries"))
    main = None
    office = store.get("weatherOffice")
    if office:
        main = _place(office, store.get("weatherArea"), store.get("weatherCityName"), store.get("weatherAreaName"), with_series)
    pick = [r for r in regions() if r["id"] in (store.get("weatherRegions") or [])]
    days = {0: [0], 1: [1], 2: [0, 1]}.get(store.get("weatherRegionDay"), [1])
    region_pages = []
    forecasts = _prefetch([t for r in pick for t in r["tiles"]]) if pick else {}
    for r in pick:
        for d in days:
            try:
                p = region_page(r, d, forecasts)
            except Exception:
                p = None
            if p:
                region_pages.append(p)
    if not main and not region_pages:
        return None
    result = dict(main) if main else {"daily": None, "series": None, "cityName": None, "areaName": None}
    result["regions"] = region_pages
    return result
