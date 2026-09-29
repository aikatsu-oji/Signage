"""
設定・フォルダ・テロップの管理（Windows 版）。
設定の項目名は Android 版の Prefs とそろえ、管理画面（admin.html）から同じ API で操作できるようにする。
"""

import json
import os
import re
import secrets
import socket
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path

APP_DIR = Path(os.environ.get("APPDATA", Path.home())) / "Signage"
CONFIG_FILE = APP_DIR / "config.json"
MEDIA_ROOT = Path.home() / "Signage"

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif", ".svg"}
VIDEO_EXTS = {".mp4", ".m4v", ".webm", ".mkv", ".mov", ".ogv"}

LAYOUT_SINGLE, LAYOUT_LEFT_RIGHT, LAYOUT_TOP_BOTTOM, LAYOUT_MAIN_SIDE = 0, 1, 2, 3
ZONE_FOLDER, ZONE_WEATHER = 0, 1
MAX_ZONES = 3


def zone_count(layout: int) -> int:
    return {LAYOUT_LEFT_RIGHT: 2, LAYOUT_TOP_BOTTOM: 2, LAYOUT_MAIN_SIDE: 3}.get(layout, 1)


def zone_names(layout: int) -> list:
    return {
        LAYOUT_LEFT_RIGHT: ["左", "右"],
        LAYOUT_TOP_BOTTOM: ["上", "下"],
        LAYOUT_MAIN_SIDE: ["メイン", "サイド1：横長画面では右上、縦長画面では左下", "サイド2：右下"],
    }.get(layout, ["全画面"])


