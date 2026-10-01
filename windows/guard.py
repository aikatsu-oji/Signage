"""管理画面のサーバーに届いたリクエストが、正しい接続元からのものかを調べる（外部のサイトからの操作や、
偽のドメイン名を使った攻撃を防ぐ）。Windows 専用の処理を含まないので、どこでもテストできる。"""

import ipaddress
from urllib.parse import urlparse


def _hostname(host_header: str) -> str:
    h = (host_header or "").strip().lower()
    if h.startswith("["):                      # [::1]:8080
        return h[1:h.find("]")] if "]" in h else h
    return h.rsplit(":", 1)[0] if h.count(":") == 1 else h


def host_ok(host_header: str) -> bool:
    """宛先が IP アドレス・localhost・*.local（mDNS）のどれかか。
    それ以外（例：攻撃者のドメイン名が端末の IP に向けられた「DNS リバインディング」）は拒否する。"""
    name = _hostname(host_header)
    if not name:
        return False
    if name == "localhost" or name.endswith(".local"):
        return True
    try:
        ipaddress.ip_address(name.split("%")[0])
        return True
    except ValueError:
        return False


def origin_is_loopback(origin: str) -> bool:
    """Origin がこの PC 自身（localhost・127.x・::1）のページか"""
    u = urlparse(origin or "")
    name = (u.hostname or "").lower()
    if name == "localhost":
        return u.scheme == "http"
    try:
        return u.scheme == "http" and ipaddress.ip_address(name).is_loopback
    except ValueError:
        return False


LOCAL_PREFIXES = ("/local/", "/media/", "/player", "/settings")


def check(method: str, path: str, host: str, origin, sec_fetch_site, is_lan_origin) -> str:
    """問題なければ空文字、拒否するときはその理由を返す。
    is_lan_origin: Origin が同じネットワークのページか調べる関数"""
    if not host_ok(host):
        return "このアドレスでは利用できません"
    is_local_path = path.startswith(LOCAL_PREFIXES)
    if origin:
        if is_local_path:
            # この PC 内部用の操作は、この PC 自身のページ（再生画面・設定画面）からだけ受け付ける
            if not origin_is_loopback(origin):
                return "許可されていない接続元です"
        elif not is_lan_origin(origin):
            return "許可されていない接続元です"
    if is_local_path and method not in ("GET", "HEAD") and sec_fetch_site not in (None, "", "same-origin", "none"):
        return "許可されていない接続元です"
    return ""
