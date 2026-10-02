# サイネージ Android 1.9.13 / Windows 1.8.12

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.13.apk` | Android / Fire TV 用アプリ | 1.9.13 |
| `Signage-windows-1.8.12.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.12 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **通信を HTTPS（暗号化）にできるようになりました（初期は OFF）。** 端末の設定画面の「通信を HTTPS（暗号化）にする」を ON にすると、管理画面の通信（PIN・画像・設定）が暗号化されます。Windows は、切り替えたあと、アプリの起動し直しが必要です（Android は自動で起動し直します）。
- 証明書は、端末が作る自己署名のものです。**管理画面を開くブラウザに、初回は警告が出ます。** 端末の設定画面と管理画面に出る SHA-256 フィンガープリントを見比べてから許可してください。
- 管理画面は、HTTPS の端末の一覧に「🔒 HTTPS」と表示します。証明書をまだ許可していない端末には、許可用のリンクを案内します。
- Windows：HTTPS のとき、この PC 自身（再生画面・設定画面）は、この PC の中だけの HTTP（127.0.0.1）を使います。
- 詳しくは SECURITY.md を参照してください。

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
