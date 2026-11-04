package com.example.highwaymode.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.highwaymode.core.hwdb.HwdbDownloader
import com.example.highwaymode.core.hwdb.Prefectures
import com.example.highwaymode.data.DataSyncService
import com.example.highwaymode.data.DataSyncState
import com.example.highwaymode.data.HwdbManager
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.data.UserCameraStore
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.engine.RunDiagnostics
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 設定画面から開くダイアログ（地図データ・渋滞情報・オービスの登録・動作状態）。
 * 設定画面（[SettingsActivity]）と開発者向け設定（[DevSettingsActivity]）から使う。
 */
object SettingsDialogs {

    private const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

    private fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun Activity.column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }

    private fun Activity.label(text: String, size: Float = 14f, bold: Boolean = false, color: Int = 0xFFD0D6DE.toInt()): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun Activity.button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun Activity.scroll(v: View): ScrollView = ScrollView(this).apply { addView(v) }

    // ================================================================== 地図データ

    /** 端末内の地図データの短い説明（設定画面の行用。別スレッドで調べて、メインスレッドで [done] を呼ぶ） */
    fun mapSummary(a: Activity, done: (String) -> Unit) {
        thread(name = "hwdb-summary") {
            val list = try {
                HwdbManager.list(a)
            } catch (e: Exception) {
                emptyList()
            }
            val national = list.filter { it.coverage == "all" }.maxByOrNull { it.version }
            val text = when {
                national != null -> "全国　版 ${national.version}（${national.kind.label}）"
                list.isNotEmpty() -> "一部の地方　版 ${list.maxOf { it.version }}"
                else -> "端末内に地図データがありません（通信で取得します）"
            }
            a.runOnUiThread { if (!a.isFinishing) done(text) }
        }
    }

    /**
     * 地図データの版（作った日時）。例「2026年11月4日 0:05（全国・アプリ同梱）」。
     * 別スレッドで調べて、メインスレッドで [done]（版の文, 配布元の最新版についての一言 or null）
     */
    fun mapVersion(a: Activity, done: (String, String?) -> Unit) {
        val prefs = Prefs(a)
        thread(name = "hwdb-version") {
            val list = try {
                HwdbManager.list(a)
            } catch (e: Exception) {
                emptyList()
            }
            val best = list.filter { it.coverage == "all" }.maxByOrNull { it.version } ?: list.maxByOrNull { it.version }
            val text = if (best == null) "端末内に地図データがありません" else {
                val whenText = formatCreated(best.createdAt) ?: formatVersion(best.version) ?: best.version
                val cov = if (best.coverage == "all") "全国" else "一部の地方"
                "$whenText（$cov・${best.kind.label}）"
            }
            val avail = prefs.availableVersion
            val note = if (best != null && avail != null) {
                if (avail > best.version) "配布元に新しい版があります（${formatVersion(avail) ?: avail}）" else "配布元の最新版と同じです"
            } else null
            a.runOnUiThread { if (!a.isFinishing) done(text, note) }
        }
    }

    /** "2026-11-03T15:05:00Z" → 端末の時刻で「2026年11月4日 0:05」 */
    fun formatCreated(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val d = f.parse(iso) ?: return null
            java.text.SimpleDateFormat("yyyy年M月d日 H:mm", Locale.JAPAN).format(d) + " 作成"
        } catch (e: Exception) {
            null
        }
    }

    /** 版の名前 "20261104-0005" → 「2026年11月4日 0:05」 */
    fun formatVersion(v: String): String? {
        val m = Regex("^(\\d{4})(\\d{2})(\\d{2})(?:-(\\d{2})(\\d{2}))?").find(v) ?: return null
        val (y, mo, d) = Triple(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
        val time = if (m.groupValues[4].isNotEmpty()) " ${m.groupValues[4].toInt()}:${m.groupValues[5]}" else ""
        return "${y}年${mo}月${d}日$time"
    }

    /** 端末内の地図データ・読み込み中のタイル・キャッシュの詳しい説明（開発者向け設定） */
    fun mapDetails(a: Activity, done: (String) -> Unit) {
        val prefs = Prefs(a)
        thread(name = "hwdb-info") {
            val list = try {
                HwdbManager.list(a)
            } catch (e: Exception) {
                emptyList()
            }
            val (tiles, local) = EngineHost.loadedTileStats()
            val cacheMb = EngineHost.cacheSizeBytes() / 1e6
            val sb = StringBuilder()
            if (list.isEmpty()) {
                sb.append("端末内の地図データ: なし（その場で Overpass API から取得）\n")
            } else {
                sb.append("端末内の地図データ:\n")
                for (i in list) {
                    val cov = when (i.coverage) {
                        "all" -> "全国"
                        else -> i.prefectures?.split(',')?.mapNotNull { Prefectures.byCode(it)?.name }?.joinToString("・")?.take(40) ?: "一部"
                    }
                    sb.append(String.format(Locale.JAPAN, "・%s：%s 版 %s（%.1f MB, %d タイル）\n", i.kind.label, cov, i.version, i.bytes / 1e6, i.tiles))
                }
            }
            sb.append(String.format(Locale.JAPAN, "読み込み中のタイル: %d（うち端末内データ %d）\nオンライン取得のキャッシュ: %.1f MB", tiles, local, cacheMb))
            prefs.availableVersion?.let { sb.append("\n配布元の最新版: ").append(it) }
            a.runOnUiThread { if (!a.isFinishing) done(sb.toString()) }
        }
    }

    /** ダウンロードや端末内での作成の進み具合（実行中でなければ前回の結果。何も無ければ null） */
    fun syncProgress(): String? = if (DataSyncState.running) {
        "${DataSyncState.title}：${DataSyncState.message ?: ""}" + if (DataSyncState.percent >= 0) "（${DataSyncState.percent}%）" else ""
    } else {
        DataSyncState.lastResult
    }

    /** 「最新の地図データに更新」（確認してからダウンロード） */
    fun startDownload(a: Activity) {
        val prefs = Prefs(a)
        when {
            DataSyncState.running -> Toast.makeText(a, "更新中です（通知に進み具合が出ます）", Toast.LENGTH_SHORT).show()
            prefs.manifestUrl.isBlank() -> Toast.makeText(a, "このアプリには地図データの配布元が設定されていません", Toast.LENGTH_LONG).show()
            else -> AlertDialog.Builder(a, THEME)
                .setTitle("最新の地図データに更新")
                .setMessage("全国の地図データ（数 MB〜十数 MB）をダウンロードします。Wi-Fi での更新をおすすめします。\n新しい版が無ければ、すぐに終わります。")
                .setPositiveButton("更新する") { _, _ ->
                    DataSyncService.download(a)
                    Toast.makeText(a, "更新を始めました（通知に進み具合が出ます）", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
    }

    /** 地方を選んで、端末内で地図データを作る（Overpass から取得。開発者向け設定） */
    fun chooseRegions(a: Activity) {
        val regions = Prefectures.REGIONS
        val names = regions.map { r -> "${r.name}（${r.prefectures.size}都道府県）" }.toTypedArray()
        val checked = BooleanArray(regions.size)
        AlertDialog.Builder(a, THEME)
            .setTitle("作成する地方（1 地方 数分〜20 分）")
            .setMultiChoiceItems(names, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("作成") { _, _ ->
                val sel = regions.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (sel.isEmpty()) return@setPositiveButton
                DataSyncService.build(a, sel.joinToString(","))
                Toast.makeText(a, "作成を開始しました（通知に進み具合が出ます）", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    fun editManifestUrl(a: Activity, prefs: Prefs, done: () -> Unit) {
        val edit = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.manifestUrl)
            hint = "https://github.com/<ユーザー>/<リポジトリ>/releases/download/hwdb-latest/hwdb-manifest.json"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7D8590.toInt())
        }
        AlertDialog.Builder(a, THEME)
            .setTitle("地図データの配布元（hwdb-manifest.json の URL）")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                prefs.manifestUrl = edit.text.toString()
                done()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    fun editEndpoints(a: Activity, prefs: Prefs, done: () -> Unit) {
        val edit = EditText(a)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
        edit.setText(prefs.endpoints.joinToString("\n"))
        edit.setTextColor(Color.WHITE)
        AlertDialog.Builder(a, THEME)
            .setTitle("Overpass API（1 行に 1 つ、上から順に使用）")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                prefs.endpoints = edit.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                done()
            }
            .setNeutralButton("既定に戻す") { _, _ ->
                prefs.endpoints = Prefs.DEFAULT_ENDPOINTS
                done()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** ダウンロード・端末内で作成した地図データを消す（同梱のデータは残る） */
    fun deleteDownloaded(a: Activity, done: () -> Unit) {
        AlertDialog.Builder(a, THEME)
            .setTitle("地図データの削除")
            .setMessage("ダウンロード・端末内で作成した地図データを削除します（アプリに同梱のデータは残ります）。")
            .setPositiveButton("削除") { _, _ ->
                thread(name = "hwdb-delete") {
                    for (i in HwdbManager.list(a)) if (i.kind != HwdbManager.Kind.BUNDLED) i.file?.delete()
                    HwdbManager.notifyChanged()
                    a.runOnUiThread { done() }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 起動時に 1 日 1 回、配布元に新しい地図データがあるか確認して知らせる */
    fun checkUpdateOnStart(a: Activity) {
        val prefs = Prefs(a)
        if (!prefs.autoCheckUpdate || prefs.manifestUrl.isBlank() || DataSyncState.running) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheckMs < 20 * 3600_000L) return
        prefs.lastUpdateCheckMs = System.currentTimeMillis()
        thread(name = "hwdb-check") {
            try {
                val m = HwdbDownloader(HwdbManager.downloadDir(a)).fetchManifest(prefs.manifestUrl)
                prefs.availableVersion = m.dataVersion
                val have = HwdbManager.newestNationalVersion(a)
                if (have == null || m.dataVersion > have) {
                    a.runOnUiThread {
                        if (a.isFinishing) return@runOnUiThread
                        AlertDialog.Builder(a, THEME)
                            .setTitle("新しい地図データがあります")
                            .setMessage(String.format(Locale.JAPAN, "版 %s（%.1f MB）\n現在: %s\n\n更新しますか？（Wi-Fi 推奨）", m.dataVersion, m.totalBytes / 1e6, have ?: "なし"))
                            .setPositiveButton("更新") { _, _ -> DataSyncService.download(a) }
                            .setNegativeButton("あとで", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                // 通信できない・配布元が無い場合は何もしない
            }
        }
    }

    // ================================================================== 渋滞情報

    /** 取得元（保存する名前と、設定画面に出す名前）。おすすめは TomTom */
    val PROVIDERS = listOf(
        "none" to "使わない（自車の走行から推定）",
        "tomtom" to "TomTom Traffic（おすすめ）",
        "here" to "HERE Traffic",
        "google" to "Google Routes（従量課金）"
    )

    fun providerName(p: String): String = when (p) {
        "tomtom" -> "TomTom"
        "here" -> "HERE"
        "google" -> "Google"
        else -> "—"
    }

    /** API キーの見せ方（全部は出さない） */
    fun maskedKey(key: String): String = if (key.isBlank()) "未設定" else "設定済み（末尾 ${key.takeLast(4)}）"

    /** 取得間隔の選択肢 */
    val INTERVALS = listOf(3 to "3 分ごと", 5 to "5 分ごと", -1 to "自動（前方に渋滞があれば 3 分、無ければ 5 分）", 0 to "取得しない")

    /** API キーの取り方（取得元ごと） */
    fun showKeyGuide(a: Activity, provider: String) {
        val p = if (provider == "none") "tomtom" else provider
        val (title, url, msg) = when (p) {
            "here" -> Triple(
                "HERE の API キーの取り方", "https://platform.here.com/",
                "1. 下の「サイトを開く」から HERE の開発者サイトを開き、無料のアカウントを作る（Base Plan。本人確認・支払い方法の登録を求められることがあります）\n" +
                    "2. 「Access Manager」→「Apps」でアプリを作り、「API Keys」→「Create API key」でキーを作る\n" +
                    "3. キーの文字列をコピーし、この画面の「API キー」に貼り付けて保存する\n" +
                    "4. 「キーを確かめる」で「使えます」と出れば完了\n\n" +
                    "1 回の更新で 2 回問い合わせます（道路の流れ・事故や規制）。無料の回数と料金は HERE のサイトで確認してください。"
            )
            "google" -> Triple(
                "Google の API キーの取り方", "https://console.cloud.google.com/",
                "1. 下の「サイトを開く」から Google Cloud コンソールを開き、プロジェクトを作って、お支払い（請求先アカウント）を設定する\n" +
                    "2. 「API とサービス」→「ライブラリ」で「Routes API」を有効にする\n" +
                    "3. 「認証情報」→「認証情報を作成」→「API キー」。キーの制限で「Routes API」だけに絞るのがおすすめ\n" +
                    "4. キーをコピーし、この画面の「API キー」に貼り付けて保存。「キーを確かめる」で確認\n\n" +
                    "注意: 従量課金です。無料の範囲を超えると料金がかかります。料金と無料の範囲を Google の料金ページで必ず確認してください。"
            )
            else -> Triple(
                "TomTom の API キーの取り方（おすすめ）", "https://developer.tomtom.com/",
                "TomTom は無料で始められ、渋滞（長さ・遅れ）・事故・通行止め・工事・車線規制を取得できます（日本国内での対応範囲・精度は TomTom によります）。\n\n" +
                    "1. 下の「サイトを開く」から TomTom の開発者サイト（developer.tomtom.com）を開き、「Register」（無料登録）でアカウントを作る（メールアドレスの確認があります）\n" +
                    "2. ログインして「Dashboard」（My Dashboard）→「Keys」を開く。最初からある「My first API key」か、「Create key」で作ったキーを使う（使える API に Traffic が含まれていることを確認）\n" +
                    "3. キーの文字列をコピーし、この画面の「API キー」に貼り付けて保存する\n" +
                    "4. 「キーを確かめる」で「使えます」と出れば完了\n\n" +
                    "使う回数の目安: 高速道路を走行中だけ、3〜5 分ごとに 1〜4 回問い合わせます（1 時間で 12〜80 回ほど。一般道・停車中は問い合わせません）。" +
                    "無料の回数・条件は変わることがあるので、TomTom の料金ページ（Pricing）で確認してください。"
            )
        }
        AlertDialog.Builder(a, THEME)
            .setTitle(title)
            .setMessage(msg + "\n\nAPI キーは端末の中にだけ保存し、問い合わせ以外には使いません。")
            .setPositiveButton("サイトを開く") { _, _ ->
                try {
                    a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(a, "ブラウザを開けませんでした: $url", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** API キーを入れる（貼り付け）。保存したら [onSaved] */
    fun editKey(a: Activity, provider: String, onSaved: () -> Unit) {
        val prefs = Prefs(a)
        val col = a.column()
        val edit = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "API キーを貼り付け"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7D8590.toInt())
            setText(prefs.trafficKey(provider))
            isSingleLine = true
        }
        col.addView(edit)
        col.addView(a.label("${providerName(provider)} の開発者サイトで作ったキーを貼り付けてください。キーは端末の中にだけ保存します。", 12f, color = 0xFF8D96A3.toInt()))
        AlertDialog.Builder(a, THEME)
            .setTitle("API キー（${providerName(provider)}）")
            .setView(col)
            .setPositiveButton("保存") { _, _ ->
                prefs.setTrafficKey(provider, edit.text.toString())
                EngineHost.refreshTraffic()
                onSaved()
            }
            .setNeutralButton("キーの取り方") { _, _ -> showKeyGuide(a, provider) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * キーが使えるか、1 回だけ問い合わせて確かめる（東京駅付近の短い区間）。
     * 結果はメインスレッドで [done]（使える, 説明）
     */
    fun testKey(a: Activity, provider: String, key: String, done: (Boolean, String) -> Unit) {
        if (provider == "none") {
            done(false, "取得元を選んでください")
            return
        }
        if (key.isBlank()) {
            done(false, "API キーが未設定です")
            return
        }
        thread(name = "traffic-key-test") {
            val prov: com.example.highwaymode.core.traffic.TrafficProvider = when (provider) {
                "here" -> com.example.highwaymode.core.traffic.HereTrafficProvider(key)
                "google" -> com.example.highwaymode.core.traffic.GoogleRoutesTrafficProvider(key)
                else -> com.example.highwaymode.core.traffic.TomTomTrafficProvider(key)
            }
            // 首都高 都心環状線の近くを南から北へ約 1km（取得できるかだけを見る）
            val q = com.example.highwaymode.core.traffic.TrafficQuery(
                lat = doubleArrayOf(35.6764, 35.6855),
                lon = doubleArrayOf(139.7660, 139.7695),
                bearing = doubleArrayOf(17.0, 17.0),
                dist = doubleArrayOf(0.0, 1050.0),
                lengthM = 1050.0
            )
            val (ok, msg) = try {
                val d = prov.fetch(q)
                true to "使えます（${prov.name}。この区間の情報 ${d.items.size} 件）"
            } catch (e: com.example.highwaymode.core.net.HttpStatusException) {
                false to when (e.code) {
                    400 -> "キーまたは問い合わせが受け付けられませんでした（HTTP 400）。キーを確認してください"
                    401, 403 -> "キーが正しくないか、このキーでは使えない API です（HTTP ${e.code}）。開発者サイトでキーと使える API を確認してください"
                    429 -> "問い合わせが多すぎます（HTTP 429）。しばらく待ってから確かめてください"
                    else -> "取得できませんでした（HTTP ${e.code}）"
                }
            } catch (e: java.io.IOException) {
                false to "通信できませんでした（${e.message?.take(60) ?: "接続エラー"}）"
            } catch (e: Exception) {
                false to "取得できませんでした（${e.javaClass.simpleName}）"
            }
            a.runOnUiThread { if (!a.isFinishing) done(ok, msg) }
        }
    }

    // ================================================================== 音声の読み取り（Shizuku）

    /** Shizuku で「音声の読み取り」を許可する手順と、許可のボタン */
    fun showShizukuGuide(a: Activity, onChanged: () -> Unit) {
        val granted = com.example.spectrum.SessionScanner.hasPermission(a)
        val msg = if (granted) {
            "音声の読み取りは許可済みです。音声の番号を知らせない音楽アプリでも、再生中の音を読み取ってスペアナを動かします。"
        } else {
            "Amazon Music など一部の音楽アプリは、どの音声を解析すればよいかをアプリに知らせません。このアプリは自動で再生中の音を探しますが（数秒〜1 分ほど）、" +
                "それでもスペアナが動かないときだけ、次の手順で「音声の読み取り」を許可してください。一度許可すれば、その後は Shizuku を止めても大丈夫です。\n\n" +
                "1. Google Play で「Shizuku」をインストールする（下の「Shizuku を入手」）\n" +
                "2. スマホの「開発者向けオプション」を有効にする（設定 → デバイス情報 →「ビルド番号」を 7 回続けてタップ）\n" +
                "3. Wi-Fi につないで、開発者向けオプションの「ワイヤレス デバッグ」をオンにする\n" +
                "4. Shizuku を開き、「ワイヤレス デバッグ」の「ペアリング」を選ぶ。ワイヤレス デバッグの画面の「ペア設定コードによるデバイスのペア設定」に出る 6 桁のコードを、Shizuku の通知に入力する\n" +
                "5. Shizuku の「開始」を押す（「Shizuku は実行中です」と出れば準備完了）\n" +
                "6. この画面に戻って「許可する」を押し、Shizuku の確認で「許可」を選ぶ\n\n" +
                "Android 10 以前はワイヤレス デバッグが無いため、パソコン（adb）での開始が必要です。音声を録音・保存することはありません。"
        }
        val b = AlertDialog.Builder(a, THEME)
            .setTitle("音声の読み取り（Shizuku）")
            .setMessage(msg)
            .setNegativeButton("閉じる", null)
        if (!granted) {
            b.setPositiveButton("許可する") { _, _ ->
                com.example.spectrum.ShizukuHelper.grantDump(a) { ok, m ->
                    Toast.makeText(a, m, Toast.LENGTH_LONG).show()
                    if (!ok && !com.example.spectrum.ShizukuHelper.isRunning()) com.example.spectrum.ShizukuHelper.openShizuku(a)
                    onChanged()
                }
            }
            b.setNeutralButton("Shizuku を入手") { _, _ ->
                val pkg = com.example.spectrum.ShizukuHelper.SHIZUKU_PACKAGE
                try {
                    a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")))
                } catch (e: Exception) {
                    try {
                        a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
                    } catch (e2: Exception) {
                        Toast.makeText(a, "Google Play を開けませんでした", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        b.show()
    }

    // ================================================================== オービス（登録した地点）

    interface CameraFileActions {
        fun importCameraCsv()
        fun exportCameraCsv()
    }

    /** 自分で登録したオービスの管理（登録・CSV の取り込みと書き出し・削除）。件数が変わったら [onChanged] */
    fun showCameras(a: Activity, files: CameraFileActions, onChanged: () -> Unit = {}) {
        val store = UserCameraStore(a)
        val col = a.column()
        val count = a.label("登録済み: ${store.count()} 件", 15f, bold = true)
        col.addView(count)
        fun changed() {
            count.text = "登録済み: ${store.count()} 件"
            onChanged()
        }
        col.addView(a.button("現在地をオービスとして登録（進行方向つき）") {
            EngineHost.registerCameraHere { ok ->
                Toast.makeText(a, if (ok) "登録しました" else "現在地が未取得です", Toast.LENGTH_SHORT).show()
                changed()
            }
        })
        col.addView(a.button("CSV を取り込む") { files.importCameraCsv() })
        col.addView(a.button("登録を CSV に書き出す") { files.exportCameraCsv() })
        col.addView(a.button("現在地付近（150m 以内）の登録を削除") {
            val f = EngineHost.latest
            val lat = f?.lat
            val lon = f?.lon
            if (lat == null || lon == null) {
                Toast.makeText(a, "現在地が未取得です", Toast.LENGTH_SHORT).show()
            } else {
                val n = store.removeNear(lat, lon)
                EngineHost.reloadUserCameras()
                changed()
                Toast.makeText(a, "$n 件削除しました", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(a.button("登録をすべて削除") {
            AlertDialog.Builder(a, THEME)
                .setMessage("登録したオービスをすべて削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    store.clear()
                    EngineHost.reloadUserCameras()
                    changed()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        })
        col.addView(a.label(
            "走行中に登録すると、その地点と進行方向を記録します（Android Auto の画面右上の「オービス登録」でも登録できます）。\n" +
                "CSV 形式: 緯度,経度,進行方位(度),制限速度,名称（1 行 1 件、見出し行は任意）。進行方位を入れると、その向きの車線だけに付きます。",
            12f, color = 0xFF8D96A3.toInt()
        ).apply { gravity = Gravity.START })
        AlertDialog.Builder(a, THEME)
            .setTitle("登録したオービス")
            .setView(a.scroll(col))
            .setPositiveButton("閉じる", null)
            .show()
    }

    // ================================================================== 動作状態（Android Auto だけで使う）

    /**
     * スマホでアプリを開かずに Android Auto だけで使うための設定と、いまの動作状態。
     * 問題のある項目の設定画面へのボタンを出す。
     */
    fun showRunStatus(a: Activity) {
        val items = RunDiagnostics.collect(a)
        val fixes = items.filter { !it.ok }.mapNotNull { it.fix }.distinct()
        val msg = RunDiagnostics.GUIDE + "\n\n" + RunDiagnostics.asText(items)
        val b = AlertDialog.Builder(a, THEME)
            .setTitle("Android Auto だけで使うための設定")
            .setMessage(msg)
            .setNegativeButton("閉じる", null)
        val primary = fixes.firstOrNull() ?: RunDiagnostics.Fix.BATTERY.takeIf {
            // 問題が無くても、電池の設定はいつでも開けるようにする
            items.none { i -> i.title == "電池" && i.ok }
        }
        if (primary != null) b.setPositiveButton(primary.label) { _, _ -> openFix(a, primary) }
        val second = fixes.getOrNull(1)
        if (second != null) b.setNeutralButton(second.label) { _, _ -> openFix(a, second) }
        b.show()
    }

    /** 問題を直すための端末の設定画面を開く */
    fun openFix(a: Activity, fix: RunDiagnostics.Fix) {
        for (i in RunDiagnostics.intentFor(a, fix)) {
            try {
                a.startActivity(i)
                if (fix == RunDiagnostics.Fix.BATTERY) {
                    Toast.makeText(a, "一覧（すべてのアプリ）から「Spectrum ハイウェイ」を「最適化しない」にしてください", Toast.LENGTH_LONG).show()
                }
                return
            } catch (e: Exception) {
                // 次の画面を試す
            }
        }
        Toast.makeText(a, "設定を開けませんでした", Toast.LENGTH_SHORT).show()
    }
}
