"""
サイネージ（Windows 版）
タスクトレイに常駐し、再生サーバーを動かして Edge を全画面（キオスクモード）で開く。

  Signage.exe                 通常の起動
  Signage.exe --no-player     再生画面を開かずに起動（設定・管理だけ）
"""

import argparse
import ctypes
import ctypes.wintypes as wt
import os
import subprocess
import sys
import threading
import time
import webbrowser
from datetime import datetime
from pathlib import Path

import store as st
from peers import Peers
from instance import duplicate_message, find_running
from placement import choose_monitor, monitor_label, needs_move
from server import VERSION, Server, local_addresses

APP_NAME = "SignagePlayer"
EDGE_PROFILE = st.APP_DIR / "edge"


# ---------------------------------------------------------------- Windows の機能

def log(text: str):
    """動作の記録（%APPDATA%\Signage\signage.log）。モニターの切り替えなど、画面に出ない動作の確認に使う"""
    try:
        path = st.APP_DIR / "signage.log"
        st.APP_DIR.mkdir(parents=True, exist_ok=True)
        if path.exists() and path.stat().st_size > 200_000:
            path.write_text("", encoding="utf-8")
        with open(path, "a", encoding="utf-8") as f:
            f.write(f"{datetime.now():%Y-%m-%d %H:%M:%S} {text}\n")
    except Exception:
        pass


def single_instance() -> bool:
    """二重起動していなければ True"""
    handle = ctypes.windll.kernel32.CreateMutexW(None, False, "Local\\SignagePlayerMutex")
    single_instance.handle = handle  # 終了するまで保持
    return ctypes.windll.kernel32.GetLastError() != 183  # ERROR_ALREADY_EXISTS


def keep_awake(on: bool):
    """再生中は画面の消灯・スリープを防ぐ"""
    ES_CONTINUOUS, ES_SYSTEM_REQUIRED, ES_DISPLAY_REQUIRED = 0x80000000, 0x00000001, 0x00000002
    flags = ES_CONTINUOUS | (ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED if on else 0)
    ctypes.windll.kernel32.SetThreadExecutionState(flags)


def monitors() -> list:
    """接続されているモニターの一覧 [{index, name, x, y, width, height, primary}]"""
    result = []

    class MONITORINFOEXW(ctypes.Structure):
        _fields_ = [("cbSize", wt.DWORD), ("rcMonitor", wt.RECT), ("rcWork", wt.RECT),
                    ("dwFlags", wt.DWORD), ("szDevice", wt.WCHAR * 32)]

    MonitorEnumProc = ctypes.WINFUNCTYPE(ctypes.c_int, wt.HMONITOR, wt.HDC, ctypes.POINTER(wt.RECT), wt.LPARAM)

    def callback(hmon, hdc, rect, data):
        info = MONITORINFOEXW()
        info.cbSize = ctypes.sizeof(MONITORINFOEXW)
        ctypes.windll.user32.GetMonitorInfoW(hmon, ctypes.byref(info))
        r = info.rcMonitor
        result.append({
            "index": len(result),
            "name": info.szDevice.replace("\\\\.\\", ""),
            "x": r.left, "y": r.top, "width": r.right - r.left, "height": r.bottom - r.top,
            "primary": bool(info.dwFlags & 1),
        })
        return 1

    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass
    ctypes.windll.user32.EnumDisplayMonitors(None, None, MonitorEnumProc(callback), 0)
    result.sort(key=lambda m: (not m["primary"], m["x"], m["y"]))
    for i, m in enumerate(result):
        m["index"] = i
    return result


def exe_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    return f'"{Path(sys.executable).with_name("pythonw.exe")}" "{Path(__file__).resolve()}"'


def set_autostart(on: bool):
    """Windows にサインインしたときに自動で起動する（このユーザーのみ）"""
    import winreg
    key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0,
                         winreg.KEY_SET_VALUE)
    with key:
        if on:
            winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, exe_command())
        else:
            try:
                winreg.DeleteValue(key, APP_NAME)
            except FileNotFoundError:
                pass


