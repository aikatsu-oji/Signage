# サイネージ Android 1.9.11 / Windows 1.8.10

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.11.apk` | Android / Fire TV 用アプリ | 1.9.11 |
| `Signage-windows-1.8.10.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.10 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **Android（Fire TV 専用）：画面を回して縦向きで表示できるようにしました。** 設定画面と管理画面（画面分割）に「画面を回す（Fire TV 用）」を追加しました（回さない／右に90°／左に90°）。Fire OS は縦向きの指定を無視するため、有効にしたときだけ、再生画面そのものを回して表示します。初期値は「回さない」で、Fire TV 以外では表示されません。
- 画面の向きの選択を、設定画面の一番上に移しました。
- Windows：管理画面を共通の新しいものに更新しました（操作は変わりません）。

## 確認方法
```
sha256sum -c SHA256SUMS
```
（Windows の PowerShell では `Get-FileHash <ファイル名>` の値を SHA256SUMS と見比べてください）

## 導入
- **Android**：APK を端末に入れ、「不明なアプリのインストール」を許可します。詳しくは [android/README.md](https://github.com/aikatsu-oji/Signage/blob/main/android/README.md)。
- **Windows**：exe を起動します（初回の SmartScreen 警告は「詳細情報」→「実行」）。詳しくは [windows/README.md](https://github.com/aikatsu-oji/Signage/blob/main/windows/README.md)。
- 配布時の注意（署名・ライセンス・セキュリティ）は [docs/DISTRIBUTION.md](https://github.com/aikatsu-oji/Signage/blob/main/docs/DISTRIBUTION.md)、[SECURITY.md](https://github.com/aikatsu-oji/Signage/blob/main/SECURITY.md) を参照してください。

## 注意
- この APK は **debug 署名** です。社外へ広く配る場合は、リリース鍵で署名し直してください。署名が変わると、入っている端末では上書きできません。
- Android 1.9.5 以降は、管理画面から APK で更新できます（端末側で許可が必要）。1.9.4 以前からは、一度手動で入れ替えてください。
