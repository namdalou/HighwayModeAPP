# CLAUDE.md — Spectrum ハイウェイ（引き継ぎメモ）

このファイルは Claude Code が作業の最初に読むためのメモです。詳しい説明・更新履歴は `README.md` にあります。

## アプリの概要

- Android アプリ「Spectrum ハイウェイ」。スマホと Android Auto（車の画面）に対応。
- 音楽プレーヤー表示とスペクトラムアナライザーの **Spectrum** と、高速道路の前方の IC / JCT / SA / PA までの距離・到着予定時刻を出す **ハイウェイモード** を 1 つにしたもの。
- 高速道路の外では Spectrum を全画面表示。高速道路に入ると自動で 2 画面（左 Spectrum : 右ハイウェイ = 4 : 5）。
- 現在の版は **v2.5.0**（`app/build.gradle.kts` の `versionName`）。v2.0 以降の変更点は README の「今回の更新」「v2.x の更新」の表を参照。

## 利用者について

- 利用者は日本語で依頼します。返答・README・画面の文言は日本語で書いてください。
- 利用者は PC で直接ビルドしていません。GitHub リポジトリ **HighwayModeAPP** の main で GitHub Actions の **build-app** を手動実行し、できた AAB を Play Console の内部テストに上げて実機（スマホ＋車）で確認しています。
- **作業が終わったら、利用者から別の指示が無い限り、コミット → プッシュ → Pull Request を作って main へマージし、main で GitHub Actions の build-app を実行するところまで行ってください**（利用者の指示。v2.4 から）。build-app が成功したら、実行結果の URL と成果物（Artifacts の `HighwayMode-app` の中の `HighwayMode.aab`）を知らせます。利用者はその AAB を Play Console の内部テストに上げます。
- 地図データの作り直し（build-hwdb）は毎週自動で動くので、利用者に頼まれたときだけ手動で実行します（そのときは build-hwdb が終わってから build-app を実行する。build-app は公開済みの最新の地図データを同梱するため）。
- 利用者は手元のフォルダ（GitHub Desktop、Windows）も使います。古いフォルダの中身で置き換えると `.github`（build-app）などが消えるので（v2.4 の直前に一度消えた）、手元で触る前に「Fetch origin → Pull origin」で main を最新にするよう案内してください。
- 変更をまとめて渡すときは、README の先頭に「今回の更新（vX.Y）」の表（ご要望 → 対応）を追加し、それまでの「今回の更新」は「vX.Y の更新」に改名してください。版を上げるときは `versionName` も更新してください。

## 構成

| モジュール | 内容 |
|---|---|
| `:core` | Android に依存しない演算（純粋な Kotlin/JVM）。地図データ（HWDB）、道路グラフ、マップマッチング、前方経路（カーナビの案内に合わせる `route/NavGuide`）、到着予測、渋滞、オービス、エンジン統合（`HighwayEngine`）、電池の節約の判定（`PowerPolicy`）、画面の切替判定と区画割り（`dual/HighwayPresence`, `dual/DualGeometry`）。**JVM 上でテストできる** |
| `:app` | Android アプリ。`com.example.spectrum`（Spectrum：音声解析 `SpectrumEngine`、音声の自動探索 `SessionProber`、描画 `PlayerRenderer` / `PlayerView`、スペアナの表示の種類ごとの描画 `SpectrumPainter`、ジャケットの色 `ArtPalette`、見た目の設定 `SpectrumStyle`、通知の受け渡し `NotificationHooks`）と `com.example.highwaymode`（スマホ画面、常駐サービス、`engine/EngineHost`、`car/DualCarScreen`（Android Auto）、`ui/PanelRenderer`（ハイウェイ側の描画）、`ui/SettingsActivity`（設定画面）・`ui/DevSettingsActivity`（開発者向け設定）・`ui/SpectrumPickers`（スペアナの見本・選ぶ画面）、`nav/NavNotifications`（Google マップのナビの通知の読み取り）） |
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
- **スペアナの表示の種類（v2.5）**：`SpectrumPainter` が 8 種類（`SpectrumStyle.VISUAL_*`：LED・なめらかなバー・ミラー・ライン・ドット・レトロ・アナログメーター 2 種）を描きます。音楽の画面（スマホ・車）と設定画面の見本（`SpectrumPreviewView`）で共用。
  - 模様・グラデーション・メーターの目盛り板は、区画・色・種類が変わったときだけ作り直します（作り直しの判断は数値の比較。文字列のキーを毎フレーム作らない）。目盛り板の古い画像は `recycle()` しない（描画中の画面が使っていることがある）。
  - 色は `SpectrumStyle.colorsFor`（帯ごとの下・上の色）→ `painter.setColors`。決まった色は `fixedColors`、ジャケットの色・カラーコードは `gradientFor`（その色を中ほどに、下を濃く・上を明るく、色合いを少しずらす）。ジャケットの代表色は `ArtPalette.dominant`（色合い 24 区分の重み付き。白黒は銀色）をジャケットの別スレッドで求めます。
  - 保存する番号（色 `bar_theme` の 0〜5、表示 `bar_visual`）は前の版と互換にしてあります。番号を並べ替えないでください（選ぶ画面の並びは `PICKER_ORDER`）。
  - 音楽の画面でスペアナを長押し（`PlayerView.onSpectrumLongPress`）すると表示の種類を順に切り替えます（20 回タップの数には入れない）。
