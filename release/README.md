# サイネージ Android 1.21.0 / Windows 1.20.0

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.21.0.apk` | Android / Fire TV 用アプリ | 1.21.0 |
| `Signage-windows-1.20.0.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.20.0 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
脆弱性診断の残りの指摘（低）と、リリースの仕組みの指摘に対応しました。
- Windows 版：RSS の転送先を http / https に限り、この PC 自身の LAN アドレスも取得先にできないようにしました。天気の地域コードの検証、Windows の予約名のファイル名の拒否、500 エラーでの例外の文言の非表示、MAC 制限の確認の同時実行の制限、*.local の Host の拒否（内部用の URL）、設定画面のエスケープ、Web 区画の iframe の sandbox を追加しました。
- 管理画面：手動で追加する端末を LAN 内のアドレスに限り、端末の不正な形の応答で状況監視が止まらないようにし、設定ファイルの反映の確認に時刻サーバーを表示するようにしました。
- Android 版：PIN の失敗回数を、IPv6 は /64 でまとめて数えるようにしました。
- リリースの仕組み：Action を SHA で固定し、公開済みのタグの上書きや、チェックサムに載っていないファイルの公開を拒否するようにしました。依存の版も固定しました。

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
