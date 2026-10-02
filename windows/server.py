"""
Windows 版の Web サーバー。
- /            管理画面（Android 版と同じ admin.html・同じ API。LAN 内から PIN 付きで操作）
- /player      再生画面（この PC の Edge で全画面表示）
- /settings    この PC の設定画面（フォルダ・天気の地域・端末名など）
- /local/...   再生画面・設定画面用の API（この PC からのみ）
- /media/...   再生する画像・動画（この PC からのみ）
"""

import base64
import hmac
import ipaddress
import json
import mimetypes
import os
import queue
import random
import re
import shutil
import socket
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

import devices
import group
import guard
import rss
import store as st
import weather

mimetypes.add_type("video/mp4", ".m4v")
mimetypes.add_type("video/quicktime", ".mov")
mimetypes.add_type("video/x-matroska", ".mkv")
mimetypes.add_type("image/webp", ".webp")
mimetypes.add_type("image/avif", ".avif")

DEFAULT_PORT = 8080
LOCAL_PORT = 18080  # HTTPS のとき、この PC 自身が使う HTTP のポート
VERSION = "1.8.20"


def resource_dir() -> Path:
    """web フォルダの場所（PyInstaller で .exe にしたときは展開先）"""
    import sys
    base = getattr(sys, "_MEIPASS", None)
    return Path(base) if base else Path(__file__).resolve().parent