- **Google マップのナビ連携（v2.5）**：`MediaListenerService`（通知へのアクセス）が受け取った通知を `NotificationHooks` で `nav/NavNotifications` へ渡し、Google マップの「実行中」の通知から文字（extras と独自の表示 RemoteViews を組み立てた文字）を集めて `NavText.parse` で距離と案内の文にします（1.5 秒にまとめて読む）。
  - `EngineHost.setNavInstruction` → `HighwayEngine.setNavInstruction`。案内の文が変わったら前方経路を作り直し、`buildRoute` で `NavGuide.plan`（JCT の乗り換え先を `NavForkChooser` で選び直す）→ `NavGuide.match`（降りる IC）。降りる IC より先の施設は一覧に出しません。結果は `FacilityView.navExit` / `navNote` と `HighwaySnapshot.nav`（`NavView`）。
  - 照合は文を解釈せず、前方経路の施設名・分岐の先の道路名と路線番号・ランプの行き先が案内の文に含まれるかで決めます（言い回しの違いに強くするため）。通知の実際の形は実機で未確認なので、開発者向け設定の「Google マップのナビ連携（確認用）」で受け取った文字（部品の名前つき）を見てもらい、合わない言い回しがあれば `NavText` / `NavGuide` を直してください。
  - このアプリは Android Auto の `NavigationManager.navigationStarted()` を呼びません（呼ぶと、ナビ中のほかのアプリ（Google マップ）のナビが止められる可能性がある）。ナビ連携のため、今後も呼ばないでください。
- **電池**：`PowerPolicy` が GPS の間隔とセンサーの ON/OFF を決めます（高速道路上・近く・デモ・設定で節約オフのときは常に毎秒）。
- **設定画面（v2.4、v2.5 で項目を追加）**：スマホの下部バーの［⋮］で `ui/SettingsActivity`（1 画面にまとめたもの。部品は `ui/SettingsUi`、ダイアログは `ui/SettingsDialogs`）。値は `data/Prefs` に保存し、選んだらすぐ反映（保存ボタンなし）。
  - v2.5 で、音声の読み取り（Shizuku。手順つき `SettingsDialogs.showShizukuGuide`）・渋滞情報（取得元・API キー・取り方の手順 `showKeyGuide`・キーの確認 `testKey`。おすすめは TomTom）・地図データの版（`mapVersion`）・Google マップのナビ連携を通常の設定画面に入れました。
  - 描画で毎回使う設定（到着予定の表示 `Formats.etaRemaining`、スペアナの色・高さ `SpectrumStyle`）は `data/AppSettings.apply` で変数に写します。設定を変えたら `AppSettings.changed` を呼ぶと `version` が増え、表示中の車の画面が 2 秒以内に読み直します（`DualCarScreen.reloadSettings`）。
  - **開発者向け設定**（`ui/DevSettingsActivity`）：公開版の設定画面に出さない項目（診断・デモ走行・ナビ連携の確認用の表示・地図データの細かい設定（配布元・Overpass・キャッシュなど）・Android Auto の状態）。音楽の画面の何もない場所を 20 回続けてタップ（`PlayerView.onSecretTap`）→ `DevAccess.prompt` でパスワード。コードには SHA-256 だけ（`DevAccess.PASSWORD_SHA256`。パスワードの文字はリポジトリ・README に書かない）。閉じたらロック。
  - 新しい開発者向けの項目は開発者向け設定へ、利用者向けの項目は設定画面のまとまりへ入れてください。

