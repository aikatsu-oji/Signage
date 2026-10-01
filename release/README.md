# リリース（配布用ファイル）

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.1.apk` | Android / Fire TV 用アプリ | 1.9.1 |
| `Signage-windows-1.8.1.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.1 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 確認方法
```
sha256sum -c SHA256SUMS
```
（Windows の PowerShell では `Get-FileHash <ファイル名>` の値を SHA256SUMS と見比べてください）

## 導入
- **Android**：APK を端末に入れ、「不明なアプリのインストール」を許可します。詳しくは [android/README.md](../android/README.md)。
- **Windows**：exe を起動します（初回の SmartScreen 警告は「詳細情報」→「実行」）。詳しくは [windows/README.md](../windows/README.md)。
- 配布時の注意（署名・ライセンス・セキュリティ）は [docs/DISTRIBUTION.md](../docs/DISTRIBUTION.md)、[SECURITY.md](../SECURITY.md) を参照してください。

## 注意
- この APK は **debug 署名** です。社外へ広く配る場合は、リリース鍵で署名し直してください（docs/DISTRIBUTION.md）。署名が変わると、入っている端末では上書きできません。
- Android 1.9.1 以降は、管理画面から APK で更新できます（端末側で許可が必要）。1.9.0 以前からは、一度手動で入れ替えてください。