def is_lan(addr: str) -> bool:
    try:
        ip = ipaddress.ip_address(addr.split("%")[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_loopback or ip.is_private or ip.is_link_local


def is_local(addr: str) -> bool:
    try:
        ip = ipaddress.ip_address(addr.split("%")[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_loopback


IPV4 = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")


def is_lan_origin(origin: str) -> bool:
    u = urlparse(origin)
    if u.scheme not in ("http", "https") or not u.hostname:
        return False
    if u.hostname == "localhost":
        return True
    return bool(IPV4.match(u.hostname)) and is_lan(u.hostname)


def local_addresses() -> list:
    """この PC の LAN 内の IPv4 アドレス"""
    found = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.0.2.1", 80))  # 実際には送信しない（経路の確認のみ）
        found.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    result = []
    for a in found:
        if a not in result and is_lan(a) and not a.startswith("127."):
            result.append(a)
    return result


class _HTTPServer(ThreadingHTTPServer):
    """
    使用中のポートには重ねて待ち受けない（Windows では「アドレスの再利用」を有効にすると
    ほかのアプリが使っているポートでも待ち受けできてしまうため、排他で待ち受ける）
    """
    allow_reuse_address = False
    daemon_threads = True

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

    def handle_error(self, request, client_address):
        """接続のやり取りの失敗（証明書の警告で切られた、など）は、画面に出さず無視する"""
        pass


class HttpError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


class Server:
    def __init__(self, store: st.Store):
        self.store = store
        self.port = 0
        self.httpd = None
        self.failures = 0
        self.locked_until = 0
        self.peers = None  # Peers（端末の検出）
        self.event_queues = []
        self.started = time.time()
        self.local_port = 0       # この PC 自身が使う HTTP のポート（HTTPS のときは、LAN 向けとは別。HTTPS でないときは port と同じ）
        self.local_httpd = None
        self.player_status = None  # 再生画面が定期的に知らせる、いま再生中の内容
        self.player_beat = 0.0
        store.listeners.append(self._broadcast)

    # ------------------------------------------------------------ 起動

    def start(self):
        handler = self._handler_class()
        for p in range(DEFAULT_PORT, DEFAULT_PORT + 10):
            try:
                self.httpd = _HTTPServer(("0.0.0.0", p), handler)
                self.port = p
                break
            except OSError:
                continue
        if not self.httpd:
            raise OSError("使用できるポートがありません")
        self.local_port = self.port
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def stop(self):
        for h in (self.httpd, self.local_httpd):
            if h:
                h.shutdown()

    def _broadcast(self, event):
        for q in list(self.event_queues):
            q.put(event)

    # ------------------------------------------------------------ 状態

    def state(self):
        s = self.store
        layout = s.get("layout")
        names = st.zone_names(layout)
        zones = []
        for i in range(st.zone_count(layout)):
            z = {"index": i, "label": f"区画{i + 1}（{names[i] if i < len(names) else ''}）", "type": s.zone_type(i)}
            if z["type"] in (st.ZONE_WEB, st.ZONE_RSS):
                z["url"] = s.zone_url(i)
                z["refreshMin"] = s.zone_refresh(i)
            if z["type"] == st.ZONE_FOLDER:
                folder = s.zone_folder(i)
                z["folder"] = str(folder) if folder else ""
                if folder:
                    z["writable"] = s.is_writable(folder)
                    try:
                        z["files"] = []
                        for f in s.scan(folder):
                            e = {"name": f["name"], "video": f["video"], "size": f["size"]}
                            rule = s.file_rule(i, f["name"])
                            if rule:
                                e["rule"] = rule
                            rot = s.file_rotation(i, f["name"])
                            if rot:
                                e["rotation"] = rot
                            z["files"].append(e)
                    except OSError:
                        z["files"] = []
                        z["error"] = "フォルダを読み込めません"
            zones.append(z)
        settings = {k: s.get(k) for k in (
            "layout", "splitPercent", "mainPercent", "sidePercent", "imageSeconds", "shuffle", "recursive", "videoSound",
            "fitMode", "orientation",
            "clockEnabled", "clockPosition", "clockSize", "timeZone", "timeOffsetSec", "timeFormat", "timeSync", "timeServer", "weatherEnabled", "weatherIntervalMin",
            "weatherSeconds", "weatherTimeSeries",
            "weatherOffice", "weatherArea", "weatherAreaName", "weatherCity", "weatherCityName")}
        settings["timeOffsetMs"] = s.time_offset_ms()  # 再生画面が使う、アプリの時刻のずれの合計
        settings["timeSyncInfo"] = s.time_sync_info()
        settings["appTime"] = s.app_now().strftime("%Y-%m-%d %H:%M:%S")  # 管理画面での確認用（アプリが、いま何時と考えているか）
        settings["zoneTypes"] = [s.zone_type(i) for i in range(st.MAX_ZONES)]
        settings["zoneUrls"] = [s.zone_url(i) for i in range(st.MAX_ZONES)]
        settings["zoneRefreshMin"] = [s.zone_refresh(i) for i in range(st.MAX_ZONES)]
        settings["weatherPlace"] = s.get("weatherCityName") or s.get("weatherAreaName") or ""
        return {
            "ticker": {"standing": s.get("tickerStanding"), "schedules": s.get("tickerSchedules") or []},
            "id": s.device_id,
            "name": s.device_name,
            "device": "Windows " + socket.gethostname(),
            "screen": self.screen_size(),
            "version": VERSION,
            "zones": zones,
            "settings": settings,
        }

    def screen_size(self) -> dict:
        """再生に使うモニターの解像度（画像がどう収まるかを管理画面で確認するために使う）"""
        try:
            mons = self.monitors() if hasattr(self, "monitors") else []
            idx = self.store.get("monitor") or 0
            m = mons[idx] if 0 <= idx < len(mons) else (mons[0] if mons else None)
            if m:
                return {"width": m["width"], "height": m["height"]}
        except Exception:
            pass
        return {"width": 1920, "height": 1080}

    PLAYER_ALIVE_SECONDS = 15

    def status(self):
        """配信状況（管理画面のモニタリング用）。再生画面が動いているか、何を再生中か、空き容量など"""
        s = self.store
        now = time.time()
        running = self.player_status is not None and now - self.player_beat < self.PLAYER_ALIVE_SECONDS
        disk = None
        try:
            folder = s.zone_folder(0)
            u = shutil.disk_usage(str(folder) if folder else str(st.APP_DIR))
            disk = {"free": u.free, "total": u.total}
        except Exception:
            pass
        return {
            "id": s.device_id, "name": s.device_name, "version": VERSION, "time": int(now * 1000),
            "uptimeSec": int(now - self.started),
            "player": {"running": running, "age": round(now - self.player_beat, 1) if self.player_status else None,
                       "zones": (self.player_status or {}).get("zones", []) if running else []},
            "ticker": {"standing": bool(s.get("tickerStanding")), "queued": len(s.ticker_queue)},
            "disk": disk,
            "platform": "windows",
        }

    def group_info(self):
        code = self.store.get("groupCode") or ""
        return {"enabled": bool(code), "id": group.ident(code)}

    def apply_group(self, j):
        """グループコードを決める・解除する（呼び出した端末は、新しいコードに切り替える）"""
        code = str(j.get("code") or "").strip()
        if code and not group.valid(code):
            raise HttpError(400, "グループコードは、英数字と - _ だけの 8〜32 文字にしてください")
        self.store.update({"groupCode": code})
        if self.peers:
            # 見つけ合いの識別子を変える。一覧は、新しいグループで作り直す（少し時間がかかる）
            peers = self.peers
            def rediscover():
                peers.stop()
                peers.start()
            threading.Thread(target=rediscover, daemon=True).start()
        return self.group_info()

    def access_info(self, addr):
        s = self.store
        return {
            "enabled": bool(s.get("macLock")), "allowVpn": bool(s.get("allowVpn")),
            "devices": list(s.get("allowedMacs") or []), "canResolve": True,
            "you": {"ip": addr, "mac": devices.lookup_mac(addr), "vpn": devices.is_vpn(addr),
                    "local": devices.is_loopback(addr)},
        }

    def apply_access(self, j, addr):
        """操作できる端末の制限を変える。操作中の端末自身が締め出される変更は断る"""
        s = self.store
        try:
            devs = devices.clean_devices(j["devices"]) if "devices" in j else list(s.get("allowedMacs") or [])
        except ValueError as e:
            raise HttpError(400, str(e))
        lock = bool(j["enabled"]) if "enabled" in j else bool(s.get("macLock"))
        vpn = bool(j["allowVpn"]) if "allowVpn" in j else bool(s.get("allowVpn"))
        why = devices.check_update(lock, devs, vpn, addr, devices.lookup_mac(addr))
        if why:
            raise HttpError(400, why)
        s.update({"macLock": lock, "allowVpn": vpn, "allowedMacs": devs})
        return self.access_info(addr)

    def reset_access(self):
        """制限を解除して、登録した端末の一覧も消す（誰も操作できなくなったとき用。この PC 自身からのみ）"""
        self.store.update({"macLock": False, "allowedMacs": []})

    def apply_settings(self, j: dict):
        def clamp(v, lo, hi):
            return max(lo, min(hi, int(v)))
        u = {}
        if "layout" in j: u["layout"] = clamp(j["layout"], 0, 3)
        if "splitPercent" in j: u["splitPercent"] = clamp(j["splitPercent"], 10, 90)
        if "mainPercent" in j: u["mainPercent"] = clamp(j["mainPercent"], 20, 90)
        if "sidePercent" in j: u["sidePercent"] = clamp(j["sidePercent"], 10, 90)
        if isinstance(j.get("zoneTypes"), list):
            types = list(self.store.get("zoneTypes"))
            for i, t in enumerate(j["zoneTypes"][:st.MAX_ZONES]):
                if t in st.ZONE_TYPES:
                    types[i] = t
            u["zoneTypes"] = types
        if isinstance(j.get("zoneUrls"), list):
            urls = list(self.store.get("zoneUrls") or [""] * st.MAX_ZONES)
            for i, v in enumerate(j["zoneUrls"][:st.MAX_ZONES]):
                v = str(v or "").strip()
                if v:
                    why = rss.check_url(v)
                    if why:
                        raise HttpError(400, f"区画{i + 1}：{why}")
                urls[i] = v
            u["zoneUrls"] = urls
        if isinstance(j.get("zoneRefreshMin"), list):
            mins = list(self.store.get("zoneRefreshMin") or [10] * st.MAX_ZONES)
            for i, v in enumerate(j["zoneRefreshMin"][:st.MAX_ZONES]):
                mins[i] = clamp(v, 1, 1440)
            u["zoneRefreshMin"] = mins
        if "timeZone" in j:
            name = str(j["timeZone"] or "").strip()
            if name:
                try:
                    from zoneinfo import ZoneInfo
                    ZoneInfo(name)
                except Exception:
                    raise HttpError(400, "タイムゾーンの名前が正しくありません")
            u["timeZone"] = name
        if "timeServer" in j:
            import timesync
            host = str(j["timeServer"] or "").strip() or "ntp.nict.jp"
            if not timesync.valid_host(host):
                raise HttpError(400, "時刻サーバーの名前が正しくありません")
            u["timeServer"] = host
        for k, lo, hi in (("imageSeconds", 1, 3600), ("clockPosition", 0, 3), ("clockSize", 0, 2), ("timeOffsetSec", -43200, 43200), ("timeFormat", 0, 1), ("fitMode", 0, 3), ("orientation", 0, 2),
                          ("weatherIntervalMin", 1, 1440), ("weatherSeconds", 3, 600)):
            if k in j: u[k] = clamp(j[k], lo, hi)
        for k in ("shuffle", "recursive", "videoSound", "clockEnabled", "weatherEnabled", "weatherTimeSeries", "timeSync"):
            if k in j: u[k] = bool(j[k])
        # 天気予報の地域（気象庁のコード。数字のみ）
        for k in ("weatherOffice", "weatherArea", "weatherCity"):
            if k in j:
                v = j[k]
                if v is not None and not re.fullmatch(r"\d{1,10}", str(v)):
                    raise HttpError(400, "天気予報の地域が正しくありません")
                u[k] = str(v) if v is not None else None
        for k in ("weatherAreaName", "weatherCityName"):
            if k in j:
                u[k] = str(j[k])[:40] if j[k] else None
        self.store.update(u)

    # ------------------------------------------------------------ ハンドラー

    def _handler_class(self):
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt, *args):
                pass

            def do_OPTIONS(self): self._dispatch("OPTIONS")
            def do_GET(self): self._dispatch("GET")
            def do_HEAD(self): self._dispatch("HEAD")
            def do_POST(self): self._dispatch("POST")
            def do_PUT(self): self._dispatch("PUT")

            # -------- 応答

            def cors(self):
                origin = self.headers.get("Origin")
                if not origin or not is_lan_origin(origin):
                    return {}
                h = {
                    "Access-Control-Allow-Origin": origin,
                    "Vary": "Origin",
                    "Access-Control-Allow-Headers": "X-Pin, X-Group-Code, Content-Type",
                    "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS",
                    "Access-Control-Max-Age": "600",
                }
                if self.headers.get("Access-Control-Request-Private-Network") == "true":
                    h["Access-Control-Allow-Private-Network"] = "true"
                return h

            def send(self, status, body: bytes, ctype, extra=None):
                self.send_response(status)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("X-Frame-Options", "DENY")          # 他のページに埋め込まれて操作されるのを防ぐ
                self.send_header("Referrer-Policy", "no-referrer")
                for k, v in {**self.cors(), **(extra or {})}.items():
                    self.send_header(k, v)
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)

            def json(self, obj, status=200):
                extra = None
                if status >= 400:
                    # 読み残した送信データが次のリクエストに混ざらないよう、エラーのときは接続を閉じる
                    self.close_connection = True
                    extra = {"Connection": "close"}
                self.send(status, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                          "application/json; charset=utf-8", extra)

            def body_json(self):
                self.body_read = True
                n = int(self.headers.get("Content-Length") or 0)
                if n > 64 * 1024:
                    raise HttpError(413, "データが大きすぎます")
                raw = self.rfile.read(n) if n else b""
                try:
                    return json.loads(raw.decode("utf-8") or "{}")
                except ValueError:
                    raise HttpError(400, "不正なデータです")

            def page(self, name):
                data = (resource_dir() / "web" / name).read_bytes()
                self.send(200, data, "text/html; charset=utf-8")

            # -------- 振り分け

            def _dispatch(self, method):
                self.body_read = False
                try:
                    self._handle(method)
                finally:
                    self.discard_body()

            def discard_body(self):
                """読まなかった送信データを捨てる（残ると、同じ接続の次のリクエストが壊れて 501 になる）"""
                if self.body_read or self.close_connection:
                    return
                try:
                    n = int(self.headers.get("Content-Length") or 0)
                    if n > 1 << 20:
                        self.close_connection = True
                    elif n > 0:
                        self.rfile.read(n)
                except (ValueError, OSError):
                    self.close_connection = True

            def _handle(self, method):
                client = self.client_address[0]
                try:
                    if not is_lan(client):
                        raise HttpError(403, "同じネットワーク内からのみ利用できます")
                    url = urlparse(self.path)
                    path = unquote(url.path)
                    # 外部のサイトからの操作（CSRF）や、偽のドメイン名を使った攻撃（DNS リバインディング）を拒否する
                    why = guard.check(method, path, self.headers.get("Host"), self.headers.get("Origin"),
                                      self.headers.get("Sec-Fetch-Site"), is_lan_origin)
                    if why:
                        raise HttpError(403, why)
                    query = {k: v[0] for k, v in parse_qs(url.query).items()}
                    if method == "OPTIONS":
                        origin = self.headers.get("Origin")
                        if not origin or not is_lan_origin(origin):
                            raise HttpError(403, "許可されていない接続元です")
                        self.send(204, b"", "text/plain")
                        return
                    local = is_local(client)
                    if path.startswith("/local/") or path.startswith("/media/") or path in ("/player", "/settings"):
                        if not local:
                            raise HttpError(403, "この PC からのみ利用できます")
                        self.route_local(method, path, query)
                        return
                    if not server.store.get("adminEnabled") and not local:
                        raise HttpError(403, "管理画面は無効になっています")
                    if method in ("GET", "HEAD") and path in ("/", "/index.html"):
                        self.page("admin.html")
                        return
                    if path.startswith("/api/"):
                        self.check_access(client)
                        self.check_pin(self.headers.get("X-Pin"), self.headers.get("X-Group-Code"))
                        self.route_api(method, path, query)
                        return
                    raise HttpError(404, "見つかりません")
                except HttpError as e:
                    self.json({"error": str(e)}, e.status)
                except (ConnectionError, BrokenPipeError):
                    pass
                except Exception as e:  # noqa
                    try:
                        self.json({"error": str(e) or e.__class__.__name__}, 500)
                    except Exception:
                        pass

            def check_access(self, client):
                """グループコードが未設定の端末は、この PC 自身からしか操作できない。そのうえで、操作できる端末の制限（MAC アドレス）"""
                s = server.store
                if not (s.get("groupCode") or "") and not devices.is_loopback(client):
                    raise HttpError(403, "グループコードが未設定のため、この PC 自身からしか操作できません。この PC の設定画面で、グループコードを決めてください")
                if not s.get("macLock"):
                    return
                devs = s.get("allowedMacs") or []
                mac = None if devices.is_loopback(client) else devices.lookup_mac(client)
                why = devices.gate(True, devs, bool(s.get("allowVpn")), client, mac)
                if why:
                    raise HttpError(403, why)

            def check_pin(self, pin, group_code=None):
                """グループコード（設定している端末だけ）と PIN を確かめる。どちらを間違えても、失敗の回数に数える"""
                now = time.time()
                with server.store.lock:
                    if now < server.locked_until:
                        raise HttpError(429, "PIN またはグループコードを続けて間違えたため、しばらく操作できません")
                    expected_group = server.store.get("groupCode") or ""
                    group_ok = (not expected_group) or bool(group_code and hmac.compare_digest(group_code.encode(), expected_group.encode()))
                    expected = server.store.get("adminPin")
                    pin_ok = bool(pin and hmac.compare_digest(pin.encode(), expected.encode()))
                    if group_ok and pin_ok:
                        server.failures = 0
                        return
                    server.failures += 1
                    if server.failures >= 5:
                        server.failures = 0
                        server.locked_until = now + 60
                # グループコードを先に判定する（コードを知らない相手に、PIN が合っているかを教えない）
                raise HttpError(401, "PIN が違います" if group_ok else "グループコードが違います")

            def folder_of(self, raw, writable=False):
                s = server.store
                try:
                    i = int(raw)
                except (TypeError, ValueError):
                    raise HttpError(400, "区画が指定されていません")
                if not (0 <= i < st.zone_count(s.get("layout"))) or s.zone_type(i) != st.ZONE_FOLDER:
                    raise HttpError(400, "フォルダの区画ではありません")
                folder = s.zone_folder(i)
                if not folder:
                    raise HttpError(400, "この区画のフォルダが未設定です")
                if writable and not s.is_writable(folder):
                    raise HttpError(403, "このフォルダには書き込めません")
                return folder

            # -------- 管理画面の API（Android 版と同じ）

            def route_api(self, method, path, query):
                s = server.store
                key = f"{method} {path}"
                if key == "GET /api/state":
                    d = server.state()
                    d["access"] = server.access_info(self.client_address[0])
                    d["group"] = server.group_info()
                    self.json(d)
                elif key == "GET /api/status":
                    self.json(server.status())
                elif key == "GET /api/weather/offices":
                    self.json(weather.offices())
                elif key == "POST /api/timesync":
                    info = s.sync_time()  # すぐに、時刻サーバーに問い合わせる（数秒かかることがある）
                    s.notify("time")
                    self.json({**info, "appTime": s.app_now().strftime("%Y-%m-%d %H:%M:%S")})
                elif key == "POST /api/group":
                    self.json(server.apply_group(self.body_json()))
                elif key == "POST /api/access":
                    self.json(server.apply_access(self.body_json(), self.client_address[0]))
                elif key == "PUT /api/upload":
                    folder = self.folder_of(query.get("zone"), writable=True)
                    name = s.sanitize(query.get("name", ""))
                    if not name:
                        raise HttpError(400, "画像・動画のファイルのみアップロードできます")
                    self.body_read = True
                    length = int(self.headers.get("Content-Length") or 0)
                    if length <= 0:
                        raise HttpError(400, "ファイルが空です")
                    final = s.unique_name(folder, name)
                    tmp = folder / f".upload-{time.time_ns()}{os.path.splitext(final)[1]}"
                    try:
                        with open(tmp, "wb") as f:
                            remaining = length
                            while remaining > 0:
                                chunk = self.rfile.read(min(1 << 20, remaining))
                                if not chunk:
                                    raise HttpError(400, "転送が途中で切れました")
                                f.write(chunk)
                                remaining -= len(chunk)
                        os.replace(tmp, folder / final)
                    finally:
                        if tmp.exists():
                            tmp.unlink()
                    self.json({"ok": True, "name": final})
                elif key == "POST /api/delete":
                    j = self.body_json()
                    folder = self.folder_of(j.get("zone"), writable=True)
                    try:
                        s.find(folder, j.get("name", ""))["path"].unlink()
                    except FileNotFoundError as e:
                        raise HttpError(404, str(e))
                    s.set_file_rule(j.get("zone"), j.get("name", ""), None)
                    s.set_file_rotation(j.get("zone"), j.get("name", ""), 0)
                    self.json({"ok": True})
                elif key == "POST /api/filerotate":
                    j = self.body_json()
                    self.folder_of(j.get("zone"), writable=True)  # 区画の確認
                    try:
                        deg = int(j.get("degrees", 0))
                    except (TypeError, ValueError):
                        raise HttpError(400, "回転は 0・90・180・270 のどれかにしてください")
                    if deg not in (0, 90, 180, 270):
                        raise HttpError(400, "回転は 0・90・180・270 のどれかにしてください")
                    s.set_file_rotation(int(j["zone"]), str(j.get("name", "")), deg)
                    s.notify("content")
                    self.json({"ok": True})
                elif key == "POST /api/filerule":
                    j = self.body_json()
                    self.folder_of(j.get("zone"), writable=True)  # 区画の確認
                    try:
                        rule = s.normalize_rule(j.get("rule"))
                    except ValueError as e:
                        raise HttpError(400, str(e))
                    s.set_file_rule(int(j["zone"]), str(j.get("name", "")), rule)
                    s.notify("content")
                    self.json({"ok": True})
                elif key in ("GET /api/file", "HEAD /api/file"):
                    folder = self.folder_of(query.get("zone"))
                    try:
                        item = s.find(folder, query.get("name", ""))
                    except FileNotFoundError as e:
                        raise HttpError(404, str(e))
                    self.send_file(item["path"])
                elif key == "POST /api/voice":
                    # 管理画面のマイクの声（16kHz・モノラル・16bit PCM、0.2秒ぶんほど）。再生画面（Edge）へすぐ流す
                    self.body_read = True
                    n = int(self.headers.get("Content-Length") or 0)
                    if n > 64 * 1024:
                        raise HttpError(413, "データが大きすぎます")
                    pcm = self.rfile.read(n) if n else b""
                    if len(pcm) < 2 or len(pcm) % 2:
                        raise HttpError(400, "音声データが正しくありません")
                    server._broadcast("voice:" + base64.b64encode(pcm).decode("ascii"))
                    # 再生画面が開いていないと音は出ない。管理画面で知らせるため、受け取り手の数も返す
                    self.json({"ok": True, "listeners": len(server.event_queues)})
                elif key == "POST /api/voice/end":
                    server._broadcast("voice-end")
                    self.json({"ok": True})
                elif key == "POST /api/reload":
                    s.notify("content")
                    self.json({"ok": True})
                elif key == "POST /api/settings":
                    server.apply_settings(self.body_json())
                    s.notify("settings")
                    self.json({"ok": True})
                elif key == "GET /api/devices":
                    peers = server.peers.list() if server.peers else []
                    hidden = server.peers.list_hidden() if server.peers else []
                    self.json({"id": s.device_id, "name": s.device_name, "peers": peers, "hidden": hidden})
                elif key == "POST /api/pin":
                    pin = str(self.body_json().get("pin", ""))
                    if not re.fullmatch(r"\d{6}", pin):
                        raise HttpError(400, "PIN は6桁の数字にしてください")
                    s.update({"adminPin": pin})
                    self.json({"ok": True})
                elif key == "POST /api/name":
                    name = str(self.body_json().get("name", "")).strip()[:40]
                    if not name:
                        raise HttpError(400, "端末名を入力してください")
                    s.update({"deviceName": name})
                    if server.peers:
                        server.peers.restart()
                    s.notify("settings")
                    self.json({"ok": True, "name": name})
                elif key == "POST /api/ticker":
                    m = s.ticker_message(self.body_json())
                    if not m["text"]:
                        raise HttpError(400, "テロップの文字を入力してください")
                    s.post_ticker(m)
                    self.json({"ok": True})
                elif key == "POST /api/ticker/stop":
                    s.stop_ticker()
                    self.json({"ok": True})
                elif key == "POST /api/ticker/schedules":
                    try:
                        s.set_schedules(self.body_json().get("schedules") or [])
                    except ValueError as e:
                        raise HttpError(400, str(e))
                    self.json({"ok": True})
                else:
                    raise HttpError(404, "見つかりません")

            # -------- この PC 用

            def route_local(self, method, path, query):
                s = server.store
                if path == "/player":
                    return self.page("player.html")
                if path == "/settings":
                    return self.page("settings.html")
                if path.startswith("/media/"):
                    # /media/<区画>/<相対パス>
                    parts = path[len("/media/"):].split("/", 1)
                    if len(parts) != 2:
                        raise HttpError(404, "見つかりません")
                    folder = self.folder_of(parts[0])
                    target = (folder / parts[1]).resolve()
                    if folder.resolve() not in target.parents or not target.is_file():
                        raise HttpError(404, "見つかりません")
                    return self.send_file(target)
                key = f"{method} {path}"
                if key == "GET /local/config":
                    cfg = server.state()["settings"]
                    cfg.update({
                        "version": VERSION,
                        "zoneCount": st.zone_count(s.get("layout")),
                        "zoneFolders": [bool(s.zone_folder(i)) for i in range(st.MAX_ZONES)],
                        "weatherOffice": s.get("weatherOffice"),
                    })
                    return self.json(cfg)
                if key == "GET /local/playlist":
                    folder = self.folder_of(query.get("zone"))
                    try:
                        items = s.scan(folder)
                    except OSError as e:
                        raise HttpError(404, str(e))
                    zone = query.get("zone")
                    return self.json([{
                        "name": it["name"],
                        "video": it["video"],
                        "url": f"/media/{zone}/{quote(it['name'])}?v={int(it['path'].stat().st_mtime)}",
                        "rule": s.file_rule(zone, it["name"]),
                        "rotation": s.file_rotation(zone, it["name"]),
                    } for it in items])
                if key == "GET /local/rss":
                    try:
                        zone = int(query.get("zone", "-1"))
                    except ValueError:
                        zone = -1
                    if not (0 <= zone < st.zone_count(s.get("layout"))) or s.zone_type(zone) != st.ZONE_RSS:
                        raise HttpError(400, "RSS の区画ではありません")
                    try:
                        return self.json({"items": rss.fetch(s.zone_url(zone))})
                    except ValueError as e:
                        raise HttpError(502, str(e))
                if key == "GET /local/weather":
                    return self.json(weather.pages(s))
                if key == "GET /local/ticker":
                    return self.json(s.next_ticker(take=query.get("take", "1") != "0"))
                if key == "GET /local/events":
                    return self.events()
                if key == "GET /local/settings":
                    d = {k: s.get(k) for k in ("zoneFolders", "weatherOffice", "weatherArea", "weatherCity",
                                              "adminEnabled", "adminPin", "monitor", "autoStart", "layout", "macLock", "allowedMacs")}
                    d["group"] = server.group_info()
                    d.update({
                        "deviceName": s.device_name,
                        "port": server.port,
                        "addresses": local_addresses(),
                        "version": VERSION,
                        "zoneCount": st.zone_count(s.get("layout")),
                        "zoneTypes": [s.zone_type(i) for i in range(st.MAX_ZONES)],
                        "zoneNames": st.zone_names(s.get("layout")),
                        "monitors": server.monitors() if hasattr(server, "monitors") else [],
                    })
                    return self.json(d)
                if key == "GET /local/offices":
                    return self.json(weather.offices())
                if key == "POST /local/player-status":
                    j = self.body_json()
                    zones = j.get("zones") if isinstance(j, dict) else None
                    clean = []
                    for z in (zones if isinstance(zones, list) else [])[:3]:
                        if isinstance(z, dict):
                            clean.append({"zone": int(z.get("zone", 0)), "kind": str(z.get("kind", ""))[:10],
                                          "name": str(z.get("name") or "")[:120], "video": bool(z.get("video")),
                                          "pos": int(z.get("pos") or 0), "total": int(z.get("total") or 0),
                                          "paused": bool(z.get("paused")), "message": str(z.get("message") or "")[:200]})
                    server.player_status = {"zones": clean}
                    server.player_beat = time.time()
                    return self.json({"ok": True})
                if key == "POST /local/access-reset":
                    server.reset_access()
                    return self.json({"ok": True})
                if key == "POST /local/settings":
                    return self.json(server.local_settings(self.body_json()))
                if key == "POST /local/pick-folder":
                    return self.json({"path": server.pick_folder(self.body_json().get("zone", 0))})
                if key == "POST /local/open-player":
                    if hasattr(server, "open_player"):
                        server.open_player()
                    return self.json({"ok": True})
                if key == "POST /local/open-folder":
                    folder = s.zone_folder(int(self.body_json().get("zone", 0)))
                    if folder and folder.is_dir():
                        os.startfile(folder)
                    return self.json({"ok": True})
                raise HttpError(404, "見つかりません")

            def events(self):
                """Server-Sent Events：ファイル・設定・テロップの変更を再生画面に知らせる"""
                q = queue.Queue()
                server.event_queues.append(q)
                try:
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Connection", "keep-alive")
                    self.end_headers()
                    self.wfile.write(b": connected\n\n")
                    self.wfile.flush()
                    while True:
                        try:
                            ev = q.get(timeout=15)
                            self.wfile.write(f"data: {ev}\n\n".encode())
                        except queue.Empty:
                            self.wfile.write(b": ping\n\n")
                        self.wfile.flush()
                except (ConnectionError, BrokenPipeError, OSError):
                    pass
                finally:
                    server.event_queues.remove(q)
                    self.close_connection = True

            def send_file(self, path: Path):
                size = path.stat().st_size
                ctype = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
                start, end = 0, size - 1
                m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range") or "")
                if m and (m.group(1) or m.group(2)):
                    if m.group(1):
                        start = int(m.group(1))
                        if m.group(2):
                            end = min(int(m.group(2)), size - 1)
                    else:
                        start = max(0, size - int(m.group(2)))
                    if start > end:
                        self.send_response(416)
                        self.send_header("Content-Range", f"bytes */{size}")
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    self.send_response(206)
                    self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                else:
                    self.send_response(200)
                length = end - start + 1
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(length))
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Cache-Control", "no-cache")
                for k, v in self.cors().items():
                    self.send_header(k, v)
                self.end_headers()
                if self.command == "HEAD":
                    return
                try:
                    with open(path, "rb") as f:
                        f.seek(start)
                        remaining = length
                        while remaining > 0:
                            chunk = f.read(min(1 << 18, remaining))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            remaining -= len(chunk)
                except (ConnectionError, BrokenPipeError):
                    pass

        return Handler

    # ------------------------------------------------------------ この PC の設定

    def local_settings(self, j: dict):
        s = self.store
        u = {}
        if isinstance(j.get("zoneFolders"), list):
            folders = list(s.get("zoneFolders"))
            for i, f in enumerate(j["zoneFolders"][:st.MAX_ZONES]):
                if isinstance(f, str) and f.strip():
                    p = Path(f.strip())
                    if not p.is_dir():
                        raise HttpError(400, f"フォルダが見つかりません: {p}")
                    folders[i] = str(p)
            u["zoneFolders"] = folders
        for k in ("weatherOffice", "weatherArea", "weatherAreaName", "weatherCity", "weatherCityName"):
            if k in j:
                u[k] = j[k] or None
        if "deviceName" in j and str(j["deviceName"]).strip():
            u["deviceName"] = str(j["deviceName"]).strip()[:40]
        if "adminPin" in j:
            if not re.fullmatch(r"\d{6}", str(j["adminPin"])):
                raise HttpError(400, "PIN は6桁の数字にしてください")
            u["adminPin"] = str(j["adminPin"])
        if j.get("resetPin"):
            u["adminPin"] = "%06d" % random.SystemRandom().randrange(1_000_000)
        if "adminEnabled" in j:
            u["adminEnabled"] = bool(j["adminEnabled"])
        if "groupCode" in j:
            self.apply_group({"code": j["groupCode"]})  # この PC 自身から、グループコードを決める・解除する
        if "monitor" in j:
            u["monitor"] = int(j["monitor"])
        if "autoStart" in j:
            u["autoStart"] = bool(j["autoStart"])
            if hasattr(self, "set_autostart"):
                self.set_autostart(u["autoStart"])
        name_changed = "deviceName" in u and u["deviceName"] != s.get("deviceName")
        monitor_changed = "monitor" in u and u["monitor"] != (s.get("monitor") or 0)
        s.update(u)
        if name_changed and self.peers:
            self.peers.restart()
        s.notify("settings")
        if monitor_changed and hasattr(self, "on_monitor_changed"):
            # 表示するモニターを変えたら、開いている再生画面を新しいモニターで開き直す
            threading.Thread(target=self.on_monitor_changed, daemon=True).start()
        return {"ok": True, "adminPin": s.get("adminPin")}

    def pick_folder(self, zone):
        """Windows のフォルダ選択ダイアログを開く"""
        result = {}

        def run():
            import tkinter as tk
            from tkinter import filedialog
            root = tk.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            current = self.store.zone_folder(int(zone))
            result["path"] = filedialog.askdirectory(
                parent=root, title=f"区画{int(zone) + 1} のフォルダを選択",
                initialdir=str(current) if current else str(Path.home()))
            root.destroy()

        t = threading.Thread(target=run)
        t.start()
        t.join()
        path = result.get("path")
        return str(Path(path)) if path else None
