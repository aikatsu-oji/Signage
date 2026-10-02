# サイネージ Android 1.9.16 / Windows 1.8.15

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.9.16.apk` | Android / Fire TV 用アプリ | 1.9.16 |
| `Signage-windows-1.8.15.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.8.15 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- **「画面を回す」を、Google TV・Android TV でも使えるようにしました**（これまでは Fire TV 専用）。設定画面・管理画面の「画面を回す（テレビ用）」を、「右に90°」か「左に90°」にすると、再生画面をアプリの中で回して、縦置きのテレビに合わせて表示します。テレビ以外（スマホ・タブレット）では、この項目は出ません。
- 画面が、すでに縦長になっている端末では、二重に回さないよう、回しません。
- 【注意】Google TV の実機では、確認できていません。向きが逆・二重に回るなど、おかしいときは、お知らせください。

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
