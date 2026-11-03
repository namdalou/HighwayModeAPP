# CLAUDE.md — Spectrum ハイウェイ（引き継ぎメモ）

このファイルは Claude Code が作業の最初に読むためのメモです。詳しい説明・更新履歴は `README.md` にあります。

## アプリの概要

- Android アプリ「Spectrum ハイウェイ」。スマホと Android Auto（車の画面）に対応。
- 音楽プレーヤー表示とスペクトラムアナライザーの **Spectrum** と、高速道路の前方の IC / JCT / SA / PA までの距離・到着予定時刻を出す **ハイウェイモード** を 1 つにしたもの。
- 高速道路の外では Spectrum を全画面表示。高速道路に入ると自動で 2 画面（左 Spectrum : 右ハイウェイ = 4 : 5）。
- 現在の版は **v2.4.0**（`app/build.gradle.kts` の `versionName`）。v2.0 以降の変更点は README の「今回の更新」「v2.x の更新」の表を参照。

## 利用者について

- 利用者は日本語で依頼します。返答・README・画面の文言は日本語で書いてください。
- 利用者は PC で直接ビルドしていません。GitHub リポジトリ **HighwayModeAPP** の中身を置き換えて（GitHub Desktop、Windows）、GitHub Actions の **build-app** を手動実行し、できた AAB を Play Console の内部テストに上げて実機（スマホ＋車）で確認しています。
- 変更をまとめて渡すときは、README の先頭に「今回の更新（vX.Y）」の表（ご要望 → 対応）を追加し、それまでの「今回の更新」は「vX.Y の更新」に改名してください。版を上げるときは `versionName` も更新してください。

## 構成

| モジュール | 内容 |
|---|---|
| `:core` | Android に依存しない演算（純粋な Kotlin/JVM）。地図データ（HWDB）、道路グラフ、マップマッチング、前方経路、到着予測、渋滞、オービス、エンジン統合（`HighwayEngine`）、電池の節約の判定（`PowerPolicy`）、画面の切替判定と区画割り（`dual/HighwayPresence`, `dual/DualGeometry`）。**JVM 上でテストできる** |
| `:app` | Android アプリ。`com.example.spectrum`（Spectrum：音声解析 `SpectrumEngine`、音声の自動探索 `SessionProber`、描画 `PlayerRenderer` / `PlayerView`、見た目の設定 `SpectrumStyle`）と `com.example.highwaymode`（スマホ画面、常駐サービス、`engine/EngineHost`、`car/DualCarScreen`（Android Auto）、`ui/PanelRenderer`（ハイウェイ側の描画）、`ui/SettingsActivity`（設定画面）・`ui/DevSettingsActivity`（開発者向け設定）） |
| `:datatool` | PC・CI 用の地図データ（HWDB）作成ツール |

ファイル構成の詳細は README の「ファイル構成」。

### 押さえておくべき設計

- **Android Auto**：Car App Library 1.7.0、`NavigationTemplate` ＋ Surface に自前で描画（`DualCarScreen`）。
  - 描画は専用スレッド（THREAD_PRIORITY_DISPLAY）で、締め切り方式の 30fps（一時停止中 15fps、待機中 10fps）。
  - 描くたびに `EngineHost.latest` を読みます（スマホと車で表示がずれないように）。
- **右上のボタン（ActionStrip）**：`NavigationTemplate` では必須。出し入れは Android Auto 側が行い、約 10 秒操作が無いと隠れます（アプリから時間は変えられない）。
  - 画面は常に上端から描きます。ボタンが出ている間（`onVisibleAreaChanged` の top > 0）だけ、施設一覧の列を下へずらします。
  - **隠れても表示範囲を戻してこない車がある**（v2.3 で一覧が下がったままになった）。v2.4 から `stripShownAt`（画面の表示・`onGetTemplate`・タップ・ボタン・上端が下がった通知で更新）から `STRIP_HIDE_MS`（10 秒）たったら、通知が無くても隠れたとみなして戻します。ずらす量は `animateShift` でなめらかに変えます（`PanelRenderer.topRightReserveH`）。
  - `invalidate()` はボタンの組み合わせが変わるときだけ呼んでください（呼ぶたびにボタンが出直すため）。
- **スペアナ**：`Visualizer` を使用。
  - 同じセッションに 2 つ目を付けると操作権が無く読めません。そのため、スレッドは世代番号で管理し、前のスレッドの終了を待ってから付けています。
  - 読めない状態が続いたら付け直します（ウォッチドッグ）。
  - 音声セッションを知らせないアプリ（Amazon Music など）は、`SessionProber` が新しい番号から 8 刻みでさかのぼって音の大きさを測り、探します（録音はしない）。
- **描画の軽さ**：スペアナの LED は 1 本 1 回の `drawRect`（BitmapShader）。動きは経過時間で計算します。ジャケットの縮小や色の計算は別スレッド。毎フレームの割り当てを避けてください。
- **電池**：`PowerPolicy` が GPS の間隔とセンサーの ON/OFF を決めます（高速道路上・近く・デモ・設定で節約オフのときは常に毎秒）。
- **設定画面（v2.4）**：スマホの下部バーの［⋮］で `ui/SettingsActivity`（1 画面にまとめたもの。部品は `ui/SettingsUi`、ダイアログは `ui/SettingsDialogs`）。値は `data/Prefs` に保存し、選んだらすぐ反映（保存ボタンなし）。
  - 描画で毎回使う設定（到着予定の表示 `Formats.etaRemaining`、スペアナの色・高さ `SpectrumStyle`）は `data/AppSettings.apply` で変数に写します。設定を変えたら `AppSettings.changed` を呼ぶと `version` が増え、表示中の車の画面が 2 秒以内に読み直します（`DualCarScreen.reloadSettings`）。
  - **開発者向け設定**（`ui/DevSettingsActivity`）：公開版の設定画面に出さない項目（診断・Shizuku・デモ走行・渋滞情報の取得元と API キー・地図データの細かい設定・Android Auto の状態）。音楽の画面の何もない場所を 20 回続けてタップ（`PlayerView.onSecretTap`）→ `DevAccess.prompt` でパスワード。コードには SHA-256 だけ（`DevAccess.PASSWORD_SHA256`。パスワードの文字はリポジトリ・README に書かない）。閉じたらロック。
  - 新しい開発者向けの項目は開発者向け設定へ、利用者向けの項目は設定画面のまとまりへ入れてください。

