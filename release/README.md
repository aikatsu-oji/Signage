# サイネージ Android 1.22.0 / Windows 1.21.0

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.22.0.apk` | Android / Fire TV 用アプリ | 1.22.0 |
| `Signage-windows-1.21.0.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.21.0 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
「応答なし」の頻発と、応答なし時の配置の多重登録を直しました。
- 配置の追加に操作ごとの番号（reqId）を付け、端末が最初の結果を覚えるようにしました（Android・Windows）。応答が遅れて失敗に見えたときも、同じ操作をやり直して二重に登録されません。管理画面は、応答が来なければ自動で再送し、古い端末へは、状態を読み直して登録されたかを確かめます。保存中の二重押しも防ぎます。
- 管理画面は、通信の失敗を 1 回やり直し、これまで正常だった端末が 1 回だけ応答しなくても「応答なし」にしないようにしました。
- Android：接続元 IP ごとの同時接続の上限を 12 に上げました（1.20.0 で入れた 6 本の上限で、まれに接続が切られていました）。
- 多重登録の防止は、端末側もこの版に更新されて初めて、すべての効果が出ます。

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
