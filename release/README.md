# サイネージ Android 1.22.0 / Windows 1.20.0

| ファイル | 内容 | バージョン |
|---|---|---|
| `SimpleSignage-android-1.22.0.apk` | Android / Fire TV 用アプリ | 1.22.0 |
| `SimpleSignage-windows-1.20.0.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.20.0 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
Google Play 版に一本化：管理画面からのアプリ更新（APK の送信）の機能と、関連する権限（REQUEST_INSTALL_PACKAGES・写真と動画の読み取り）をなくしました。ソースコードの内部名も jp.simplesignage に変更しました。Google Play の無い端末（Fire TV など）には、この APK を adb で入れてください。

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
- Android は、Google Play 版でのテストに移行しています。この APK は、Google Play の無い端末（Fire TV など）用です。Google Play 版とは署名が違うため、入れ替えるときは、アプリを削除してから入れ直してください。管理画面からのアプリ更新の機能は、1.22.0 でなくしました。
