"""
フォルダ内の画像・動画をリスト化してループ再生するサイネージサーバー

使い方:
    python signage.py <フォルダ> [--port 8000] [--duration 10] [--shuffle] [--no-recursive] [--open]

ブラウザで http://localhost:8000/ を開くと再生が始まります。
フォルダ内のファイルを追加・削除すると、次のループ開始時に自動で反映されます。
"""

import argparse
import json
import mimetypes
import os
import re
import sys
import threading
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlparse

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".avif"}
VIDEO_EXTS = {".mp4", ".webm", ".ogv", ".mov", ".m4v"}

mimetypes.add_type("video/mp4", ".m4v")
mimetypes.add_type("video/quicktime", ".mov")
mimetypes.add_type("image/webp", ".webp")
mimetypes.add_type("image/avif", ".avif")

BASE_DIR = Path(__file__).resolve().parent
PLAYER_HTML = BASE_DIR / "player.html"


def natural_key(s: str):
    """'img2' < 'img10' となる自然順ソート用キー"""
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def scan_media(root: Path, recursive: bool):
    items = []
    iterator = root.rglob("*") if recursive else root.glob("*")
    for p in iterator:
        if not p.is_file() or p.name.startswith("."):
            continue
        ext = p.suffix.lower()
        if ext in IMAGE_EXTS:
            kind = "image"
        elif ext in VIDEO_EXTS:
            kind = "video"
        else:
            continue
        rel = p.relative_to(root).as_posix()
        items.append({
            "name": rel,
            "type": kind,
            "url": "/media/" + quote(rel),
            "mtime": int(p.stat().st_mtime),
        })
    items.sort(key=lambda x: natural_key(x["name"]))
    return items


def make_handler(config):
    root: Path = config["root"]

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            if config.get("verbose"):
                super().log_message(fmt, *args)

        def do_HEAD(self):
            self.do_GET(head=True)

        def do_GET(self, head=False):
            self.head_only = head
            path = urlparse(self.path).path
            if path in ("/", "/index.html"):
                return self.send_file(PLAYER_HTML, "text/html; charset=utf-8")
            if path == "/api/list":
                return self.send_list()
            if path.startswith("/media/"):
                rel = unquote(path[len("/media/"):])
                target = (root / rel).resolve()
                # フォルダ外へのアクセスを防止
                if root not in target.parents or not target.is_file():
                    return self.send_error(HTTPStatus.NOT_FOUND)
                ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
                return self.send_file(target, ctype, allow_range=True)
            self.send_error(HTTPStatus.NOT_FOUND)

        def send_list(self):
            body = json.dumps({
                "folder": str(root),
                "duration": config["duration"],
                "shuffle": config["shuffle"],
                "items": scan_media(root, config["recursive"]),
            }, ensure_ascii=False).encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if not self.head_only:
                self.wfile.write(body)

        def send_file(self, file: Path, ctype: str, allow_range=False):
            try:
                size = file.stat().st_size
                f = open(file, "rb")
            except OSError:
                return self.send_error(HTTPStatus.NOT_FOUND)
            with f:
                start, end = 0, size - 1
                range_header = self.headers.get("Range") if allow_range else None
                m = re.match(r"bytes=(\d*)-(\d*)", range_header or "")
                if m and (m.group(1) or m.group(2)):
                    if m.group(1):
                        start = int(m.group(1))
                        if m.group(2):
                            end = min(int(m.group(2)), size - 1)
                    else:  # bytes=-N (末尾Nバイト)
                        start = max(0, size - int(m.group(2)))
                    if start > end:
                        self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        self.send_header("Content-Range", f"bytes */{size}")
                        self.end_headers()
                        return
                    self.send_response(HTTPStatus.PARTIAL_CONTENT)
                    self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                else:
                    self.send_response(HTTPStatus.OK)
                length = end - start + 1
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(length))
                if allow_range:
                    self.send_header("Accept-Ranges", "bytes")
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                if self.head_only:
                    return
                f.seek(start)
                remaining = length
                try:
                    while remaining > 0:
                        chunk = f.read(min(1024 * 256, remaining))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
                except (ConnectionResetError, BrokenPipeError, ConnectionAbortedError):
                    pass  # ブラウザ側がシーク等で接続を切った場合

    return Handler


def main():
    parser = argparse.ArgumentParser(description="フォルダ内の画像・動画をループ再生するサイネージ")
    parser.add_argument("folder", nargs="?", default="media", help="再生するフォルダ (既定: ./media)")
    parser.add_argument("--port", type=int, default=8000, help="ポート番号 (既定: 8000)")
    parser.add_argument("--host", default="127.0.0.1", help="待受アドレス。LAN内の別端末から見る場合は 0.0.0.0")
    parser.add_argument("--duration", type=float, default=10, help="画像1枚あたりの表示秒数 (既定: 10)")
    parser.add_argument("--shuffle", action="store_true", help="ループごとに順番をシャッフル")
    parser.add_argument("--no-recursive", dest="recursive", action="store_false", help="サブフォルダを含めない")
    parser.add_argument("--open", action="store_true", help="起動時にブラウザを開く")
    parser.add_argument("--verbose", action="store_true", help="アクセスログを表示")
    args = parser.parse_args()

    root = Path(args.folder).expanduser().resolve()
    if not root.is_dir():
        print(f"フォルダが見つかりません: {root}", file=sys.stderr)
        sys.exit(1)

    config = {
        "root": root,
        "duration": args.duration,
        "shuffle": args.shuffle,
        "recursive": args.recursive,
        "verbose": args.verbose,
    }
    items = scan_media(root, args.recursive)
    print(f"フォルダ: {root}")
    print(f"メディア: {len(items)} 件 (画像 {sum(i['type'] == 'image' for i in items)} / "
          f"動画 {sum(i['type'] == 'video' for i in items)})")
    for i, it in enumerate(items, 1):
        print(f"  {i:3d}. [{'画像' if it['type'] == 'image' else '動画'}] {it['name']}")

    server = ThreadingHTTPServer((args.host, args.port), make_handler(config))
    url = f"http://localhost:{args.port}/"
    print(f"\n再生URL: {url}  (Ctrl+C で終了)")
    if args.open:
        threading.Timer(0.5, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n終了します")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
