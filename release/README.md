# サイネージ Android 1.9.2 / Windows 1.8.2

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.2.apk` | Android / Fire TV 用アプリ | 1.9.2 |
| `Signage-windows-1.8.2.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.2 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- 配信状況で、監視したくない端末を「監視対象外」にできます（`📊 配信状況` タブ）。
- 直近の主な追加：配信状況のモニタリング、管理画面からの Android アプリ更新、天気予報の地域設定、管理画面に入れる端末の制限（MAC アドレス）、テロップのボタン編集。

## 確認方法
```
sha256sum -c SHA256SUMS
```
（Windows の PowerShell では `Get-FileHash <ファイル名>` の値を SHA256SUMS と見比べてください）

## 導入
- **Android**：APK を端末に入れ、「不明なアプリのインストール」を許可します。詳しくは [android/README.md](https://github.com/aikatsu-oji/Signage/blob/claude/upbeat-fermi-dux9hk/android/README.md)。
- **Windows**：exe を起動します（初回の SmartScreen 警告は「詳細情報」→「実行」）。詳しくは [windows/README.md](https://github.com/aikatsu-oji/Signage/blob/claude/upbeat-fermi-dux9hk/windows/README.md)。
- 配布時の注意（署名・ライセンス・セキュリティ）は [docs/DISTRIBUTION.md](https://github.com/aikatsu-oji/Signage/blob/claude/upbeat-fermi-dux9hk/docs/DISTRIBUTION.md)、[SECURITY.md](https://github.com/aikatsu-oji/Signage/blob/claude/upbeat-fermi-dux9hk/SECURITY.md) を参照してください。

## 注意
- この APK は **debug 署名** です。社外へ広く配る場合は、リリース鍵で署名し直してください。署名が変わると、入っている端末では上書きできません。
- Android 1.9.1 以降は、管理画面から APK で更新できます（端末側で許可が必要）。1.9.0 以前からは、一度手動で入れ替えてください。
