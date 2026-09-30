"""再生画面（Edge の全画面）を表示するモニターの選択と、ウィンドウ位置の判定。Windows 専用の処理は含めない（テストできるように）。"""


def choose_monitor(mons, idx):
    """設定のモニター番号から、使うモニターを選ぶ。範囲外なら先頭（メイン）にする。モニターが無ければ None"""
    if not mons:
        return None
    idx = idx if isinstance(idx, int) else 0
    return mons[idx] if 0 <= idx < len(mons) else mons[0]


def needs_move(actual, monitor, tol=16):
    """ウィンドウの実際の位置 (left, top, right, bottom) が、モニターの範囲からずれているか。
    全画面表示なら、ウィンドウはモニターの範囲とほぼ一致する。"""
    left, top, right, bottom = actual
    want = (monitor["x"], monitor["y"], monitor["x"] + monitor["width"], monitor["y"] + monitor["height"])
    return any(abs(a - b) > tol for a, b in zip((left, top, right, bottom), want))


def monitor_label(m):
    return f"モニター{m['index'] + 1}（{m['width']}×{m['height']}{'・メイン' if m.get('primary') else ''}）"
