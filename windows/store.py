"""
設定・フォルダ・テロップの管理（Windows 版）。
設定の項目名は Android 版の Prefs とそろえ、管理画面（admin.html）から同じ API で操作できるようにする。
"""

import json
import os
import re
import secrets
import shutil
import socket
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path

APP_DIR = Path(os.environ.get("APPDATA", Path.home())) / "Signage"
CONFIG_FILE = APP_DIR / "config.json"
MEDIA_ROOT = Path.home() / "Signage"
LIBRARY_DIR = MEDIA_ROOT / "library"  # 画像・動画の保存場所（端末に 1 つ）。どの区画で・いつ流すかは「配置」で決める

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif", ".svg"}
VIDEO_EXTS = {".mp4", ".m4v", ".webm", ".mkv", ".mov", ".ogv"}

LAYOUT_SINGLE, LAYOUT_LEFT_RIGHT, LAYOUT_TOP_BOTTOM, LAYOUT_MAIN_SIDE, LAYOUT_COLUMNS3, LAYOUT_ROWS3 = 0, 1, 2, 3, 4, 5
ZONE_FOLDER, ZONE_WEATHER, ZONE_WEB, ZONE_RSS = 0, 1, 2, 3
ZONE_TYPES = (ZONE_FOLDER, ZONE_WEATHER, ZONE_WEB, ZONE_RSS)
MAX_ZONES = 3


def zone_count(layout: int) -> int:
    return {LAYOUT_LEFT_RIGHT: 2, LAYOUT_TOP_BOTTOM: 2, LAYOUT_MAIN_SIDE: 3, LAYOUT_COLUMNS3: 3, LAYOUT_ROWS3: 3}.get(layout, 1)


