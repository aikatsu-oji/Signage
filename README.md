# SimpleSignage

フォルダに入れた画像・動画を一覧にして、全画面でループ再生するデジタルサイネージです。
最新の版は **Android 1.9.5 / Windows 1.8.4**（[Releases](../../releases) からダウンロードできます）。

| | 内容 |
|---|---|
| [android/](android/) | **Android アプリ**（Android / Android TV / Fire TV）。端末内・USB メモリのフォルダを再生。画面分割・天気予報・時計・テロップ・電源 ON 時の自動再生に対応 |
| [windows/](windows/) | **Windows 版**。Edge の全画面で再生し、タスクトレイに常駐。Android 版と同じ機能・同じ管理画面 |
| [signage.py](signage.py) / [player.html](player.html) | 簡易 PC 版（旧版）。PC 上のフォルダをブラウザで再生（Python 標準ライブラリのみ） |

## できること

- **再生**：フォルダ内の画像（jpg / png / gif / webp など）と動画（mp4 / webm / mkv など）を自然順でループ再生。ファイルの追加・削除は自動で反映
- **画面分割**：1画面 / 左右2分割 / 上下2分割 / メイン＋サイド（比率を選択可）。区画ごとにフォルダまたは天気予報を割り当て
- **画像・動画の表示方法**：おまかせ / 全体＋ぼかし背景 / 画面いっぱい / 全体を表示。反映前に見え方を確認できる「画像のフィット確認」
- **再生条件**：画像・動画ごとに、曜日・時間帯・期間で再生する条件を付けられる（複数指定可）
- **天気予報（気象庁）**：日ごと・週間・3時間ごとの予報を一定間隔で表示。市区町村を選べる
- **時計**：再生中に常に表示（位置・大きさを選択）
- **テロップ**：お客様の呼び出しや閉店のご案内などの流れる文字を、すぐに／予約して流す（チャイム・読み上げ付き）
- **マイクで声を届ける**：管理画面から、選んだ端末のスピーカーに声をすぐ流す
- **管理画面**：同じ Wi-Fi の PC・スマホのブラウザから、再生中のまま画像・動画の追加・削除や設定の変更ができる
  - 同じネットワークの Android 版・Windows 版を自動で見つけて「端末一覧」に表示し、ファイル・設定・テロップをまとめて送れる
  - **配信状況のモニタリング**：各端末の再生状況・テロップ・空き容量・稼働時間を確認し、異常を一覧できる
  - **設定のエクスポートと一括反映**：ある端末の設定を、ほかの端末へまとめてコピー
  - **操作できる端末の制限**：MAC アドレスで、管理画面に入れる端末を絞れる（任意）
- 電源 ON 時の自動再生、Android TV / Fire TV / STB のリモコン操作、フォルダ選択画面が無い端末向けのフォルダブラウザ（Android）

使い方の詳細は、[Android 版](android/README.md) と [Windows 版](windows/README.md) の README を参照してください。

## インストール

[Releases](../../releases) の最新のリリースから、次のファイルをダウンロードします。

| ファイル | 内容 |
|---|---|
| `SimpleSignage-android-<版>.apk` | Android / Android TV / Fire TV 用（Android 8.0 以降） |
| `SimpleSignage-windows-<版>.exe` | Windows 10 / 11 用（インストール不要・単体で動作） |
| `SHA256SUMS` | ファイルの改ざん・破損を確かめるチェックサム |

```
sha256sum -c SHA256SUMS
```

（Windows の PowerShell では `Get-FileHash <ファイル名>` の値を `SHA256SUMS` と見比べてください）

- **Android**：Google Play から入れます（クローズドテスト中は、招待されたテスターのみ。[docs/PLAY_STORE.md](docs/PLAY_STORE.md)）。Google Play の無い端末（Fire TV など）には、リリースの APK を端末に入れ、「不明なアプリのインストール」を許可します。PC から入れるときは `adb install -r SimpleSignage-android-<版>.apk`
- **Windows**：exe を起動します（初回の SmartScreen 警告は「詳細情報」→「実行」。ファイアウォールの確認は「プライベート ネットワーク」を許可）
- APK は **debug 署名** です。社外へ広く配る場合は、リリース鍵で署名してください（[docs/DISTRIBUTION.md](docs/DISTRIBUTION.md)）

## ビルド

```
cd android
gradlew assembleDebug  # APK を android/app/build/outputs/apk/debug/ に出力（build.bat は ..\dist\signage.apk にも出力）
```

JDK 17 と Android SDK（API 34）が必要です。Windows 版は `windows\build.bat`（`..\dist\SimpleSignage.exe` に出力。Python 3.10 以降が必要）です。
リリースの出し方は [docs/RELEASING.md](docs/RELEASING.md) を参照してください。

## 簡易 PC 版（旧版）

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

管理画面・テロップ・天気予報などは、Windows 版を使ってください。

## 天気予報のデータ

出典：[気象庁ホームページ](https://www.jma.go.jp/)

## ライセンス・セキュリティ・配布

- [LICENSE](LICENSE) — 本ソフトウェアは MIT ライセンス
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — 利用しているライセンスと出典
- [SECURITY.md](SECURITY.md) — 対策・既知の制限・推奨設定
- [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md) — 署名・配布・チェックリスト
- [docs/RELEASING.md](docs/RELEASING.md) — リリースの出し方
