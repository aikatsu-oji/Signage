# 配布ガイド

## 1. ライセンス
- 自作部分のライセンスは **MIT**（ルートの `LICENSE`）。
-  `THIRD_PARTY_NOTICES.md` を同梱し、LGPL（zeroconf, pystray）の再ビルド手段（`windows/build.bat`）を提供する。

## 2. Android（APK）
### リリース署名（正式配布前に必須）
現在のビルドは debug キーで署名されます。外部配布する場合は専用のリリース鍵を作ります。
```
keytool -genkeypair -v -keystore signage-release.jks -alias signage \
  -keyalg RSA -keysize 4096 -validity 10000
cp android/keystore.properties.example android/keystore.properties   # 値を記入
cd android && ./gradlew assembleRelease
```
- `*.jks` と `keystore.properties` は `.gitignore` 済み。**鍵とパスワードは別の場所にバックアップ**（紛失すると更新配布不可）。
- ⚠ 署名を変えると、既に入っている端末では **上書きインストールできません**（一度アンインストール → 設定が消える。事前にエクスポートを）。
  すでに debug 鍵で配布済みの端末が多い場合は、移行計画を立ててから切り替える。
- 配布先の端末では「提供元不明のアプリ」許可が必要。

## 3. Windows（exe）
- 未署名の exe は **SmartScreen 警告**や一部アンチウイルスの誤検知（PyInstaller 製の特性）が出ます。
  対策: コードサイニング証明書で署名、または配布先で例外登録。
- 配布前に https://www.virustotal.com 等で確認。
- 起動中の旧版を終了してから差し替える（トレイ → 終了）。

## 4. 配布物の完全性
```
cd dist && sha256sum * > SHA256SUMS
```
SHA256SUMS を同じ場所に置き、受け取り側が `sha256sum -c` で検証できるようにする。

## 5. 配布前チェックリスト
- [ ] 履歴・ファイルに鍵・パスワード・個人情報がない（確認済み）
- [ ] LICENSE（MIT）と THIRD_PARTY_NOTICES.md を同梱
- [ ] Android をリリース鍵で署名（debuggable でない）
- [ ] Windows exe を署名 or 警告の案内を用意
- [ ] 初期 PIN の変更手順を利用者に案内
- [ ] 気象庁の出典表示が出ている
- [ ] 素材（画像・音楽）の権利を確認
- [ ] SHA256SUMS を作成