def natural_key(s: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def kind_of(name: str):
    """'video' / 'image' / None"""
    ext = os.path.splitext(name)[1].lower()
    if ext in VIDEO_EXTS:
        return "video"
    if ext in IMAGE_EXTS:
        return "image"
    return None


DEFAULTS = {
    "layout": LAYOUT_SINGLE,
    "splitPercent": 50,
    "mainPercent": 70,
    "sidePercent": 50,
    "zoneTypes": [ZONE_FOLDER, ZONE_FOLDER, ZONE_WEATHER],
    "zoneFolders": ["", "", ""],
    "imageSeconds": 10,
    "shuffle": False,
    "recursive": True,
    "videoSound": True,
    "clockEnabled": False,
    "clockPosition": 0,
    "clockSize": 1,
    "weatherEnabled": False,
    "weatherIntervalMin": 10,
    "weatherSeconds": 15,
    "weatherTimeSeries": True,
    "weatherOffice": "130000",
    "weatherArea": "130010",
    "weatherAreaName": "東京地方",
    "weatherCity": None,
    "weatherCityName": None,
    "adminEnabled": True,
    "adminPin": None,
    "deviceId": None,
    "deviceName": None,
    "monitor": 0,
    "autoStart": False,
    "tickerStanding": None,
    "tickerSchedules": [],
}


class Store:
    def __init__(self):
        self.lock = threading.RLock()
        self.data = dict(DEFAULTS)
        self.listeners = []  # event(str) を受け取る関数
        self.ticker_queue = []
        self.ticker_stop = False
        self.fired = {}
        self._load()

    # ------------------------------------------------------------ 保存

    def _load(self):
        try:
            saved = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            self.data.update({k: v for k, v in saved.items() if k in DEFAULTS})
        except (OSError, ValueError):
            pass
        changed = False
        if not self.data["deviceId"]:
            self.data["deviceId"] = str(uuid.uuid4())
            changed = True
        if not self.data["adminPin"]:
            self.data["adminPin"] = "%06d" % secrets.randbelow(1_000_000)
            changed = True
        # 初めて起動したときは、ホームフォルダの Signage\zone1〜3 を区画のフォルダにする
        folders = list(self.data["zoneFolders"]) + [""] * MAX_ZONES
        for i in range(MAX_ZONES):
            if not folders[i]:
                d = MEDIA_ROOT / f"zone{i + 1}"
                d.mkdir(parents=True, exist_ok=True)
                folders[i] = str(d)
                changed = True
        self.data["zoneFolders"] = folders[:MAX_ZONES]
        if changed:
            self.save()

    def save(self):
        with self.lock:
            APP_DIR.mkdir(parents=True, exist_ok=True)
            tmp = CONFIG_FILE.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
            os.replace(tmp, CONFIG_FILE)

    def get(self, key):
        with self.lock:
            return self.data.get(key, DEFAULTS.get(key))

    def update(self, values: dict):
        with self.lock:
            self.data.update(values)
            self.save()

    def notify(self, event: str):
        for listener in list(self.listeners):
            try:
                listener(event)
            except Exception:
                pass

    # ------------------------------------------------------------ 端末

    @property
    def device_id(self):
        return self.get("deviceId")

    @property
    def device_name(self):
        return self.get("deviceName") or f"{socket.gethostname()}-{self.device_id[:4]}"

    def zone_type(self, i):
        types = self.get("zoneTypes")
        t = types[i] if i < len(types) else ZONE_FOLDER
        return t if t in (ZONE_FOLDER, ZONE_WEATHER) else ZONE_FOLDER

    def zone_folder(self, i):
        folders = self.get("zoneFolders")
        f = folders[i] if i < len(folders) else ""
        return Path(f) if f else None

    # ------------------------------------------------------------ フォルダ

    def scan(self, folder: Path, recursive=None):
        """フォルダ内の画像・動画を自然順で返す [{name, path, video, size}]"""
        if recursive is None:
            recursive = self.get("recursive")
        if not folder or not folder.is_dir():
            raise OSError(f"フォルダを読み込めません: {folder}")
        items = []
        it = folder.rglob("*") if recursive else folder.glob("*")
        for p in it:
            if p.name.startswith(".") or not p.is_file():
                continue
            rel = p.relative_to(folder)
            if any(part.startswith(".") for part in rel.parts):
                continue
            kind = kind_of(p.name)
            if not kind:
                continue
            try:
                size = p.stat().st_size
            except OSError:
                continue
            items.append({"name": rel.as_posix(), "path": p, "video": kind == "video", "size": size})
        items.sort(key=lambda x: natural_key(x["name"]))
        return items

    @staticmethod
    def is_writable(folder: Path) -> bool:
        if not folder or not folder.is_dir():
            return False
        probe = folder / f".write-test-{time.time_ns()}"
        try:
            probe.write_bytes(b"")
            probe.unlink()
            return True
        except OSError:
            return False

    @staticmethod
    def sanitize(raw: str):
        name = raw.replace("\\", "/").split("/")[-1]
        name = re.sub(r'[\x00-\x1f:*?"<>|]', "_", name).strip().lstrip(".")
        if not name or len(name) > 150 or not kind_of(name):
            return None
        return name

    def find(self, folder: Path, name: str):
        for item in self.scan(folder):
            if item["name"] == name:
                return item
        raise FileNotFoundError(f"ファイルが見つかりません: {name}")

    @staticmethod
    def unique_name(folder: Path, name: str) -> str:
        existing = {p.name.lower() for p in folder.iterdir()}
        if name.lower() not in existing:
            return name
        base, ext = os.path.splitext(name)
        n = 2
        while f"{base} ({n}){ext}".lower() in existing:
            n += 1
        return f"{base} ({n}){ext}"

    # ------------------------------------------------------------ テロップ

    @staticmethod
    def ticker_message(j: dict) -> dict:
        def clamp(v, lo, hi, d):
            try:
                return max(lo, min(hi, int(v)))
            except (TypeError, ValueError):
                return d
        text = re.sub(r"\s*\n\s*", "　", str(j.get("text", "")).strip())[:500]
        return {
            "id": j.get("id") or str(uuid.uuid4()),
            "text": text,
            "style": clamp(j.get("style"), 0, 2, 0),
            "repeat": clamp(j.get("repeat"), 0, 20, 2),
            "position": clamp(j.get("position"), 0, 1, 0),
            "size": clamp(j.get("size"), 0, 2, 1),
            "speed": clamp(j.get("speed"), 0, 2, 1),
            "chime": bool(j.get("chime", False)),
            "speak": bool(j.get("speak", False)),
        }

    def post_ticker(self, message: dict):
        with self.lock:
            if message["repeat"] == 0:
                self.update({"tickerStanding": message})
            else:
                self.ticker_queue.append(message)
        self.notify("ticker")

    def stop_ticker(self):
        with self.lock:
            self.ticker_queue.clear()
            self.ticker_stop = True
            self.update({"tickerStanding": None})
        self.notify("ticker")

    def next_ticker(self, take=True):
        """
        再生画面が次に流すもの（止める指示・回数指定のもの・止めるまで流すもの）。
        take=False のときは回数指定のものを取り出さずに、あるかどうかだけ返す。
        """
        with self.lock:
            stop = self.ticker_stop
            self.ticker_stop = False
            queued = None
            if self.ticker_queue:
                queued = self.ticker_queue.pop(0) if take else True
            return {"stop": stop, "queued": queued, "standing": self.get("tickerStanding")}

    TIME_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")

    def set_schedules(self, raw_list):
        schedules = []
        for j in raw_list:
            t = str(j.get("time", ""))
            msg = self.ticker_message(j.get("message") or {})
            if not self.TIME_RE.match(t) or not msg["text"]:
                raise ValueError("予約の時刻や文字が正しくありません")
            msg["repeat"] = max(1, msg["repeat"])
            schedules.append({
                "id": j.get("id") or str(uuid.uuid4()),
                "time": t,
                "days": int(j.get("days", 0x7F)) & 0x7F,
                "enabled": bool(j.get("enabled", True)),
                "message": msg,
            })
        schedules.sort(key=lambda s: s["time"])
        self.update({"tickerSchedules": schedules})

    def check_schedules(self, now=None):
        now = now or datetime.now()
        hm = now.strftime("%H:%M")
        bit = 1 << now.weekday()  # 月曜=0
        stamp = now.strftime("%Y-%m-%d ") + hm
        for s in self.get("tickerSchedules") or []:
            if not s.get("enabled") or s.get("time") != hm or not (s.get("days", 0) & bit):
                continue
            if self.fired.get(s["id"]) == stamp:
                continue
            self.fired[s["id"]] = stamp
            msg = dict(s["message"])
            msg["id"] = str(uuid.uuid4())
            with self.lock:
                self.ticker_queue.append(msg)
            self.notify("ticker")
