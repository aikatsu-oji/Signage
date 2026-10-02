# サイネージ Android 1.9.14 / Windows 1.8.13

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.14.apk` | Android / Fire TV 用アプリ | 1.9.14 |
| `Signage-windows-1.8.13.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.13 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **グループ（組織）コードを追加しました。** 管理画面の「🔒 入れる端末」タブで、全端末に同じコードを設定すると、**コードを知る管理画面だけ**が操作できます（PIN に加えて必要）。同じネットワークにある**別のグループのサイネージは、端末一覧に出なくなります**。
- コードは、英数字と - _ の 8〜32 文字。設定すると、そのブラウザがコードを覚え、別の PC・スマホからは、ログインのときに入力します。コードを間違えた回数も、PIN と同じく 5 回でロックします。
- 忘れたときは、端末側（Windows は設定画面、Android は端末の設定画面）で解除できます。
- **HTTPS と併用してください。** HTTP だと、コードが同じ LAN 内で盗み見られる可能性があります。コードは、推測されにくい長さ（12 文字以上）にしてください。

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
