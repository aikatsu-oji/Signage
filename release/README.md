# サイネージ Android 1.11.0 / Windows 1.10.0

| ファイル | 内容 | バージョン |
|---|---|---|
| `Signage-android-1.11.0.apk` | Android / Fire TV 用アプリ | 1.11.0 |
| `Signage-windows-1.10.0.exe` | Windows 用アプリ（インストール不要・単体で動作） | 1.10.0 |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム | |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` | 本ソフトウェア（MIT）と、利用している部品のライセンス | |

## 今回の変更
- 管理画面に「🖼 コンテンツ」（画像・動画のライブラリ。ドラッグ＆ドロップでまとめて登録、複数端末へ同時登録、検索・絞り込み・複数選択、回転、削除）と「🗓 スケジュール」（区画ごとの週間表。ライブラリから曜日・時間にドラッグして配置、条件の編集、画像の表示秒数、「専用」、再生順の並べ替え、予約テロップの時刻も表示）を追加
- 配置を新しい形式に変更（同じファイルを、複数の区画・時間帯に配置可能）。旧データは自動で移行
- 専用の配置が有効な時間帯は、専用の配置だけを再生

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
