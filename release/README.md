# サイネージ Android 1.9.18 / Windows 1.8.17

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.18.apk` | Android / Fire TV 用アプリ | 1.9.18 |
| `Signage-windows-1.8.17.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.17 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **アプリが、時刻サーバーに定期的に問い合わせて、時刻を合わせるようになりました（初期は ON）。** 端末を長く動かしていて時計がずれても、アプリの時計の表示・ファイルの再生条件・予約テロップの時刻が、正しく保たれます。1 時間ごと（失敗したときは 5 分ごと、起動時にも）に、NTP の時刻サーバー（初期は ntp.nict.jp）に問い合わせます。
- NTP（UDP 123）が塞がれたネットワークでは、HTTPS の応答の Date ヘッダーで代わりに合わせます（精度は約 1 秒）。
- 端末（PC）のシステムの時計は、変えません。アプリの時刻だけを合わせます。
- 管理画面の「端末の設定」の「時計」で、ON/OFF、時刻サーバー、「今すぐ時刻サーバーに問い合わせる」を操作できます。最後に合わせた時刻と、ずれの大きさが表示されます。
- NTP は暗号化・認証されないため、気になる場合は、同期を OFF にして手動の補正にしてください（SECURITY.md）。

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
