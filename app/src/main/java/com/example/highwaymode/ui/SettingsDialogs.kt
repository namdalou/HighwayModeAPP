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
import android.widget.RadioButton
import android.widget.RadioGroup
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

    // ================================================================== 渋滞情報（開発者向け設定）

    private val PROVIDERS = listOf("none" to "取得しない", "here" to "HERE Traffic", "tomtom" to "TomTom Traffic", "google" to "Google Routes")

    /** いまの渋滞情報の設定の短い説明（「HERE Traffic・5 分ごと」など） */
    fun trafficSummary(prefs: Prefs): String {
        val p = prefs.trafficProvider
        val name = PROVIDERS.firstOrNull { it.first == p }?.second ?: p
        if (p == "none") return "使わない（自車の走行から渋滞を推定）"
        val interval = when (prefs.trafficIntervalMin) {
            3 -> "3 分ごと"
            5 -> "5 分ごと"
            -1 -> "自動（3〜5 分）"
            else -> "取得しない"
        }
        val key = if (prefs.trafficKey(p).isBlank()) "・API キー未設定" else ""
        return "$name・$interval$key"
    }

    fun showTraffic(a: Activity, done: () -> Unit = {}) {
        val prefs = Prefs(a)
        val col = a.column()
        col.addView(a.label("取得元", 15f, bold = true))
        val pg = RadioGroup(a)
        val ids = HashMap<Int, String>()
        for ((k, label) in PROVIDERS) {
            val rb = RadioButton(a).apply {
                text = label
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            ids[rb.id] = k
            pg.addView(rb)
            if (prefs.trafficProvider == k) rb.isChecked = true
        }
        col.addView(pg)
        val keyEdit = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "API キー"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7D8590.toInt())
            setText(prefs.trafficKey(prefs.trafficProvider))
        }
        col.addView(keyEdit)
        val help = a.label("", 12f, color = 0xFF8D96A3.toInt())
        col.addView(help)
        fun updateHelp(p: String) {
            help.text = when (p) {
                "here" -> "HERE（developer.here.com）の API キー。前方 100km の道路の流れ（速度）と事故・規制を取得。"
                "tomtom" -> "TomTom（developer.tomtom.com）の API キー。渋滞（遅れ時間）・事故・通行止め・工事を取得。"
                "google" -> "Google Cloud の API キー（Routes API を有効化）。前方経路の混雑区間と渋滞を考慮した所要時間を取得。従量課金なので無料枠と料金を確認してください。"
                else -> "渋滞情報を使わず、自車の走行（渋滞の持続推定）だけで到着予定を計算します。"
            }
        }
        updateHelp(prefs.trafficProvider)
        var current = prefs.trafficProvider
        pg.setOnCheckedChangeListener { _, id ->
            prefs.setTrafficKey(current, keyEdit.text.toString())
            current = ids[id] ?: "none"
            keyEdit.setText(prefs.trafficKey(current))
            keyEdit.isEnabled = current != "none"
            updateHelp(current)
        }
        keyEdit.isEnabled = current != "none"

        col.addView(a.label("取得間隔", 15f, bold = true))
        val intervals = listOf(3 to "3 分ごと", 5 to "5 分ごと", -1 to "自動（前方に渋滞があれば 3 分、無ければ 5 分）", 0 to "取得しない")
        val ig = RadioGroup(a)
        val iids = HashMap<Int, Int>()
        for ((v, label) in intervals) {
            val rb = RadioButton(a).apply {
                text = label
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            iids[rb.id] = v
            ig.addView(rb)
            if (prefs.trafficIntervalMin == v) rb.isChecked = true
        }
        col.addView(ig)
        val shortRange = android.widget.CheckBox(a).apply {
            text = "通信量を抑える（前方 60km まで）"
            setTextColor(Color.WHITE)
            isChecked = prefs.trafficShortRange
        }
        col.addView(shortRange)
        col.addView(a.label("渋滞情報は高速道路を走行中だけ取得し、到着予定時刻と左の帯の色（赤＝渋滞）に反映します。施設の行には手前の渋滞の長さ・事故・通行止めを表示します。渋滞の音声案内は、設定画面の「音声案内」でオン・オフできます。API キーは端末内にだけ保存します。", 12f, color = 0xFF8D96A3.toInt()))

        fun save() {
            prefs.trafficProvider = current
            prefs.setTrafficKey(current, keyEdit.text.toString())
            prefs.trafficIntervalMin = iids[ig.checkedRadioButtonId] ?: 5
            prefs.trafficShortRange = shortRange.isChecked
        }
        AlertDialog.Builder(a, THEME)
            .setTitle("渋滞情報（リアルタイム）")
            .setView(a.scroll(col))
            .setPositiveButton("保存") { _, _ ->
                save()
                EngineHost.refreshTraffic()
                done()
            }
            .setNeutralButton("保存して今すぐ取得") { _, _ ->
                save()
                EngineHost.refreshTraffic()
                Toast.makeText(a, "高速道路を走行中なら取得します", Toast.LENGTH_SHORT).show()
                done()
            }
            .setNegativeButton("キャンセル", null)
            .show()
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
