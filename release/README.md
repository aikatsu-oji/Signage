# サイネージ Android 1.21.3 / Windows 1.20.2

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.21.3.apk` | Android / Fire TV 用アプリ | 1.21.3 |
| `Signage-windows-1.20.2.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.20.2 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
セキュリティ強化：グループコードを新しく設定するとき、12 文字以上にするように（短いコードは、端末の見つけ合いの識別子から割り出されやすいため）。設定済みの 8〜11 文字のコードは、そのまま使えます。

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
- Android 1.19.0 以降は、管理画面から APK を送って更新する機能はありません。APK を端末に直接入れ直してください（`adb install -r`、または APK ファイルを端末で開く）。
