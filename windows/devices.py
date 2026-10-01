"""操作できる端末の制限（MAC アドレス）。

接続元の MAC アドレスは、同じネットワーク内（ルーターを挟まない）なら ARP テーブルから分かる。
VPN（Tailscale など）経由や、ルーターを挟んだ先の端末は MAC アドレスを確認できない。
Windows 専用の処理は lookup_mac の中だけにして、ほかは単体でテストできるようにしてある。
"""
import ipaddress
import os
import re
import socket
import struct
import subprocess
import threading
import time

MAX_DEVICES = 50
_HEX = re.compile(r"[^0-9a-f]")


def normalize_mac(text):
    """'AA-BB-CC-DD-EE-FF' や 'aabb.ccdd.eeff' を 'aa:bb:cc:dd:ee:ff' にそろえる。不正なら None"""
    h = _HEX.sub("", str(text or "").lower())
    if len(h) != 12 or h == "0" * 12 or h == "f" * 12:
        return None
    return ":".join(h[i:i + 2] for i in range(0, 12, 2))


def clean_devices(items):
    """[{mac, name}] を検査して整える。不正な MAC があれば ValueError（重複は 1 つにまとめる）"""
    if not isinstance(items, list):
        raise ValueError("端末の一覧が正しくありません")
    result, seen = [], set()
    for it in items:
        raw = it.get("mac") if isinstance(it, dict) else it
        mac = normalize_mac(raw)
        if not mac:
            raise ValueError(f"MAC アドレスが正しくありません: {raw}")
        if mac in seen:
            continue
        seen.add(mac)
        name = str(it.get("name") or "").strip()[:30] if isinstance(it, dict) else ""
        result.append({"mac": mac, "name": name})
    if len(result) > MAX_DEVICES:
        raise ValueError(f"登録できるのは {MAX_DEVICES} 台までです")
    return result


def _ip(addr):
    try:
        ip = ipaddress.ip_address(str(addr).split("%")[0])
    except ValueError:
        return None
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip


def is_loopback(addr):
    ip = _ip(addr)
    return bool(ip and ip.is_loopback)


_TAILSCALE_V4 = ipaddress.ip_network("100.64.0.0/10")
_TAILSCALE_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")


def is_vpn(addr):
    """Tailscale などの VPN 経由のアドレスか（MAC アドレスを確認できない）"""
    ip = _ip(addr)
    if not ip:
        return False
    return ip in (_TAILSCALE_V4 if ip.version == 4 else _TAILSCALE_V6)


def parse_arp(text):
    """`arp -a`（Windows）と /proc/net/arp（Linux）の出力から {IPv4: MAC} を作る"""
    table = {}
    for line in str(text).splitlines():
        m = re.search(r"(\d{1,3}(?:\.\d{1,3}){3})\s+(?:0x\d+\s+0x\d+\s+)?([0-9a-fA-F]{2}(?:[:-][0-9a-fA-F]{2}){5})", line)
        if m:
            mac = normalize_mac(m.group(2))
            if mac:
                table[m.group(1)] = mac
    return table


_cache = {}
_cache_lock = threading.Lock()
CACHE_SECONDS = 60


def _send_arp(ip):
    import ctypes
    dest = struct.unpack("<L", socket.inet_aton(ip))[0]
    buf = (ctypes.c_ubyte * 8)()
    size = ctypes.c_ulong(8)
    if ctypes.windll.iphlpapi.SendARP(dest, 0, ctypes.byref(buf), ctypes.byref(size)) != 0 or size.value < 6:
        return None
    return normalize_mac(":".join(f"{b:02x}" for b in buf[:6]))


def lookup_mac(addr):
    """接続元 IP の MAC アドレス。確認できなければ None"""
    ip = _ip(addr)
    if not ip or ip.version != 4 or ip.is_loopback or is_vpn(addr):
        return None
    key = str(ip)
    now = time.time()
    with _cache_lock:
        hit = _cache.get(key)
        if hit and now - hit[1] < CACHE_SECONDS:
            return hit[0]
    mac = None
    try:
        if os.name == "nt":
            mac = _send_arp(key)
            if not mac:
                out = subprocess.run(["arp", "-a", key], capture_output=True, text=True, timeout=5,
                                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
                mac = parse_arp(out).get(key)
        else:
            with open("/proc/net/arp", encoding="utf-8") as f:
                mac = parse_arp(f.read()).get(key)
    except Exception:
        mac = None
    if mac:
        with _cache_lock:
            _cache[key] = (mac, now)
    return mac


def gate(lock, devices, allow_vpn, addr, mac):
    """この接続元が操作してよいか。許可なら ""、だめなら理由。
    制限が OFF、または登録が 0 台なら誰でも許可（初期状態）。この PC 自身（ループバック）は常に許可。"""
    if not lock or not devices or is_loopback(addr):
        return ""
    if is_vpn(addr):
        return "" if allow_vpn else "VPN 経由の操作は許可されていません"
    if not mac:
        return "この端末の MAC アドレスを確認できないため、操作できません"
    if mac in {d["mac"] for d in devices}:
        return ""
    return f"この端末（MAC アドレス {mac}）は、操作が許可されていません"


def check_update(lock, devices, allow_vpn, addr, mac):
    """制限を ON にする（または一覧を変える）とき、操作中の端末自身が締め出されないか確認する。
    問題なければ ""、だめなら理由"""
    if not lock:
        return ""
    if not devices:
        return "制限を ON にするには、操作を許可する端末を 1 台以上登録してください"
    return gate(lock, devices, allow_vpn, addr, mac).replace(
        "は、操作が許可されていません", "が登録されていません。このままだと、いま操作しているこの端末が操作できなくなります")
