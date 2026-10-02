# サイネージ Android 1.9.25 / Windows 1.8.22

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.25.apk` | Android / Fire TV 用アプリ | 1.9.25 |
| `Signage-windows-1.8.22.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.22 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **セキュリティ強化**：PIN・グループコードのロックを接続元 IP ごとにし、待ち時間を段階的に延ばす（他の端末に締め出されない）
- 端末の検出（UDP）をグループコードで署名し、偽の端末への PIN 送信を防ぐ。**グループがあるときは、全端末を更新してください**（旧版は一覧に出なくなります）
- 通信の受信に期限を設け、LAN 外は読まずに切断。管理画面の気象リストの HTML エスケープ漏れを修正
- リリースの公開は、main に取り込んだコミットだけが対象になりました

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