def zone_names(layout: int) -> list:
    return {
        LAYOUT_LEFT_RIGHT: ["左", "右"],
        LAYOUT_TOP_BOTTOM: ["上", "下"],
        LAYOUT_COLUMNS3: ["左", "中", "右"],
        LAYOUT_ROWS3: ["上", "中", "下"],
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
    "splitA": 33,  # 3分割の区画1・区画2の大きさ（％）。区画3は残り
    "splitB": 33,
    "mainPercent": 70,
    "sidePercent": 50,
    "zoneTypes": [ZONE_FOLDER, ZONE_FOLDER, ZONE_WEATHER],
    "zoneFolders": ["", "", ""],  # 旧バージョンの区画ごとのフォルダ（ライブラリへの移行にだけ使う）
    "placements": [],  # 配置（[{zone, name}]。name はライブラリのファイル名。区画ごとの順番は、この並び順）
    "libraryMigrated": False,
    "placementsV2": False,  # 配置が、ID・再生条件・専用・秒数を持つ形になったか
    "imageSeconds": 10,
    "shuffle": False,
    "recursive": True,
    "videoSound": True,
    "orientation": 0,  # 画面の向き 0: 自動 / 1: 横 / 2: 縦（Windows の向きは Windows 側の設定。管理画面のプレビュー・必要な画像サイズの確認用）
    "fitMode": 3,  # 0: 全体を表示 / 1: 全体＋ぼかし背景 / 2: 画面いっぱい / 3: おまかせ
    "clockEnabled": False,
    "clockPosition": 0,
    "clockSize": 1,
    "timeZone": "",  # アプリの時計・再生条件・予約テロップに使うタイムゾーン（IANA 名。空なら、この PC の設定）
    "timeOffsetSec": 0,  # アプリの時刻の補正（秒。この PC の時計が、ずれているとき）
    "timeSync": True,  # 時刻サーバーに定期的に問い合わせて、アプリの時刻を合わせるか
    "timeServer": "ntp.nict.jp",  # 問い合わせる NTP サーバー（取れなければ HTTPS の Date ヘッダー）
    "timeSyncOffsetMs": 0,  # 最後に求めた、PC の時計のずれ（サーバー − PC。ミリ秒）
    "timeSyncAt": 0,  # 最後に、時刻サーバーと合わせた時刻（エポックミリ秒。0 は未実施）
    "timeSyncMethod": "",
    "timeSyncError": "",
    "timeFormat": 0,  # 時計の表示形式（0=24時間 / 1=12時間（午前・午後））
    "weatherEnabled": False,
    "weatherIntervalMin": 10,
    "weatherSeconds": 15,
    "weatherRegions": [],  # 地方ごとの一覧を表示する地方の ID（kinki など）
    "weatherRegionDay": 1,  # 一覧に出す日（0=きょう 1=あした 2=両方）
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
    "groupCode": "",  # グループ（組織）コード。空ならグループなし
    "macLock": False,  # 操作できる端末を MAC アドレスで制限するか（初期状態は制限なし）
    "allowedMacs": [],  # 操作を許可する端末 [{mac, name}]
    "fileRotations": {},  # 画像・動画ごとの表示の回転（キーは「区画|ファイル名」、値は 90・180・270）
    "zoneUrls": ["", "", ""],  # Web ページ・RSS の区画の URL
    "zoneRefreshMin": [10, 10, 10],  # Web ページ・RSS の区画を読み直す間隔（分）
    "fileRules": {},  # 画像・動画ごとの再生条件（キーは「区画|ファイル名」）
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
        if not self.data["libraryMigrated"]:
            self._migrate_library()
            changed = True
        if not self.data["placementsV2"]:
            self._migrate_placements_v2()
            changed = True
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
        return t if t in ZONE_TYPES else ZONE_FOLDER

    def zone_url(self, i):
        urls = self.get("zoneUrls") or []
        return urls[i] if i < len(urls) and isinstance(urls[i], str) else ""

    def zone_refresh(self, i):
        v = self.get("zoneRefreshMin") or []
        try:
            return max(1, min(1440, int(v[i])))
        except (IndexError, TypeError, ValueError):
            return 10

    # ------------------------------------------------------------ ライブラリと配置

    def library_dir(self) -> Path:
        LIBRARY_DIR.mkdir(parents=True, exist_ok=True)
        return LIBRARY_DIR

    def _migrate_library(self):
        """旧バージョンの区画ごとのフォルダ（サブフォルダ含む）の画像・動画を、ライブラリに集めて、配置にする。
        元のファイルは消さない（同じドライブなら、ハードリンクで、容量は増えない）。再生条件・回転は、新しい名前に付け替える"""
        lib = self.library_dir()
        placements, rules, rots = [], dict(self.data.get("fileRules") or {}), dict(self.data.get("fileRotations") or {})
        new_rules, new_rots = {}, {}
        known = {}  # (名前, 大きさ) → ライブラリ内の名前（同じファイルは 1 つにまとめる）
        folders = list(self.data.get("zoneFolders") or []) + [""] * MAX_ZONES
        recursive = bool(self.data.get("recursive", True))
        for i in range(MAX_ZONES):
            if not folders[i]:
                continue
            folder = Path(folders[i])
            if not folder.is_dir():
                continue
            try:
                items = self.scan(folder, recursive)
            except OSError:
                continue
            for it in items:
                base = Path(it["name"]).name
                key = (base, it["size"])
                name = known.get(key)
                if name is None and (lib / base).is_file() and (lib / base).stat().st_size == it["size"]:
                    name = base  # 前回の移行が途中で止まった場合など、すでに同じファイルがあれば、それを使う
                    known[key] = name
                if name is None:
                    name = self.unique_name(lib, base)
                    try:
                        os.link(it["path"], lib / name)
                    except OSError:
                        try:
                            shutil.copy2(it["path"], lib / name)
                        except OSError:
                            continue
                    known[key] = name
                placements.append({"zone": i, "name": name})
                old = f"{i}|{it['name']}"
                if old in rules:
                    new_rules[f"{i}|{name}"] = rules[old]
                if old in rots:
                    new_rots[f"{i}|{name}"] = rots[old]
        self.data["placements"] = placements
        self.data["fileRules"] = new_rules
        self.data["fileRotations"] = new_rots
        self.data["libraryMigrated"] = True

    def _migrate_placements_v2(self):
        """配置に ID を付け、ファイルごとの再生条件（区画|名前）を、配置の条件に移す。回転は、ファイル名だけのキーにする"""
        rules = self.data.get("fileRules") or {}
        out = []
        for p in self.data.get("placements") or []:
            if not isinstance(p, dict) or "zone" not in p or "name" not in p:
                continue
            q = {"id": uuid.uuid4().hex[:8], "zone": p["zone"], "name": p["name"]}
            rule = rules.get(f"{p['zone']}|{p['name']}")
            if rule:
                q["rule"] = rule
            out.append(q)
        rots = {}
        for k, v in (self.data.get("fileRotations") or {}).items():
            name = k.split("|", 1)[-1]
            rots.setdefault(name, v)
        self.data["placements"] = out
        self.data["fileRotations"] = rots
        self.data["fileRules"] = {}
        self.data["placementsV2"] = True

    def placement_list(self):
        """配置の一覧（コピー）。[{id, zone, name, rule?, exclusive?, seconds?}]"""
        with self.lock:
            return [dict(p) for p in (self.data.get("placements") or [])]

    def placements_of(self, zone):
        return [p for p in self.placement_list() if p.get("zone") == zone]

    def zone_items(self, zone):
        """区画に配置されている画像・動画を、配置の順に返す
        [{id, name, path, video, size, rule, exclusive, seconds, rotation}]（ライブラリに無いものは除く）"""
        lib = self.library_dir()
        items = []
        for p in self.placements_of(zone):
            name = p["name"]
            f = lib / name
            kind = kind_of(name)
            try:
                if kind and f.is_file():
                    items.append({"id": p["id"], "name": name, "path": f, "video": kind == "video", "size": f.stat().st_size,
                                  "rule": p.get("rule"), "exclusive": bool(p.get("exclusive")), "seconds": p.get("seconds"),
                                  "rotation": self.file_rotation(name)})
            except OSError:
                continue
        return items

    def find_item(self, zone, name):
        for it in self.zone_items(zone):
            if it["name"] == name:
                return it
        raise FileNotFoundError(f"ファイルが見つかりません: {name}")

    def find_library_file(self, name):
        p = self.library_dir() / name
        if not kind_of(name) or not p.is_file() or p.parent != self.library_dir():
            raise FileNotFoundError(f"ファイルが見つかりません: {name}")
        return p

    @staticmethod
    def _clean_seconds(v):
        if v in (None, "", 0):
            return None
        try:
            return max(1, min(3600, int(v)))
        except (TypeError, ValueError):
            raise ValueError("表示秒数は 1〜3600 の数字にしてください")

    def add_placement(self, zone, name, rule=None, exclusive=False, seconds=None):
        """配置を追加して ID を返す。条件のない同じ配置があれば、それを返す（重複させない）"""
        rule = self.normalize_rule(rule) if rule else None
        seconds = self._clean_seconds(seconds)
        with self.lock:
            lst = list(self.data.get("placements") or [])
            if not rule and not exclusive and seconds is None:
                for p in lst:
                    if p.get("zone") == zone and p.get("name") == name and not p.get("rule") and not p.get("exclusive") and p.get("seconds") is None:
                        return p["id"]
            p = {"id": uuid.uuid4().hex[:8], "zone": zone, "name": name}
            if rule:
                p["rule"] = rule
            if exclusive:
                p["exclusive"] = True
            if seconds is not None:
                p["seconds"] = seconds
            lst.append(p)
            self.data["placements"] = lst
        self.save()
        return p["id"]

    def update_placement(self, pid, fields: dict):
        """配置の条件・専用・秒数・区画を変える（fields に含まれる項目だけ）"""
        with self.lock:
            lst = list(self.data.get("placements") or [])
            for p in lst:
                if p.get("id") != pid:
                    continue
                if "rule" in fields:
                    rule = self.normalize_rule(fields["rule"]) if fields["rule"] else None
                    p.pop("rule", None)
                    if rule:
                        p["rule"] = rule
                if "exclusive" in fields:
                    p.pop("exclusive", None)
                    if fields["exclusive"]:
                        p["exclusive"] = True
                if "seconds" in fields:
                    sec = self._clean_seconds(fields["seconds"])
                    p.pop("seconds", None)
                    if sec is not None:
                        p["seconds"] = sec
                if "zone" in fields:
                    z = int(fields["zone"])
                    if not 0 <= z < MAX_ZONES:
                        raise ValueError("区画が正しくありません")
                    p["zone"] = z
                break
            else:
                raise KeyError(pid)
            self.data["placements"] = lst
        self.save()

    def remove_placement_id(self, pid):
        """配置を外す（ファイルは、ライブラリに残る）"""
        with self.lock:
            self.data["placements"] = [p for p in (self.data.get("placements") or []) if p.get("id") != pid]
        self.save()

    def reorder_placements(self, zone, ids):
        """区画の配置を、ids の順に並べ替える（ids に無いものは、後ろに、元の順で残す）"""
        with self.lock:
            lst = list(self.data.get("placements") or [])
            mine = {p["id"]: p for p in lst if p.get("zone") == zone}
            ordered = [mine[i] for i in ids if i in mine]
            ordered += [p for p in lst if p.get("zone") == zone and p["id"] not in ids]
            it = iter(ordered)
            self.data["placements"] = [next(it) if p.get("zone") == zone else p for p in lst]
        self.save()

    def remove_placement(self, zone, name):
        """（旧 API 用）区画から、その名前の配置をすべて外す。どの区画にも使われなくなったファイルは、ライブラリからも消す"""
        with self.lock:
            lst = [p for p in (self.data.get("placements") or []) if not (p.get("zone") == zone and p.get("name") == name)]
            self.data["placements"] = lst
            still_used = any(p.get("name") == name for p in lst)
        if not still_used:
            self._delete_file(name)
        self.save()

    def delete_library_file(self, name):
        """ライブラリからファイルを消す（すべての配置も外れる）"""
        with self.lock:
            self.data["placements"] = [p for p in (self.data.get("placements") or []) if p.get("name") != name]
        self._delete_file(name)
        self.save()

    def _delete_file(self, name):
        try:
            (self.library_dir() / name).unlink()
        except OSError:
            pass
        self.set_file_rotation(name, 0)

    def library_files(self):
        """ライブラリの全ファイル [{name, size, video, rotation, placements:[{id, zone}]}]"""
        used = {}
        for p in self.placement_list():
            used.setdefault(p.get("name"), []).append({"id": p["id"], "zone": p.get("zone")})
        out = []
        for p in sorted(self.library_dir().iterdir(), key=lambda x: natural_key(x.name)):
            kind = kind_of(p.name)
            if kind and p.is_file() and not p.name.startswith("."):
                e = {"name": p.name, "size": p.stat().st_size, "video": kind == "video", "placements": used.get(p.name, [])}
                rot = self.file_rotation(p.name)
                if rot:
                    e["rotation"] = rot
                out.append(e)
        return out

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

    def file_rotation(self, name):
        return int((self.get("fileRotations") or {}).get(name, 0))

    def set_file_rotation(self, name, degrees):
        rots = dict(self.get("fileRotations") or {})
        if degrees in (90, 180, 270):
            rots[name] = degrees
        else:
            rots.pop(name, None)
        self.update({"fileRotations": rots})

    MAX_RULE_SLOTS = 10

    @staticmethod
    def normalize_rule(raw):
        """再生条件を検証して保存用の {"slots": [...]} に整える。どれか1つの条件に合えば再生する（複数の日時を指定できる）。
        中身のない条件は無視し、1つも無ければ None（＝いつでも再生）。以前の形式（slots のない、条件1つだけのもの）も受け付ける
        条件1つ: days 曜日 0=日〜6=土（空なら毎日） / start,end "HH:MM"（start > end は日またぎ） / from,to "YYYY-MM-DD"（両端を含む）"""
        if not isinstance(raw, dict):
            return None
        items = raw.get("slots") if isinstance(raw.get("slots"), list) else [raw]
        if len(items) > Store.MAX_RULE_SLOTS:
            raise ValueError(f"条件は {Store.MAX_RULE_SLOTS} 個までです")
        slots = [x for x in (Store._normalize_slot(i) for i in items if isinstance(i, dict)) if x]
        return {"slots": slots} if slots else None

    @staticmethod
    def _normalize_slot(raw):
        out = {}
        days = sorted({d for d in (raw.get("days") or []) if isinstance(d, int) and 0 <= d <= 6})
        if days and len(days) < 7:
            out["days"] = days
        for k in ("start", "end"):
            v = str(raw.get(k) or "")
            if v:
                if not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", v):
                    raise ValueError("時刻は HH:MM の形式で指定してください")
                out[k] = v
        for k in ("from", "to"):
            v = str(raw.get(k) or "")
            if v:
                if not re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])", v):
                    raise ValueError("日付は YYYY-MM-DD の形式で指定してください")
                out[k] = v
        if out.get("from") and out.get("to") and out["from"] > out["to"]:
            raise ValueError("期間の終わりは開始より後にしてください")
        if "start" in out and out.get("start") == out.get("end"):
            raise ValueError("開始と終了の時刻が同じです")
        return out or None

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

    def time_offset_ms(self):
        """アプリの時刻に足す、ずれ（ミリ秒）：時刻サーバーとの差（同期が ON のとき）＋ 手動の補正"""
        auto = int(self.get("timeSyncOffsetMs") or 0) if self.get("timeSync") else 0
        return auto + int(self.get("timeOffsetSec") or 0) * 1000

    def app_now(self):
        """アプリの時刻（この PC の設定とは別に決めたタイムゾーン・補正を反映した、その土地の壁時計の時刻。タイムゾーンの情報は持たない）"""
        from datetime import timedelta, timezone
        t = datetime.now(timezone.utc) + timedelta(milliseconds=self.time_offset_ms())
        name = self.get("timeZone") or ""
        if name:
            try:
                from zoneinfo import ZoneInfo
                return t.astimezone(ZoneInfo(name)).replace(tzinfo=None)
            except Exception:
                pass  # タイムゾーンの名前が不正・データが無いときは、この PC の時刻
        return t.astimezone().replace(tzinfo=None)

    def sync_time(self):
        """時刻サーバーに問い合わせて、ずれを記録する。結果の辞書を返す（失敗しても、前回のずれは、そのまま使う）"""
        import timesync
        t0 = int(time.time() * 1000)
        try:
            offset, method = timesync.measure(self.get("timeServer") or "ntp.nict.jp")
            self.update({"timeSyncOffsetMs": int(round(offset)), "timeSyncAt": t0, "timeSyncMethod": method, "timeSyncError": ""})
        except Exception as e:
            self.update({"timeSyncError": str(e)[:200]})
        return self.time_sync_info()

    def time_sync_info(self):
        return {"enabled": bool(self.get("timeSync")), "server": self.get("timeServer"),
                "offsetMs": int(self.get("timeSyncOffsetMs") or 0), "at": int(self.get("timeSyncAt") or 0),
                "method": self.get("timeSyncMethod") or "", "error": self.get("timeSyncError") or ""}

    def check_schedules(self, now=None):
        now = now or self.app_now()
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
