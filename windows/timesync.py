"""時刻サーバーへの問い合わせ。端末（PC）の時計がずれていても、アプリの時刻を正しく保つ。

NTP（UDP 123）を使う。UDP が塞がれているネットワークでは、HTTPS の応答の Date ヘッダーで代わりに確かめる（精度は約 1 秒）。
求めた「サーバーの時刻 − この PC の時刻」のずれ（ミリ秒）を、アプリの時刻に足す（PC の時計そのものは、変えない）。
"""
import re
import socket
import struct
import time
import urllib.request
from email.utils import parsedate_to_datetime

NTP_EPOCH = 2208988800  # 1900-01-01 から 1970-01-01 までの秒数
FALLBACK_URLS = ("https://www.google.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace")
_HOST = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9.-]{0,98}[A-Za-z0-9])?$")


def valid_host(host: str) -> bool:
    return bool(_HOST.match(host or ""))


def _ntp_ms(data: bytes, offset: int) -> float:
    sec, frac = struct.unpack("!II", data[offset:offset + 8])
    return (sec - NTP_EPOCH) * 1000.0 + frac * 1000.0 / 2 ** 32


def parse_ntp(data: bytes, t1_ms: float, t4_ms: float) -> float:
    """NTP の応答から、ずれ（ミリ秒。サーバー − この PC）を求める。応答が正しくなければ ValueError"""
    if len(data) < 48:
        raise ValueError("応答が短すぎます")
    mode = data[0] & 0x07
    stratum = data[1]
    if mode != 4 or stratum == 0 or stratum > 15:
        raise ValueError("時刻サーバーの応答が正しくありません")
    t2 = _ntp_ms(data, 32)  # サーバーが受け取った時刻
    t3 = _ntp_ms(data, 40)  # サーバーが返した時刻
    return ((t2 - t1_ms) + (t3 - t4_ms)) / 2


def sntp(host: str, timeout: float = 3.0) -> float:
    """NTP で、ずれ（ミリ秒）を求める"""
    last = None
    infos = socket.getaddrinfo(host, 123, socket.AF_INET, socket.SOCK_DGRAM)
    for family, kind, proto, _, addr in infos[:3]:
        s = socket.socket(family, kind, proto)
        s.settimeout(timeout)
        try:
            packet = b"\x1b" + 47 * b"\0"  # LI=0, VN=3, Mode=3（クライアント）
            t1 = time.time() * 1000
            s.sendto(packet, addr)
            data, _ = s.recvfrom(512)
            t4 = time.time() * 1000
            return parse_ntp(data, t1, t4)
        except (OSError, ValueError) as e:
            last = e
        finally:
            s.close()
    raise ValueError(f"NTP で取得できません（{last.__class__.__name__ if last else '応答なし'}）")


def http_date(url: str, timeout: float = 5.0) -> float:
    """HTTPS の応答の Date ヘッダーから、ずれ（ミリ秒）を求める（精度は約 1 秒）"""
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "SignagePlayer/1.0"})
    t1 = time.time() * 1000
    with urllib.request.urlopen(req, timeout=timeout) as r:
        date = r.headers.get("Date")
    t4 = time.time() * 1000
    if not date:
        raise ValueError("Date ヘッダーがありません")
    server_ms = parsedate_to_datetime(date).timestamp() * 1000 + 500  # Date は秒の単位なので、秒の中ほどとみなす
    return server_ms - (t1 + t4) / 2


def measure(host: str):
    """(ずれ（ミリ秒）, 方法)。NTP で取れなければ、HTTPS で。どちらもだめなら ValueError"""
    errors = []
    if valid_host(host):
        try:
            return sntp(host), f"NTP（{host}）"
        except (OSError, ValueError) as e:
            errors.append(str(e))
    for url in FALLBACK_URLS:
        try:
            return http_date(url), "HTTPS（Date ヘッダー・精度 約 1 秒）"
        except Exception as e:
            errors.append(f"{url.split('/')[2]}: {e.__class__.__name__}")
    raise ValueError("；".join(errors) or "取得できません")
