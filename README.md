# Signage

フォルダに入れた画像・動画を一覧にして、全画面でループ再生するデジタルサイネージです。

| | 内容 |
|---|---|
| [android/](android/) | **Android アプリ**（メイン）。端末内・USBメモリのフォルダを再生。画面分割・天気予報・時計・電源ON時の自動再生に対応 |
| [windows/](windows/) | **Windows 版**（Signage.exe）。Edge の全画面で再生し、タスクトレイに常駐。Android 版と同じ機能・同じ管理画面で、まとめて管理できる |
| [signage.py](signage.py) / [player.html](player.html) | 簡易 PC 版（旧版）。PC 上のフォルダをブラウザで再生（Python 標準ライブラリのみ） |

## Android 版

詳しくは [android/README.md](android/README.md) を参照してください。

- フォルダ内の画像（jpg / png / gif / webp など）と動画（mp4 / webm / mkv など）を自然順でループ再生。ファイルの追加・削除は自動で反映
- 画面分割：1画面 / 左右2分割 / 上下2分割 / メイン＋サイド（比率を選択可）。区画ごとにフォルダまたは天気予報を割り当て
- 天気予報（気象庁）：日ごと・週間・3時間ごとの予報を一定間隔で表示。市区町村を選んで表示
- 再生中の時計表示（位置・大きさを選択）
- 管理画面：同じ Wi-Fi の PC・スマホのブラウザから、再生中のまま画像・動画の追加・削除や設定の変更。複数台を自動で見つけて一覧表示し、ファイル・設定をまとめて送れる
- テロップ：お客様の呼び出しや閉店のご案内などの流れる文字を、管理画面からすぐに／予約して流す（チャイム・読み上げ付き）
- 電源 ON 時の自動再生、Android TV / Fire TV / STB のリモコン操作、フォルダ選択画面が無い端末向けのフォルダブラウザ

### インストール

[Releases](../../releases) の `signage.apk` を端末にインストールしてください（Android 8.0 以降）。
Windows 版は同じページの `Signage.exe` をダウンロードして実行してください（詳しくは [windows/README.md](windows/README.md)）。

### ビルド

```
cd android
gradlew assembleDebug
```

JDK 17 と Android SDK（API 34）が必要です。`android/build.bat` も使えます。

## PC 版

```
python signage.py <フォルダ> [--duration 10] [--shuffle] [--open]
```

ブラウザで `http://localhost:8000/` を開くと再生が始まります。`start.bat` にフォルダをドラッグ＆ドロップしても起動できます。
LAN 内の別端末（Android のブラウザなど）から表示する場合は `--host 0.0.0.0` を付けてください。

| キー | 動作 |
|---|---|
| → / ← | 次へ / 前へ |
| Space | 一時停止 / 再開 |
| F / ダブルクリック | 全画面 |
| M | 動画の音声 ON/OFF |
| I | 情報表示 |

## 天気予報のデータ

出典：[気象庁ホームページ](https://www.jma.go.jp/)

## ライセンス・セキュリティ・配布
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — 利用しているライセンスと出典
- [SECURITY.md](SECURITY.md) — 対策・既知の制限・推奨設定
- [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md) — 署名・配布・チェックリスト
