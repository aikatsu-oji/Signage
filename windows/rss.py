"""RSS 2.0 / Atom の見出しの取得（Web ページ・RSS の区画用）。再生画面からは、この PC を経由して取得する（ブラウザの制限を避けるため）。"""
import ipaddress
import re
import socket
import urllib.request
import xml.etree.ElementTree as ET
from urllib.parse import urlparse

MAX_BYTES = 1_000_000
MAX_ITEMS = 30


def check_url(url: str) -> str:
    """http / https の URL だけ受け付ける。正しければ空、だめなら理由"""
    u = urlparse((url or "").strip())
    if u.scheme not in ("http", "https") or not u.hostname:
        return "http:// か https:// で始まる URL を入力してください"
    if len(url) > 500:
        return "URL が長すぎます"
    return ""


def _is_internal(host: str) -> bool:
    """この PC 自身や、リンクローカル（169.254.x.x など）のアドレスか。取得先にはしない"""
    try:
        for info in socket.getaddrinfo(host, None):
            ip = ipaddress.ip_address(info[4][0].split("%")[0])
            if ip.is_loopback or ip.is_link_local or ip.is_unspecified:
                return True
    except OSError:
        return False
    return False


def _text(el):
    return re.sub(r"\s+", " ", "".join(el.itertext()) if el is not None else "").strip()


def parse(xml_bytes: bytes):
    """[{title, date}] （新しい順に近い、フィードの並びのまま）。形式が違えば ValueError"""
    try:
        root = ET.fromstring(xml_bytes)
    except ET.ParseError as e:
        raise ValueError("RSS として読み取れません") from e
    tag = lambda e: e.tag.rsplit("}", 1)[-1]
    items = []
    if tag(root) == "feed":  # Atom
        for e in root:
            if tag(e) == "entry":
                title = date = None
                for c in e:
                    if tag(c) == "title": title = _text(c)
                    elif tag(c) in ("updated", "published") and not date: date = _text(c)
                if title: items.append({"title": title, "date": date or ""})
    else:  # RSS 2.0 / RDF
        for e in root.iter():
            if tag(e) == "item":
                title = date = None
                for c in e:
                    if tag(c) == "title": title = _text(c)
                    elif tag(c) in ("pubDate", "date") and not date: date = _text(c)
                if title: items.append({"title": title, "date": date or ""})
    if not items:
        raise ValueError("見出しが見つかりません")
    return items[:MAX_ITEMS]


def fetch(url: str):
    why = check_url(url)
    if why:
        raise ValueError(why)
    if _is_internal(urlparse(url).hostname):
        raise ValueError("この PC 自身の URL は指定できません")
    req = urllib.request.Request(url, headers={"User-Agent": "SignagePlayer/1.0", "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml, */*"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            data = r.read(MAX_BYTES + 1)
    except Exception as e:
        raise ValueError(f"取得できません（{e.__class__.__name__}）") from e
    if len(data) > MAX_BYTES:
        raise ValueError("フィードが大きすぎます")
    return parse(data)
