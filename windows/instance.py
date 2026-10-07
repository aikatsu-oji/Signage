"""すでに起動している Signage（二重起動）の確認。Windows 以外でもテストできるよう、Windows 専用の処理は含めない。"""

import json
import urllib.request


def find_running(ports=range(8080, 8090), timeout=1.0):
    """すでに動いている Signage の (ポート, 版) を返す。見つからなければ None。
    版が分からないとき（古い版は /local/config に版を含まない）は版を None とする。"""
    for port in ports:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/local/config", timeout=timeout) as r:
                cfg = json.loads(r.read().decode("utf-8"))
            return port, cfg.get("version")
        except Exception:
            continue
    return None


def duplicate_message(running_version, this_version):
    """すでに別の版が動いているときに表示する案内。同じ版なら None（そのまま設定画面を開く）"""
    if running_version == this_version:
        return None
    old = running_version or "不明（この版より古い版です）"
    return (
        "SimpleSignage はすでに起動しています。\n\n"
        f"実行中の版：{old}\n"
        f"このファイルの版：{this_version}\n\n"
        "新しい版に入れ替えるには、タスクトレイの Signage のアイコンを右クリックして「終了」を押し、"
        "もう一度このファイルを起動してください。"
    )
