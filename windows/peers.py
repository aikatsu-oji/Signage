"""
同じネットワークのサイネージ端末の検出（mDNS / DNS-SD）。
Android 版と同じ「_signage._tcp」で自分を登録し、ほかの端末（Android・Windows）を探す。
"""

import socket
import threading
import time

import group
from zeroconf import IPVersion, ServiceBrowser, ServiceInfo, ServiceStateChange, Zeroconf

SERVICE_TYPE = "_signage._tcp.local."


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

    def start(self):
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
        if props.get("grp", "") != own:
            # 一覧には出さないが、なぜ見えないかを、管理画面で案内できるように、覚えておく
            reason = ("グループ未設定、または、古い版" if not props.get("grp") else "別のグループ") if own else "グループを設定している端末（この端末は未設定）"
            with self.lock:
                self.names[name] = pid
                self.hidden[pid] = {"name": props.get("name") or name.split(".")[0], "reason": reason}
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

    def list_hidden(self):
        with self.lock:
            return sorted(self.hidden.values(), key=lambda p: p["name"])

    def list(self):
        with self.lock:
            return sorted(self.peers.values(), key=lambda p: p["name"])
