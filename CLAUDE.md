# CLAUDE.md — Spectrum ハイウェイ（引き継ぎメモ）

このファイルは Claude Code が作業の最初に読むためのメモです。詳しい説明・更新履歴は `README.md` にあります。

## アプリの概要

- Android アプリ「Spectrum ハイウェイ」。スマホと Android Auto（車の画面）に対応。
- 音楽プレーヤー表示とスペクトラムアナライザーの **Spectrum** と、高速道路の前方の IC / JCT / SA / PA までの距離・到着予定時刻を出す **ハイウェイモード** を 1 つにしたもの。
- 高速道路の外では Spectrum を全画面表示。高速道路に入ると自動で 2 画面（左 Spectrum : 右ハイウェイ = 4 : 5）。
- 現在の版は **v2.3.0**（`app/build.gradle.kts` の `versionName`）。v2.0 以降の変更点は README の「今回の更新」「v2.x の更新」の表を参照。

## 利用者について

- 利用者は日本語で依頼します。返答・README・画面の文言は日本語で書いてください。
- 利用者は PC で直接ビルドしていません。GitHub リポジトリ **HighwayModeAPP** の中身を置き換えて（GitHub Desktop、Windows）、GitHub Actions の **build-app** を手動実行し、できた AAB を Play Console の内部テストに上げて実機（スマホ＋車）で確認しています。
- 変更をまとめて渡すときは、README の先頭に「今回の更新（vX.Y）」の表（ご要望 → 対応）を追加し、それまでの「今回の更新」は「vX.Y の更新」に改名してください。版を上げるときは `versionName` も更新してください。

## 構成

| モジュール | 内容 |
|---|---|
| `:core` | Android に依存しない演算（純粋な Kotlin/JVM）。地図データ（HWDB）、道路グラフ、マップマッチング、前方経路、到着予測、渋滞、オービス、エンジン統合（`HighwayEngine`）、電池の節約の判定（`PowerPolicy`）、画面の切替判定と区画割り（`dual/HighwayPresence`, `dual/DualGeometry`）。**JVM 上でテストできる** |
| `:app` | Android アプリ。`com.example.spectrum`（Spectrum：音声解析 `SpectrumEngine`、音声の自動探索 `SessionProber`、描画 `PlayerRenderer` / `PlayerView`）と `com.example.highwaymode`（スマホ画面、常駐サービス、`engine/EngineHost`、`car/DualCarScreen`（Android Auto）、`ui/PanelRenderer`（ハイウェイ側の描画）） |
| `:datatool` | PC・CI 用の地図データ（HWDB）作成ツール |

ファイル構成の詳細は README の「ファイル構成」。

### 押さえておくべき設計

- **Android Auto**：Car App Library 1.7.0、`NavigationTemplate` ＋ Surface に自前で描画（`DualCarScreen`）。
  - 描画は専用スレッド（THREAD_PRIORITY_DISPLAY）で、締め切り方式の 30fps（一時停止中 15fps、待機中 10fps）。
  - 描くたびに `EngineHost.latest` を読みます（スマホと車で表示がずれないように）。
- **右上のボタン（ActionStrip）**：`NavigationTemplate` では必須。出し入れは Android Auto 側が行い、約 10 秒操作が無いと隠れます（アプリから時間は変えられない）。
  - 画面は常に上端から描きます。ボタンが出ている間（`onVisibleAreaChanged` の top > 0）だけ、施設一覧の列を下へずらします。
  - `invalidate()` はボタンの組み合わせが変わるときだけ呼んでください（呼ぶたびにボタンが出直すため）。
- **スペアナ**：`Visualizer` を使用。
  - 同じセッションに 2 つ目を付けると操作権が無く読めません。そのため、スレッドは世代番号で管理し、前のスレッドの終了を待ってから付けています。
  - 読めない状態が続いたら付け直します（ウォッチドッグ）。
  - 音声セッションを知らせないアプリ（Amazon Music など）は、`SessionProber` が新しい番号から 8 刻みでさかのぼって音の大きさを測り、探します（録音はしない）。
- **描画の軽さ**：スペアナの LED は 1 本 1 回の `drawRect`（BitmapShader）。動きは経過時間で計算します。ジャケットの縮小や色の計算は別スレッド。毎フレームの割り当てを避けてください。
- **電池**：`PowerPolicy` が GPS の間隔とセンサーの ON/OFF を決めます（高速道路上・近く・デモは常に毎秒）。

## ビルドとテスト

- テスト：`./gradlew :core:test`（JVM のみ。v2.3 時点で 63 件すべて成功）。
- アプリ：`./gradlew :app:assembleRelease`（Android SDK が必要）。CI（`.github/workflows/build-app.yml`）は `:core:test :app:assembleRelease`（署名鍵があれば `:app:bundleRelease` も）を実行します。
- 地図データ：`.github/workflows/build-data.yml`（build-hwdb）が毎週作って `hwdb-latest` リリースに公開します。build-app はそれを同梱します。
- **`:app` は実機・CI でしか確認できていません**。これまでの作業環境では Android SDK を使った完全なビルドができず、描画は AWT の代替実装で `PlayerRenderer` / `PanelRenderer` を PC 上で描いてプレビュー画像（`docs/preview_*.png`）を作っていました（そのプレビュー用の道具はリポジトリに入っていません）。
- 変更後は少なくとも `:core:test` を通し、`:app` は Kotlin のコンパイルが通ることを確認してください。

## 守ること（重要）

- **API キー（渋滞情報の HERE / TomTom / Google など）をリポジトリに入れない**。キーは端末の設定画面で入力し、端末内にだけ保存します。
- **署名鍵 `upload.jks` とそのパスワードをリポジトリに入れない**。GitHub の Secrets（`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`）にだけ置きます。`.gitignore` の `*.jks` / `*.keystore` の除外を外さないでください。
- **Android Auto のアプリ検証を回避する方法は使わない・案内しない**。車の画面に出す方法は、Google Play Console の内部テストと、PC の Desktop Head Unit（DHU）だけにします。
- **コミット・プッシュは利用者に頼まれたときだけ**行います。
- 改行コードは `.gitattributes` で LF に統一しています（`*.bat` / `*.cmd` だけ CRLF）。
- 新しい権限を追加したり、取得するデータを変えたりしたら、`docs/PRIVACY.md` も更新してください。

## 実機で未確認の点（v2.3 時点）

利用者の実機での確認待ちです。報告があればここから調べてください。

- 画面切替時にスペアナが止まる問題の修正（付け直し・世代管理・dumpsys の別スレッド化）と、カクつきの改善。
- Amazon Music の音声の自動探索（`SessionProber`）で、実際に見つかるか、見つかるまでの時間。
- 右上のボタンが出ている間だけ一覧をずらす動き（`onVisibleAreaChanged` の通知のしかたは車種・Android Auto の版で違う可能性がある）。
- ハイウェイだけの表示（横長画面の 2 列）・到着予定時刻を大きくした表示の見やすさ。
- 電池の節約（GPS の間隔の切替）で、高速道路に入ったときにすぐ毎秒に戻るか。
- 文字がにじむ件は、Android Auto の開発者向け設定の「動画の解像度」（720p / 1080p）でも改善する可能性がある（README の「文字をくっきりさせる」）。
