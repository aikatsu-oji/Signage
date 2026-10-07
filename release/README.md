# サイネージ Android 1.21.0 / Windows 1.19.0

| ファイル | 内容 | バージョン |
|---|---|---|
| `SimpleSignage-android-1.21.0.apk` | Android / Fire TV 用アプリ | 1.21.0 |
| `SimpleSignage-windows-1.19.0.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.19.0 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
アプリ ID を jp.signage.player から jp.simplesignage に変更しました（Google Play 公開に向けて）。**別のアプリとして入る**ため、いま使っている端末の更新は、これまでの APK への上書きではできません（管理画面からのアプリ更新も使えません）。新しい APK を入れ直し、画像・動画の登録と設定をやり直してください。古いアプリは、端末から削除してかまいません。

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
