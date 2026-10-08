#!/usr/bin/env bash
# dist/ にあるビルド済みの APK・exe を、GitHub の Releases に公開する（docs/RELEASING.md）
# 使い方: scripts/publish-release.sh "<今回の変更点（Markdown。複数行可）>"
#  版は android/app/build.gradle.kts と windows/server.py から読み取り、タグは v<Android の版> にする
set -euo pipefail
cd "$(dirname "$0")/.."
NOTES="${1:?今回の変更点を引数で渡してください}"
BRANCH="$(git branch --show-current)"
AV="$(grep -o 'versionName = "[^"]*"' android/app/build.gradle.kts | cut -d'"' -f2)"
WV="$(grep -o '^VERSION = "[^"]*"' windows/server.py | cut -d'"' -f2)"
BLOB="https://github.com/aikatsu-oji/Signage/blob/main"
TAG="v${AV}"

cp dist/signage-debug.apk "release/Signage-android-${AV}.apk"
cp dist/Signage.exe "release/Signage-windows-${WV}.exe"
(cd release && sha256sum "Signage-android-${AV}.apk" "Signage-windows-${WV}.exe" > SHA256SUMS)

cat > release/README.md <<EOT
# サイネージ Android ${AV} / Windows ${WV}

| ファイル | 内容 | バージョン |
|---|---|---|
| \`Signage-android-${AV}.apk\` | Android / Fire TV 用アプリ | ${AV} |
| \`Signage-windows-${WV}.exe\` | Windows 用アプリ（インストール不要・単体で動作） | ${WV} |
| \`SHA256SUMS\` | ファイルの改ざん・破損を確かめるチェックサム | |
| \`LICENSE\` / \`THIRD_PARTY_NOTICES.md\` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
${NOTES}

## 確認方法
\`\`\`
sha256sum -c SHA256SUMS
\`\`\`
（Windows の PowerShell では \`Get-FileHash <ファイル名>\` の値を SHA256SUMS と見比べてください）

## 導入
- **Android**：APK を端末に入れ、「不明なアプリのインストール」を許可します。詳しくは [android/README.md](${BLOB}/android/README.md)。
- **Windows**：exe を起動します（初回の SmartScreen 警告は「詳細情報」→「実行」）。詳しくは [windows/README.md](${BLOB}/windows/README.md)。
- 配布時の注意（署名・ライセンス・セキュリティ）は [docs/DISTRIBUTION.md](${BLOB}/docs/DISTRIBUTION.md)、[SECURITY.md](${BLOB}/SECURITY.md) を参照してください。

## 注意
- この APK は **debug 署名** です。社外へ広く配る場合は、リリース鍵で署名し直してください。署名が変わると、入っている端末では上書きできません。
- Android 1.19.0 以降は、管理画面から APK を送って更新する機能はありません。APK を端末に直接入れ直してください（\`adb install -r\`、または APK ファイルを端末で開く）。
EOT

# 1) ファイルを入れたコミット（Releases の添付元）  2) ファイルを消して、公開のリクエストを書いたコミット
git add release
git commit -q -m "リリース用ファイルを追加（Android ${AV} / Windows ${WV}）"
SRC="$(git rev-parse HEAD)"
git push -q origin "$BRANCH"
SUMS="$(sha256sum release/SHA256SUMS | cut -d' ' -f1)"
git rm -q release/*.apk release/*.exe release/SHA256SUMS
# 3 行目は SHA256SUMS の SHA-256。Actions はこれと照合する（main に取り込まれる＝レビューを通った値）
printf '%s\n%s\n%s\n' "$TAG" "$SRC" "$SUMS" > .github/release-request.txt
git add -A
git commit -q -m "Release ${TAG} を公開する（ファイルはコミット ${SRC:0:7}）"
git push -q origin "$BRANCH"
echo "公開を依頼しました: ${TAG}。Actions が公開するのは main に取り込まれたあとです（PR を作って main へ取り込んでください）"
