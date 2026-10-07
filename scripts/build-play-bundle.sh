#!/usr/bin/env bash
# Google Play に出す AAB（署名つき）を作る（docs/PLAY_STORE.md）
# 事前に android/keystore.properties（アップロード鍵の場所とパスワード）が必要。鍵は Git に入れない
set -euo pipefail
cd "$(dirname "$0")/../android"
[ -f keystore.properties ] || { echo "android/keystore.properties がありません（docs/PLAY_STORE.md の手順 1 を見てください）"; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
sh ./gradlew bundleRelease
OUT="app/build/outputs/bundle/release/app-release.aab"
V="$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)"
mkdir -p ../dist && cp "$OUT" "../dist/SimpleSignage-play-${V}.aab"
echo "完成: dist/SimpleSignage-play-${V}.aab"
