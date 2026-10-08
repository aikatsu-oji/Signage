# サードパーティ ライセンス表示

本ソフトウェアは下記のコンポーネントを利用しています。配布時は本ファイルを同梱してください。
※ これは調査に基づく整理であり、法的助言ではありません。商用・大規模配布の前には専門家の確認を推奨します。

## Windows 版（実行ファイルに同梱）

| コンポーネント | ライセンス | 備考 |
|---|---|---|
| Python | PSF License | 実行環境として同梱 |
| zeroconf | LGPL-2.1-or-later | 下記「LGPL の義務」参照 |
| pystray | LGPL-3.0 | 下記「LGPL の義務」参照 |
| Pillow | MIT-CMU (HPND) | 著作権表示を保持 |
| PyInstaller | GPL-2.0 + 例外 | ブートローダーは出力物にGPLを及ぼさない例外付き |
| tzdata | Apache-2.0 | タイムゾーンのデータ（IANA） |

### LGPL の義務（zeroconf / pystray）
- ライセンス全文と著作権表示を同梱する。
- 利用者が LGPL 部分を差し替えて再ビルドできること。本リポジトリは `windows/build.bat` と
  `windows/requirements.txt` でソースからの再ビルド手順を公開しているため、リポジトリを併せて配布（または入手先を明記）すれば要件を満たします。
- exe は `--onefile` のため、LGPL 部分の差し替えは再ビルドで行う形になります。
- 各ライセンス全文の入手先: PyPI の各プロジェクトページ、または各リポジトリの LICENSE。

## Android 版

| コンポーネント | ライセンス |
|---|---|
| AndroidX（core, appcompat, media3 など） | Apache-2.0 |
| Kotlin stdlib 1.9.x | Apache-2.0 |
| kotlinx-coroutines 1.6.x | Apache-2.0 |
| Guava (android) 33.0.0 / failureaccess / listenablefuture | Apache-2.0 |
| org.jetbrains:annotations 13.0 | Apache-2.0 |

Apache-2.0: ライセンス全文（https://www.apache.org/licenses/LICENSE-2.0）への参照を同梱してください。

## データの出典
- 気象情報: **気象庁ホームページ**（https://www.jma.go.jp/bosai/forecast/）。
  気象庁コンテンツは出典を明示すれば利用でき、加工した場合はその旨の記載が必要です（政府標準利用規約 第2.0版）。
  本アプリは画面に「出典：気象庁ホームページ（…）のデータを加工して表示」と常時表示します。**この表示を消さないでください。**

- 地図の背景（天気予報の「地方ごとの一覧」）: **Natural Earth**（https://www.naturalearthdata.com/）の行政区画データ（ne_10m_admin_1_states_provinces）。
  パブリックドメインで、利用に出典の表示は不要です（本アプリは、簡略化して `weather_map.json` に収録しています。作り方は `scripts/build-weather-map.py`）。

## ユーザーが配置する素材
画像・動画・音声・BGM などの著作権・肖像権・音楽著作権（JASRAC 等）は利用者の責任です。
店舗での BGM 再生・公衆への放送には別途許諾が必要な場合があります。
