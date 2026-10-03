#!/usr/bin/env bash
# 同じネットワーク上の複数の Android 端末へ、adb で APK をまとめてインストールする
# 使い方: scripts/install-adb.sh <APK> <端末一覧ファイル>
#         scripts/install-adb.sh <APK> <IP[:ポート]> [<IP[:ポート]> ...]
#  端末一覧ファイルは 1 行に 1 台（"192.168.1.101" や "192.168.1.102:5555  # 入口"）。
#  # 以降はコメント、空行は無視。ポートを省略すると 5555。例は scripts/devices.example.txt
#  環境変数 ADB で adb のパスを変更できる。上書きインストール(-r)なので設定や許可は残る
set -uo pipefail

APK="${1:-}"
[ -n "$APK" ] && [ -f "$APK" ] || { echo "使い方: $0 <APK> <端末一覧ファイル | IP...>" >&2; exit 2; }
shift
[ $# -gt 0 ] || { echo "端末一覧ファイルか IP を指定してください" >&2; exit 2; }

ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null || { echo "adb が見つかりません（platform-tools を入れて PATH を通してください）" >&2; exit 2; }

# 端末一覧の読み込み（ファイルならその中身、そうでなければ引数をそのまま IP として扱う）
TARGETS=()
if [ $# -eq 1 ] && [ -f "$1" ]; then
  while IFS= read -r line; do
    line="${line%%#*}"
    line="$(echo "$line" | tr -d '[:space:]')"
    [ -n "$line" ] && TARGETS+=("$line")
  done < "$1"
else
  TARGETS=("$@")
fi
[ ${#TARGETS[@]} -gt 0 ] || { echo "端末が 1 台も指定されていません" >&2; exit 2; }

OK=(); NG=()
for t in "${TARGETS[@]}"; do
  case "$t" in *:*) ;; *) t="$t:5555" ;; esac
  echo "=== $t ==="
  if ! timeout 15 "$ADB" connect "$t" 2>&1 | grep -qiE 'connected to|already connected'; then
    echo "  接続できません（IP・同じネットワークか・ADB デバッグがオンかを確認）"; NG+=("$t (接続失敗)"); continue
  fi
  # 初回は端末側で「USB デバッグを許可」を押すまで unauthorized になる
  state="$("$ADB" -s "$t" get-state 2>&1 || true)"
  if [ "$state" != "device" ]; then
    echo "  状態: $state（端末の画面で接続の許可を押してから、もう一度実行してください）"; NG+=("$t ($state)"); continue
  fi
  if out="$("$ADB" -s "$t" install -r "$APK" 2>&1)" && echo "$out" | grep -q '^Success'; then
    echo "  インストール成功"; OK+=("$t")
  else
    echo "$out" | sed 's/^/  /'; NG+=("$t (インストール失敗)")
  fi
  "$ADB" disconnect "$t" >/dev/null 2>&1 || true
done

echo
echo "成功: ${#OK[@]} 台 / 失敗: ${#NG[@]} 台"
for t in "${NG[@]:-}"; do [ -n "$t" ] && echo "  失敗: $t"; done
[ ${#NG[@]} -eq 0 ]
