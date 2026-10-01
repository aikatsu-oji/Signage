# サイネージ Android 1.9.9 / Windows 1.8.8

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.9.apk` | Android / Fire TV 用アプリ | 1.9.9 |
| `Signage-windows-1.8.8.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.8 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **Android：動画が途中で止まったときの復旧を速くしました。** 6 秒止まったら少し先へ移動 → さらに 6 秒で読み込み直し → さらに 6 秒で次の項目へ進みます（以前は最大 24 秒ほど固まっていました）。
- **止まった動画の記録を、管理画面の「配信状況」に表示**するようにしました（動画の名前・形式・大きさ・フレームレート・ビットレート・位置・状態）。止まる動画の特徴を調べる手がかりになります。
- 「動画の互換モード」を外すと、画面の向きの補正と回転が効きます（互換モードでは回転が効きません）。

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