## ビルドとテスト

- テスト：`./gradlew :core:test`（JVM のみ。v2.4 時点で 65 件すべて成功）。
- アプリ：`./gradlew :app:assembleRelease`（Android SDK が必要）。CI（`.github/workflows/build-app.yml`）は `:core:test :app:assembleRelease`（署名鍵があれば `:app:bundleRelease` も）を実行します。
- 地図データ：`.github/workflows/build-data.yml`（build-hwdb）が毎週作って `hwdb-latest` リリースに公開します。build-app はそれを同梱します。
- **`:app` は実機・CI でしか確認できていません**。これまでの作業環境では Android SDK を使った完全なビルドができず、描画は AWT の代替実装で `PlayerRenderer` / `PanelRenderer` を PC 上で描いてプレビュー画像（`docs/preview_*.png`）を作っていました（そのプレビュー用の道具はリポジトリに入っていません）。
- 変更後は少なくとも `:core:test` を通し、`:app` は Kotlin のコンパイルが通ることを確認してください。
  - v2.4 の作業環境では dl.google.com（Android SDK・Google Maven）につながらず、ルートの `build.gradle.kts` が AGP を読むため `./gradlew :core:test` もそのままでは動きませんでした。作業用フォルダ（リポジトリの外）に Gradle の小さなビルドを作り、`:core` はリポジトリのソースを指してテスト、`:app` は Maven Central の `org.robolectric:android-all:16-robolectric-13921718`（Android 16 のフレームワーク）と、androidx（Car App Library 1.7.0・lifecycle・core）・Shizuku・`R`・`BuildConfig` の代用品（使っている部分だけの Java のスタブ）で Kotlin をコンパイルして確認しました（この道具もリポジトリには入れていません）。
  - リポジトリの `.github/workflows/`（build-app・build-hwdb）は v2.4 の直前のコミットで消えていたので、v2.3 の内容のまま戻しました。

## 守ること（重要）

- **API キー（渋滞情報の HERE / TomTom / Google など）をリポジトリに入れない**。キーは端末の開発者向け設定（v2.4 から）で入力し、端末内にだけ保存します。
- **署名鍵 `upload.jks` とそのパスワードをリポジトリに入れない**。GitHub の Secrets（`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`）にだけ置きます。`.gitignore` の `*.jks` / `*.keystore` の除外を外さないでください。
- **Android Auto のアプリ検証を回避する方法は使わない・案内しない**。車の画面に出す方法は、Google Play Console の内部テストと、PC の Desktop Head Unit（DHU）だけにします。
- **コミット・プッシュは利用者に頼まれたときだけ**行います。
- 改行コードは `.gitattributes` で LF に統一しています（`*.bat` / `*.cmd` だけ CRLF）。
- 新しい権限を追加したり、取得するデータを変えたりしたら、`docs/PRIVACY.md` も更新してください。

## 実機で未確認の点（v2.4 時点）

利用者の実機での確認待ちです。報告があればここから調べてください。

v2.4 で入れたもの:

- 右上のボタンが隠れたら一覧が戻るか（最後の操作から約 10 秒。ダイヤル操作でボタンを選んでいる間は、ボタンが出たまま一覧だけ戻って重なる可能性がある）。`onClick` が「ボタンを出すためのタップ」でも届くか（届かない車では、ボタンが出ても一覧がずれない）。
- 設定画面・開発者向け設定の見た目（縦・横画面、文字の大きさ）、パスワードの入力（キーボードがすぐ出るか）、権限の行の表示。
- 音声案内のテスト再生・音量・話す速さ（端末の音声合成による違い）。スペアナの色（特に「ジャケットの色」を車の画面で）。
- 到着予定の「残り時間」表示（ハイウェイの一覧・下のバー・車の施設一覧・通知）、画面の向きの固定、「高速道路を走行中だけ画面を消さない」、電池の節約のオフ、音楽に戻るまでの時間。

v2.3 までのもの:

- 画面切替時にスペアナが止まる問題の修正（付け直し・世代管理・dumpsys の別スレッド化）と、カクつきの改善。
- Amazon Music の音声の自動探索（`SessionProber`）で、実際に見つかるか、見つかるまでの時間。
- 右上のボタンが出ている間だけ一覧をずらす動き（`onVisibleAreaChanged` の通知のしかたは車種・Android Auto の版で違う可能性がある）。
- ハイウェイだけの表示（横長画面の 2 列）・到着予定時刻を大きくした表示の見やすさ。
- 電池の節約（GPS の間隔の切替）で、高速道路に入ったときにすぐ毎秒に戻るか。
- 文字がにじむ件は、Android Auto の開発者向け設定の「動画の解像度」（720p / 1080p）でも改善する可能性がある（README の「文字をくっきりさせる」）。
