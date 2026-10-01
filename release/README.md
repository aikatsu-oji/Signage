# サイネージ Android 1.9.5 / Windows 1.8.4

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.5.apk` | Android / Fire TV 用アプリ | 1.9.5 |
| `Signage-windows-1.8.4.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.4 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **Android：管理画面からのアプリ更新の不具合を修正**しました。更新の結果が端末から返らず、「端末で確認・インストール中…」のまま止まっていました。
- **1.9.5 への更新だけは、APK を端末に直接入れてください**（1.9.4 以前は、管理画面から更新できません）。1.9.5 以降は、管理画面から更新できます。
- 管理画面で、45 秒たっても端末から結果が返らないときの案内を追加しました。

## 確認方法
```
sha256sum -c SHA256SUMS
```
（Windows の PowerShell では `Get-FileHash <ファイル名>` の値を SHA256SUMS と見比べてください）

## 導入
- **Android**：APK を端末に入れ、「不明なアプリのインストール」を許可します。詳しくは [android/README.md](https://github.com/aikatsu-oji/Signage/blob/claude/upbeat-fermi-dux9hk/android/README.md)。
- **Windows**：exe を起動します（初回の SmartScreen 警告は「詳細情報」→「実行」）。詳しくは [windows/README.md](https://github.com/aikatsu-oji/Signage/blob/claude/upbeat-fermi-dux9hk/windows/README.md)。
- 配布時の注意（署名・ライセンス・セキュリティ）は [docs/DISTRIBUTION.md](https://github.com/aikatsu-oji/Signage/blob/claude/upbeat-fermi-dux9hk/docs/DISTRIBUTION.md)、[SECURITY.md](https://github.com/aikatsu-oji/Signage/blob/claude/upbeat-fermi-dux9hk/SECURITY.md) を参照してください。

## 注意
- この APK は **debug 署名** です。社外へ広く配る場合は、リリース鍵で署名し直してください。署名が変わると、入っている端末では上書きできません。
- Android 1.9.5 以降は、管理画面から APK で更新できます（端末側で許可が必要）。1.9.4 以前からは、一度手動で入れ替えてください。
