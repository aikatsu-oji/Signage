"""
同じネットワークのサイネージ端末の検出（mDNS / DNS-SD）。
Android 版と同じ「_signage._tcp」で自分を登録し、ほかの端末（Android・Windows）を探す。
"""

import hmac
import json
import socket
import threading
import time

import group
from zeroconf import IPVersion, ServiceBrowser, ServiceInfo, ServiceStateChange, Zeroconf

SERVICE_TYPE = "_signage._tcp.local."
# mDNS が使えない機種（Fire TV など）でも見つけられるよう、UDP のブロードキャストでも知らせ合う
BEACON_PORT = 48080
BEACON_INTERVAL = 5
BEACON_EXPIRE = 20
# 「一覧に出さない端末」の記録は、偽の知らせで増やされないよう、件数と期間に上限を設ける
HIDDEN_MAX = 200
HIDDEN_TTL = 60


class Peers:
    def __init__(self, store, port, version, addresses):
        self.store = store
        self.port = port
        self.version = version
        self.addresses = addresses  # () -> [IPv4]
        self.zc = None
        self.info = None
        self.browser = None
        self.peers = {}  # id -> dict
        self.hidden = {}  # 見つかったが、グループが違うため、一覧に出さない端末 id -> {name, reason}
        self.names = {}  # サービス名 -> id
        self.lock = threading.Lock()
        self._beacon_stop = threading.Event()
        self._rx = None

    # ---- UDP ブロードキャスト ----

    def _beacon_message(self, ip):
        s = self.store
        code = s.get("groupCode") or ""
        m = {"app": "signage", "id": s.device_id, "name": s.device_name, "port": self.port,
             "ver": self.version, "grp": group.ident(code)}
        if code:
            # 送り元の IP とコードで作った署名を付ける（受け取る側が、なりすましを見抜く）
            m["ip"] = ip
            m["sig"] = group.sign(code, s.device_id, self.port, ip)
        return json.dumps(m, ensure_ascii=False).encode("utf-8")

    def _beacon_tx(self, stop):
        while not stop.is_set():
            try:
                for a in self.addresses():
                    msg = self._beacon_message(a)
                    targets = ["255.255.255.255", ".".join(a.split(".")[:3] + ["255"])]
                    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    try:
                        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                        sock.bind((a, 0))  # 複数のネットワークがあっても、それぞれから送る
                        for t in targets:
                            try:
                                sock.sendto(msg, (t, BEACON_PORT))
                            except OSError:
                                pass
                    except OSError:
                        pass
                    finally:
                        sock.close()
            except Exception:
                pass
            stop.wait(BEACON_INTERVAL)

    def _beacon_rx(self, stop, sock):
        while not stop.is_set():
            try:
                data, (ip, _) = sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                self._record_beacon(data, ip)
            except Exception:
                pass

    def _record_beacon(self, data, ip):
        j = json.loads(data.decode("utf-8"))
        if not isinstance(j, dict) or j.get("app") != "signage":
            return
        pid = str(j.get("id") or "")
        if not pid or pid == self.store.device_id:
            return
        port = j.get("port")
        if not isinstance(port, int) or not 1 <= port <= 65535:
            return
        name = str(j.get("name") or ip)[:60]
        grp = str(j.get("grp") or "")
        code = self.store.get("groupCode") or ""
        own = group.ident(code)
        with self.lock:
            if grp == own and own:
                # グループがあるときは、同じコードで署名された知らせだけを信じる。
                # 信じると、偽の端末に PIN とグループコードを送ってしまう
                sig = str(j.get("sig") or "")
                ok = str(j.get("ip") or "") == ip and hmac.compare_digest(sig.encode(), group.sign(code, pid, port, ip).encode())
                if not ok:
                    if pid not in self.peers:
                        self._hide(pid, name, "古い版、または、署名が合わない知らせ", beacon=True)
                    return
            if len(self.peers) >= 500 and pid not in self.peers:
                return
            if grp != own:
                reason = ("グループ未設定、または、古い版" if not grp else "別のグループ") if own else "グループを設定している端末（この端末は未設定）"
                # 署名の無い知らせで、本物の端末を一覧から消せないよう、ここでは peers を触らない
                # （グループを変えた端末は、知らせが途絶えて、20 秒ほどで一覧から外れる）
                if pid not in self.peers:
                    self._hide(pid, name, reason, beacon=True)
                return
            self.hidden.pop(pid, None)
            self.peers[pid] = {"id": pid, "name": name, "url": f"http://{ip}:{port}", "version": str(j.get("ver") or "")[:20],
                               "lastSeen": time.time(), "beacon": True}

    def _start_beacon(self):
        self._beacon_stop = stop = threading.Event()
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.settimeout(1.0)
            sock.bind(("", BEACON_PORT))
            self._rx = sock
            threading.Thread(target=self._beacon_rx, args=(stop, sock), daemon=True).start()
        except OSError:
            self._rx = None  # 受信できなくても、送信は続ける
        threading.Thread(target=self._beacon_tx, args=(stop,), daemon=True).start()

    def start(self):
        self._start_beacon()
        try:
            self.zc = Zeroconf(ip_version=IPVersion.V4Only)
        except OSError:
            return
        self._register()
        self.browser = ServiceBrowser(self.zc, SERVICE_TYPE, handlers=[self._on_change])

    def _register(self):
        s = self.store
        addrs = [socket.inet_aton(a) for a in self.addresses()]
        if not addrs:
            return
        name = s.device_name.replace(".", "・")
        self.info = ServiceInfo(
            SERVICE_TYPE,
            f"{name}.{SERVICE_TYPE}",
            addresses=addrs,
            port=self.port,
            properties={"id": s.device_id, "name": s.device_name, "ver": self.version, "grp": group.ident(s.get("groupCode") or "")},
            server=f"signage-{s.device_id[:8]}.local.",
        )
        try:
            self.zc.register_service(self.info, allow_name_change=True)
        except Exception:
            self.info = None

    def restart(self):
        """端末名が変わったときに登録し直す"""
        if not self.zc:
            return

        def run():
            if self.info:
                try:
                    self.zc.unregister_service(self.info)
                except Exception:
                    pass
            self._register()
        threading.Thread(target=run, daemon=True).start()

    def stop(self):
        self._beacon_stop.set()
        if self._rx:
            try:
                self._rx.close()
            except OSError:
                pass
            self._rx = None
        if self.zc:
            try:
                if self.info:
                    self.zc.unregister_service(self.info)
                self.zc.close()
            except Exception:
                pass
            self.zc = None
        with self.lock:
            self.peers.clear()
            self.hidden.clear()
            self.names.clear()

    def _on_change(self, zeroconf, service_type, name, state_change):
        if state_change is ServiceStateChange.Removed:
            with self.lock:
                pid = self.names.pop(name, None)
                if pid:
                    self.peers.pop(pid, None)
                    self.hidden.pop(pid, None)
            return
        threading.Thread(target=self._resolve, args=(zeroconf, service_type, name), daemon=True).start()

    def _resolve(self, zeroconf, service_type, name):
        info = zeroconf.get_service_info(service_type, name, timeout=3000)
        if not info:
            return
        props = {k.decode(): (v.decode() if isinstance(v, bytes) else "") for k, v in (info.properties or {}).items()}
        pid = props.get("id")
        if not pid or pid == self.store.device_id:
            return
        # 別のグループ（組織）の端末は、一覧に出さない
        own = group.ident(self.store.get("groupCode") or "")
        # mDNS の登録は署名できず、同じ LAN の誰でも「同じグループ」を名乗れる。
        # グループがあるときは、署名つきの UDP の知らせだけで端末を見つける
        if own and props.get("grp", "") == own:
            return
        if props.get("grp", "") != own:
            # 一覧には出さないが、なぜ見えないかを、管理画面で案内できるように、覚えておく
            reason = ("グループ未設定、または、古い版" if not props.get("grp") else "別のグループ") if own else "グループを設定している端末（この端末は未設定）"
            with self.lock:
                self.names[name] = pid
                self._hide(pid, props.get("name") or name.split(".")[0], reason, beacon=False)
            return
        addrs = info.parsed_addresses(IPVersion.V4Only)
        if not addrs:
            return
        with self.lock:
            self.names[name] = pid
            self.peers[pid] = {
                "id": pid,
                "name": props.get("name") or name.split(".")[0],
                "url": f"http://{addrs[0]}:{info.port}",
                "version": props.get("ver", ""),
                "lastSeen": time.time(),
            }

    def _hide(self, pid, name, reason, beacon):
        """一覧に出さない端末として記録する（self.lock を持った状態で呼ぶ）。件数と、UDP 由来の記録の期間に上限がある"""
        now = time.time()
        for k in [k for k, v in self.hidden.items() if v.get("beacon") and now - v["at"] > HIDDEN_TTL]:
            del self.hidden[k]
        if pid not in self.hidden and len(self.hidden) >= HIDDEN_MAX:
            return
        self.hidden[pid] = {"name": name, "reason": reason, "at": now, "beacon": beacon}

    def list_hidden(self):
        now = time.time()
        with self.lock:
            for k in [k for k, v in self.hidden.items() if v.get("beacon") and now - v["at"] > HIDDEN_TTL]:
                del self.hidden[k]
            return sorted(({"name": v["name"], "reason": v["reason"]} for v in self.hidden.values()), key=lambda p: p["name"])

    def list(self):
        now = time.time()
        with self.lock:
            return sorted((p for p in self.peers.values() if not p.get("beacon") or now - p["lastSeen"] < BEACON_EXPIRE), key=lambda p: p["name"])
