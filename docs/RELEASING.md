# リリースの出し方（GitHub Releases）

ビルド済みのファイル（APK・exe）は、ブランチには入れず、**タグが指す専用のコミット**にだけ入れて、
GitHub Actions（`.github/workflows/release.yml`）が Releases に添付します。リポジトリの履歴が大きくならない方式です。

1. APK と exe をビルドする（`android/README.md`、`windows/build.bat`）。
2. ブランチ上の `release/README.md` の版を更新して push する（ブランチには、バイナリは入れない）。
3. 一時的なコミットを作ってタグを付け、**タグだけ**を push する。
   ```
   git checkout --detach
   cp <APK> release/SimpleSignage-android-<版>.apk
   cp <exe> release/SimpleSignage-windows-<版>.exe
   (cd release && sha256sum Signage-* > SHA256SUMS)
   git add release && git commit -m "リリース v<版>"
   git tag v<版>
   git push origin v<版>
   git checkout -          # ブランチに戻る
   ```
4. Actions が、チェックサムを確認して Releases に公開します（リポジトリの Actions が有効になっている必要があります）。
5. Releases のページに、APK・exe・SHA256SUMS・ライセンスが添付されます。

注意：APK は debug 署名のままです。社外へ広く配る場合は、リリース鍵で署名してください（`docs/DISTRIBUTION.md`）。

## タグを push できない環境のとき
GitHub の Actions タブ →「Release」→「Run workflow」で、`tag`（例：`v1.9.1`）、`binaries_ref`（APK・exe が入っているコミットの SHA、40 桁）、`sums_sha256`（そのコミットの `release/SHA256SUMS` の SHA-256）を指定して実行します（`main` から実行）。
タグは、実行したコミットに自動で作られます。

タグも Actions の手動実行も使えないときは、`.github/release-request.txt` に、1 行目にタグ、2 行目に APK・exe が入っているコミットの SHA、3 行目にその `release/SHA256SUMS` の SHA-256 を書き、**`main` に取り込みます**（PR のレビューを通った変更で、Release が作られます）。

## いつもの手順（スクリプト）
ビルドして `dist/` に APK・exe を置いたら、次を実行します。版は、ソースから自動で読み取ります。
```
scripts/publish-release.sh "- 変更点1
- 変更点2"
```
ブランチに、ファイルを入れたコミットと、公開を依頼するコミット（`.github/release-request.txt`）を push します。**PR で `main` に取り込むと**、Actions が Releases に載せます（`main` に入っていないコミットのファイルは、公開されません。チェックサムも、`main` に入った値と照合します）。

## ブランチの運用
- 作業は、`main` を起点にしたブランチで行い、PR で `main` に合流します。
- Release の本文のリンクは、`main` のファイルを指します（`scripts/publish-release.sh`）。リリースのあと、`main` に合流してください。