## ビルドとテスト

- テスト：`./gradlew :core:test`（JVM のみ。v2.5 時点で 69 件すべて成功）。
- アプリ：`./gradlew :app:assembleRelease`（Android SDK が必要）。CI（`.github/workflows/build-app.yml`）は `:core:test :app:assembleRelease`（署名鍵があれば `:app:bundleRelease` も）を実行します。
- 地図データ：`.github/workflows/build-data.yml`（build-hwdb）が毎週作って `hwdb-latest` リリースに公開します。build-app はそれを同梱します。
- **`:app` は実機・CI でしか確認できていません**。これまでの作業環境では Android SDK を使った完全なビルドができず、描画は AWT の代替実装で `PlayerRenderer` / `PanelRenderer` を PC 上で描いてプレビュー画像（`docs/preview_*.png`）を作っていました（そのプレビュー用の道具はリポジトリに入っていません）。
- 変更後は少なくとも `:core:test` を通し、`:app` は Kotlin のコンパイルが通ることを確認してください。
  - v2.4 の作業環境では dl.google.com（Android SDK・Google Maven）につながらず、ルートの `build.gradle.kts` が AGP を読むため `./gradlew :core:test` もそのままでは動きませんでした。作業用フォルダ（リポジトリの外）に Gradle の小さなビルドを作り、`:core` はリポジトリのソースを指してテスト、`:app` は Maven Central の `org.robolectric:android-all:16-robolectric-13921718`（Android 16 のフレームワーク）と、androidx（Car App Library 1.7.0・lifecycle・core）・Shizuku・`R`・`BuildConfig` の代用品（使っている部分だけの Java のスタブ）で Kotlin をコンパイルして確認しました（この道具もリポジトリには入れていません）。
  - リポジトリの `.github/workflows/`（build-app・build-hwdb）は v2.4 の直前のコミットで消えていたので、v2.3 の内容のまま戻しました。
  - v2.5 も同じ方法（作業用フォルダの小さな Gradle のビルド）で `:core:test` と `:app` の Kotlin のコンパイルを確認しました。

## 守ること（重要）

- **API キー（渋滞情報の HERE / TomTom / Google など）をリポジトリに入れない**。キーは利用者が端末の設定画面（v2.5 から通常の設定の「渋滞情報（リアルタイム）」。v2.4 は開発者向け設定）で入力し、端末内にだけ保存します。
- **署名鍵 `upload.jks` とそのパスワードをリポジトリに入れない**。GitHub の Secrets（`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`）にだけ置きます。`.gitignore` の `*.jks` / `*.keystore` の除外を外さないでください。
- **Android Auto のアプリ検証を回避する方法は使わない・案内しない**。車の画面に出す方法は、Google Play Console の内部テストと、PC の Desktop Head Unit（DHU）だけにします。
- **コミット・プッシュ・main へのマージは利用者の指示に従います**（いまの指示: 作業が終わったら、別の指示が無い限り main へのマージと build-app の実行まで行う）。
- 改行コードは `.gitattributes` で LF に統一しています（`*.bat` / `*.cmd` だけ CRLF）。
- 新しい権限を追加したり、取得するデータを変えたりしたら、`docs/PRIVACY.md` も更新してください。

## 実機で未確認の点（v2.5 時点）

利用者の実機での確認待ちです。報告があればここから調べてください。

v2.5 で入れたもの:

- Google マップのナビの通知の実際の形（題名・本文・独自の表示の部品の名前）と、照合が合うか（JCT の乗り換え先・降りる IC）。日本語の案内の言い回し、距離の書き方（「500 m」「1.2 km」など）。合わないときは開発者向け設定の「Google マップのナビ連携（確認用）」のコピーをもらって直す。
- Android Auto に接続中も Google マップがスマホに案内の通知を出すか、車の画面でこのアプリを表示している間も読み取れるか。
- スペアナの新しい表示（特にレトロ・アナログメーター）の見た目と重さ（車の画面の 30fps）。ジャケットの色が期待どおりか（暗い・淡い・白黒のジャケット）。カラーコードの入力。
- スペアナの長押しでの切り替え（ジャケットのタップ・20 回タップと干渉しないか）。
- 設定画面の新しい項目（Shizuku の手順の分かりやすさ、TomTom のキーの確認、地図データの版の表示）。

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
