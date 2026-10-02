# サイネージ Android 1.9.15 / Windows 1.8.14

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.15.apk` | Android / Fire TV 用アプリ | 1.9.15 |
| `Signage-windows-1.8.14.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.14 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **【重要】グループコードが未設定の端末は、その端末自身からしか操作できなくなりました。** LAN の別の端末（スマホ・別の PC）の管理画面からは、グループコードを決めるまで、この端末を操作できません（403）。Windows はその PC のブラウザの管理画面、Android は端末の設定画面の「この端末で管理画面を開く」は、そのまま使えます。
- **アップデートする前に、グループコードを決めてください。** 管理画面から更新する場合は、先に、1.9.14 の管理画面の「🔒 入れる端末」タブで、全端末にまとめてグループコードを設定してください。決めずに更新すると、更新後は、端末の画面（Windows は設定画面、Android は端末の設定画面）でコードを決めるまで、管理画面から届きません。
- 決めたあとは、これまでどおり、管理画面から操作できます（PIN とグループコードの両方が必要）。

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