def find_edge():
    import winreg
    for root in (winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER):
        try:
            with winreg.OpenKey(root, r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe") as k:
                path = winreg.QueryValue(k, None)
                if path and Path(path).exists():
                    return path
        except OSError:
            pass
    for base in (os.environ.get("ProgramFiles(x86)"), os.environ.get("ProgramFiles"), os.environ.get("LOCALAPPDATA")):
        if base:
            p = Path(base) / "Microsoft" / "Edge" / "Application" / "msedge.exe"
            if p.exists():
                return str(p)
    return None


# ---------------------------------------------------------------- アプリ

class App:
    def __init__(self, args):
        self.args = args
        self.first_run = not st.CONFIG_FILE.exists()
        self.store = st.Store()
        self.server = Server(self.store)
        self.server.monitors = monitors
        self.server.open_player = self.open_player
        self.server.on_monitor_changed = self.reopen_player_on_monitor
        self.server.set_autostart = set_autostart
        self.peers = None
        self.tray = None
        self.player_open = False

    def run(self):
        self.server.start()
        if not self.args.no_mdns:
            self.peers = Peers(self.store, self.server.port, VERSION, local_addresses, lambda: self.server.tls_active)
            self.server.peers = self.peers
            threading.Thread(target=self.peers.start, daemon=True).start()
        threading.Thread(target=self.schedule_loop, daemon=True).start()
        threading.Thread(target=self.time_sync_loop, daemon=True).start()
        if self.store.get("autoStart"):
            set_autostart(True)  # 実行ファイルの場所が変わっていても登録し直す
        # 「Windows の起動時に自動で開始」が ON のときだけ、起動と同時に全画面で再生を始める
        # （OFF のときはタスクトレイに常駐するだけ。再生はトレイのメニューから開く）
        if self.store.get("autoStart") and not self.args.no_player:
            self.open_player()
        if self.first_run:
            self.open_settings()
        if self.args.no_tray:
            try:
                while True:
                    time.sleep(1)
            except KeyboardInterrupt:
                self.quit()
        else:
            self.run_tray()

    def time_sync_loop(self):
        """時刻サーバーに、1 時間ごとに問い合わせる（失敗したら 5 分後に、やり直す）。起動時にも、すぐに行う"""
        while True:
            try:
                s = self.store
                if s.get("timeSync"):
                    due = int(time.time() * 1000) - int(s.get("timeSyncAt") or 0) >= 3600_000 or s.get("timeSyncError")
                    if due:
                        s.sync_time()
                        s.notify("time")
            except Exception:
                pass
            time.sleep(300)

    def schedule_loop(self):
        """1分ごとに予約したテロップの時刻を確認する"""
        while True:
            now = datetime.now()
            time.sleep(60 - now.second - now.microsecond / 1e6 + 0.2)
            try:
                self.store.check_schedules()
            except Exception:
                pass

    # -------- 再生画面（Edge のキオスクモード）

    def open_player(self):
        self.close_player()
        edge = find_edge()
        url = f"http://127.0.0.1:{self.server.local_port}/player"
        mons = monitors()
        m = choose_monitor(mons, self.store.get("monitor") or 0)
        log(f"再生画面を開きます：{monitor_label(m) if m else 'モニター情報なし'}"
            + (f" 位置({m['x']},{m['y']}) {m['width']}×{m['height']}" if m else "")
            + f"（検出したモニター {len(mons)} 台）")
        if not edge:
            webbrowser.open(url)
            return
        # モニターごとに別のプロファイルを使う（Edge は前回のウィンドウ位置をプロファイルに覚えていて、
        # 同じプロファイルだと「いつも同じモニター」に開いてしまうことがあるため）
        profile = f"{EDGE_PROFILE}-m{m['index']}" if m else str(EDGE_PROFILE)
        args = [
            edge, f"--user-data-dir={profile}", "--no-first-run", "--no-default-browser-check",
            "--kiosk", url, "--edge-kiosk-type=fullscreen",
            "--autoplay-policy=no-user-gesture-required",
            "--disable-features=Translate,msEdgeTranslate",
            "--overscroll-history-navigation=0", "--disable-pinch",
        ]
        if m:
            args += [f"--window-position={m['x']},{m['y']}", f"--window-size={m['width']},{m['height']}"]
        proc = subprocess.Popen(args, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.player_open = True
        keep_awake(True)
        if m:
            # 画面の拡大率（DPI）が違うモニターを混ぜていると、Edge の位置指定がずれることがあるため、
            # 開いたあとにウィンドウの位置を確かめて、選んだモニターに合わせる
            threading.Thread(target=self.place_player, args=(proc.pid, m), daemon=True).start()

    def profile_pids(self):
        """このアプリ用プロファイルで動いている Edge のプロセス番号（普段使いの Edge は含めない）"""
        profile = str(EDGE_PROFILE).replace("'", "''")
        ps = ("Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | "
              f"Where-Object {{ $_.CommandLine -like '*{profile}*' }} | "
              "ForEach-Object { $_.ProcessId }")
        try:
            out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True,
                                 creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
            return {int(x) for x in out.split() if x.isdigit()}
        except Exception:
            return set()

    def place_player(self, pid, m, timeout=20):
        """再生画面のウィンドウが選んだモニターに出ているか確かめ、ずれていれば動かす（Windows 専用）。
        全画面になったあとに戻されることがあるので、数回確かめる"""
        try:
            user32 = ctypes.windll.user32
            enum_proc = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
            deadline = time.time() + timeout
            checks = 0
            while time.time() < deadline and checks < 3:
                pids = self.profile_pids() | {pid}
                found = []

                def cb(hwnd, _):
                    if user32.IsWindowVisible(hwnd):
                        owner = wt.DWORD()
                        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
                        if owner.value in pids:
                            cls = ctypes.create_unicode_buffer(64)
                            user32.GetClassNameW(hwnd, cls, 64)
                            r = wt.RECT()
                            user32.GetWindowRect(hwnd, ctypes.byref(r))
                            if cls.value.startswith("Chrome_WidgetWin") and r.right - r.left > 300 and r.bottom - r.top > 200:
                                found.append(hwnd)
                    return True

                user32.EnumWindows(enum_proc(cb), 0)
                if not found:
                    time.sleep(0.5)
                    continue
                time.sleep(1.5)  # 全画面表示になるのを待つ
                checks += 1
                for hwnd in found:
                    r = wt.RECT()
                    user32.GetWindowRect(hwnd, ctypes.byref(r))
                    actual = (r.left, r.top, r.right, r.bottom)
                    moved = needs_move(actual, m)
                    log(f"再生画面の位置 {actual} / {monitor_label(m)} の範囲 → {'移動します' if moved else 'そのまま'}")
                    if moved:
                        # SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW
                        user32.SetWindowPos(hwnd, 0, m["x"], m["y"], m["width"], m["height"], 0x0004 | 0x0010 | 0x0040)
                time.sleep(1.5)
            if not checks:
                log("再生画面のウィンドウが見つかりませんでした（位置の確認を省略）")
        except Exception as e:  # noqa
            log(f"再生画面の位置の確認に失敗しました: {e}")

    def reopen_player_on_monitor(self):
        """表示するモニターを変えたとき、再生画面が開いていれば、新しいモニターで開き直す"""
        log(f"表示するモニターの切り替え要求：設定={self.store.get('monitor')}、再生画面は{'開いています' if self.player_open else '開いていません'}")
        try:
            if self.player_open:
                self.open_player()
        except Exception as e:  # noqa
            log(f"再生画面をモニターを変えて開き直せませんでした: {e!r}")

    def set_monitor(self, index):
        """トレイのメニューから、表示するモニターを切り替える"""
        self.store.update({"monitor": int(index)})
        self.store.notify("settings")
        self.reopen_player_on_monitor()

    def close_player(self):
        """このアプリ用のプロファイルで開いた Edge だけを閉じる（普段使いの Edge には触れない）"""
        profile = str(EDGE_PROFILE).replace("'", "''")
        ps = ("Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | "
              f"Where-Object {{ $_.CommandLine -like '*{profile}*' }} | "
              "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; "
              # 完全に終了するまで待つ（残っていると、新しい Edge が古いものに引き継がれて位置が変わらない）
              "for ($i=0; $i -lt 20; $i++) { "
              "if (-not (Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | "
              f"Where-Object {{ $_.CommandLine -like '*{profile}*' }})) {{ break }}; Start-Sleep -Milliseconds 250 }}")
        subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), capture_output=True)
        if self.player_open:
            self.player_open = False
            keep_awake(False)

    def reset_access(self):
        """タスクトレイから、操作できる端末の制限を解除する"""
        if not self.store.get("macLock") and not self.store.get("allowedMacs"):
            return
        try:
            ok = ctypes.windll.user32.MessageBoxW(
                0, "操作できる端末の制限を解除し、登録した端末の一覧を消します。よろしいですか？", "サイネージ", 0x1 | 0x20 | 0x40000) == 1
        except Exception:
            ok = True
        if ok:
            self.server.reset_access()
            log("操作できる端末の制限を解除しました（タスクトレイから）")

    def open_settings(self):
        webbrowser.open(f"http://127.0.0.1:{self.server.local_port}/settings")

    def open_admin(self):
        webbrowser.open(f"http://127.0.0.1:{self.server.local_port}/")

    def quit(self):
        self.close_player()
        if self.peers:
            self.peers.stop()
        self.server.stop()
        keep_awake(False)
        if self.tray:
            self.tray.stop()

    # -------- タスクトレイ

    def run_tray(self):
        import pystray
        from PIL import Image, ImageDraw

        img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        d.rounded_rectangle([4, 10, 60, 48], 6, fill=(30, 136, 229, 255))
        d.polygon([(26, 19), (26, 39), (42, 29)], fill="white")
        d.rectangle([22, 52, 42, 57], fill=(30, 136, 229, 255))

        def item(text, fn, default=False):
            return pystray.MenuItem(text, lambda icon, _: fn(), default=default)

        def autostart_checked(_):
            return bool(self.store.get("autoStart"))

        def toggle_autostart(icon, _):
            on = not self.store.get("autoStart")
            set_autostart(on)
            self.store.update({"autoStart": on})

        def monitor_items():
            mons = monitors()
            cur = choose_monitor(mons, self.store.get("monitor") or 0)
            for m in mons:
                yield pystray.MenuItem(
                    monitor_label(m),
                    (lambda i: lambda icon, _: self.set_monitor(i))(m["index"]),
                    checked=(lambda idx: lambda _: bool(cur) and cur["index"] == idx)(m["index"]),
                    radio=True,
                )

        menu = pystray.Menu(
            item("設定を開く", self.open_settings, default=True),
            item("再生画面を開く（全画面）", self.open_player),
            item("再生画面を閉じる", self.close_player),
            pystray.MenuItem("表示するモニター", pystray.Menu(monitor_items)),
            item("管理画面を開く", self.open_admin),
            item("操作できる端末の制限を解除", self.reset_access),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Windows の起動時に自動で開始", toggle_autostart, checked=autostart_checked),
            pystray.Menu.SEPARATOR,
            item("終了", self.quit),
        )
        self.tray = pystray.Icon(APP_NAME, img, f"サイネージ v{VERSION}（{self.store.device_name}）", menu)

        def ready(icon):
            icon.visible = True
            # 全画面を開かずに起動したときは、常駐したことが分かるように通知する
            if not self.store.get("autoStart") and not self.first_run:
                try:
                    icon.notify("タスクトレイのアイコンから、再生画面や設定を開けます。", "サイネージを起動しました")
                except Exception:
                    pass

        self.tray.run(setup=ready)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--no-player", action="store_true", help="再生画面を開かない")
    p.add_argument("--no-tray", action="store_true", help="タスクトレイを使わない（テスト用）")
    p.add_argument("--no-mdns", action="store_true", help="端末の検出を行わない（テスト用）")
    p.add_argument("--test-instance", action="store_true", help="二重起動の確認をしない（開発中のテスト用）")
    args = p.parse_args()
    if not args.test_instance and not single_instance():
        running = find_running()
        msg = duplicate_message(running[1] if running else None, VERSION)
        if msg:
            # 別の版が動いたままだと、新しい版を起動したつもりでも古い版の画面が開くため、はっきり知らせる
            try:
                # MB_ICONINFORMATION | MB_SETFOREGROUND | MB_TOPMOST
                ctypes.windll.user32.MessageBoxW(0, msg, "サイネージ", 0x40 | 0x10000 | 0x40000)
            except Exception:
                pass
        elif running:
            # 同じ版がすでに動いているときは、設定画面を開くだけ
            webbrowser.open(f"http://127.0.0.1:{running[0]}/settings")
        return
    App(args).run()


if __name__ == "__main__":
    main()
