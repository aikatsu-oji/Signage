# サイネージ Android 1.9.17 / Windows 1.8.16

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.17.apk` | Android / Fire TV 用アプリ | 1.9.17 |
| `Signage-windows-1.8.16.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.16 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **アプリの時刻を、端末（PC）のシステムの設定とは別に決められるようになりました。** 管理画面の「端末の設定」の「時計」で、**時刻の形式**（24 時間／12 時間）、**タイムゾーン**（日本、アメリカ各地など）、**時刻のずれの補正**（分）を設定します。初期は、これまでどおり端末の設定のままです。
- 時計の表示、ファイルの再生条件（時間帯・曜日・期間）、予約テロップの時刻に反映されます（天気予報の時刻は、予報の発表に合わせて日本時間）。設定画面の下に、「いま、アプリは○○と考えています」と表示されます。
- Android の端末の設定画面には、この項目はありません（管理画面から設定します）。

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
