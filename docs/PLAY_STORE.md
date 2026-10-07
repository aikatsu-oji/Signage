# Google Play で配る（クローズドテスト）

SimpleSignage を、Google Play の「クローズドテスト」で配る手順です。

Google Play 版は、**管理画面からのアプリ更新の機能がありません**（Google Play のポリシーで、Play 以外の経路での自己更新は認められないため）。アプリの更新は、Google Play が行います。`REQUEST_INSTALL_PACKAGES` と、写真・動画の読み取りの権限も、付けていません。

> Google Play の無い端末（Fire TV など）には、GitHub のリリースの APK（debug 署名）を、`adb install` で入れます。**Google Play 版とは署名が違うため、入れ替えるときは、アプリを削除してから入れ直してください。**

## 1. アップロード鍵を作る（あなた自身で。1 回だけ）

**鍵ファイルとパスワードは、紛失すると Play の更新が難しくなります。** 複数の場所に安全に保管し、Git には入れないでください（`.gitignore` 済み）。

```
keytool -genkeypair -v -keystore simplesignage-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

`android/keystore.properties` を作ります。

```
storeFile=simplesignage-upload.jks
storePassword=（設定したパスワード）
keyAlias=upload
keyPassword=（設定したパスワード）
```

鍵ファイル（`.jks`）は、`android/` に置きます。

## 2. AAB を作る

```
scripts/build-play-bundle.sh
```

`dist/SimpleSignage-play-<版>.aab` ができます。版（versionCode）は、アップロードのたびに増やす必要があります（`android/app/build.gradle.kts`）。

## 3. Play Console の準備

1. [Google Play Console](https://play.google.com/console) に、デベロッパー アカウントを登録します（登録料 25 ドル。個人アカウントは、本人確認が必要です）。
2. 「アプリを作成」：アプリ名 SimpleSignage、言語 日本語、種類「アプリ」、無料。
3. **Play アプリ署名**（既定）を使います。上の鍵は「アップロード鍵」で、Google が最終の署名鍵を保管します。
4. 「アプリのコンテンツ」で、次を回答します。
   - **プライバシー ポリシー**：`docs/PRIVACY.md` を公開した URL（例：GitHub のページ）。先に、連絡先を書いてください。
   - **データ セーフティ**：収集するデータは「なし」（外部への送信は、気象庁・時刻サーバー・利用者が指定した URL の取得のみで、個人情報は送りません）。
   - **広告**：なし。**コンテンツのレーティング**：アンケートに回答。**ターゲット ユーザー**：18 歳以上。
   - **権限の申告**（求められたとき）：「他のアプリの上に表示」＝電源 ON 時の自動再生、「フォアグラウンド サービス（specialUse）」＝管理画面の通信の常駐。
5. ストア掲載情報：短い説明（80 文字）、詳しい説明、アイコン（`docs/play/icon-512.png`）、フィーチャー グラフィック（`docs/play/feature-graphic-1024x500.png`）、**スクリーンショット**（スマホ 2 枚以上。端末で撮ってください。テレビ向けは 16:9）。

## 4. クローズドテストを始める

1. 「テスト → クローズドテスト」でトラックを作り、`.aab` をアップロードします。
2. 「テスター」に、メールアドレス（Google アカウント）のリストを作ります。
3. **個人アカウント（2023 年 11 月以降に作成）は、12 人以上のテスターが、14 日間続けて参加**していないと、「本番環境」に申請できません（条件は変わることがあります。Play Console の案内で確認してください）。
4. テスターに、「テストへの参加リンク」を送ります。Google Play で、アプリが入れられます。

## Play 版の注意

- アプリの更新は、Google Play で行います（管理画面からの更新の機能は、ありません）。
- Android 16（targetSdk 36）に対応しています。画面の端までアプリの領域になるため、設定画面は、バーの分の余白を空けています。タブレット・テレビでの向きの固定は、Android 16 の既定（固定を無視）を、アプリ側で無効にしています。
