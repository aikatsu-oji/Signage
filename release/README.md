# サイネージ Android 1.9.19 / Windows 1.8.18

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.19.apk` | Android / Fire TV 用アプリ | 1.9.19 |
| `Signage-windows-1.8.18.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.18 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **端末一覧に出ない端末の理由を、管理画面に表示するようにしました。** 左の「端末一覧」の下に、「見つかりましたが、一覧に出していない端末：○○（理由）」と出ます。理由は、「グループ未設定、または、古い版」「別のグループ」「グループを設定している端末（この端末は未設定）」のどれかです。
- 同じグループのはずの端末が見えないときは、まず、この表示を確認してください。1.9.14 より古い版や、グループコードを決めていない端末は、グループコードを設定した端末からは見えません（README に対処を追記）。

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
