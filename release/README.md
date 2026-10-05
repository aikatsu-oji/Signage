# サイネージ Android 1.9.31 / Windows 1.8.28

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.31.apk` | Android / Fire TV 用アプリ | 1.9.31 |
| `Signage-windows-1.8.28.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.28 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- 天気予報に「地方ごとの一覧」を追加（全国・北海道・東北・関東甲信・北陸・東海・近畿・中国・四国・九州・沖縄。日本地図のように、府県の天気を位置に合わせたタイルで 1 画面に表示。きょう／あした／両方を選べます）
- 1.9.30 の「追加の地域」（個別に追加する方式）は、この表示に置き換え

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
