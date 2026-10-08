# サイネージ Android 1.20.0 / Windows 1.19.0

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.20.0.apk` | Android / Fire TV 用アプリ | 1.20.0 |
| `Signage-windows-1.19.0.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.19.0 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
脆弱性診断の指摘（中）3 件を修正しました。
- 管理画面で他の端末のファイルを新しいタブで開くとき、画像（SVG を除く）と動画だけ開くようにしました。SVG・HTML のスクリプトが管理画面と同じオリジンで動き、保存してある PIN を読まれるのを防ぎます。
- 遅い接続・何も送らない接続で、管理画面を止められないようにしました。接続数に上限（Windows は全体 256・接続元 IP ごとに 32、Android は接続元 IP ごとに 6）を設け、ヘッダーの受信は総時間 10 秒で切ります。
- UDP の知らせで、「一覧に出さない端末」の記録を 200 件まで・60 秒で消すようにし、署名の無い知らせで、一覧にある端末を消せないようにしました。

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
