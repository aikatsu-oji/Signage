# サイネージ Android 1.9.6 / Windows 1.8.5

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.6.apk` | Android / Fire TV 用アプリ | 1.9.6 |
| `Signage-windows-1.8.5.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.5 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **画面分割の比率を 1% 刻みで自由に決められる**ようになりました（区画の大きさ 10〜90%、メインは 20〜90%）。境界線のドラッグ・矢印キー・数字の入力で変えられます。
- **「区画の縦横比を合わせる」を追加。** 縦長の画面のメインに 16:9 の横長動画をぴったり表示するなど、選んだ縦横比（16:9・4:3・1:1・3:4・9:16・21:9）になるよう、比率を自動で計算して設定します（1% 刻みなので、差は 1% ほど）。
- Android：端末の設定画面の選択肢にない比率も、そのまま保持されます。
- 管理画面から更新できるのは、1.9.5 以降の端末です。

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
