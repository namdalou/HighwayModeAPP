package com.example.highwaymode.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
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
import java.util.Locale
import kotlin.concurrent.thread

/** 設定画面（地図データ・渋滞情報・オービス） */
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

    fun showMapData(a: Activity) {
        val prefs = Prefs(a)
        val col = a.column()
        val status = a.label("読み込み中…")
        col.addView(status)
        val progress = a.label("", 13f, color = 0xFFFFC107.toInt())
        col.addView(progress)

        fun refresh() {
            thread(name = "hwdb-info") {
                val list = HwdbManager.list(a)
                val (tiles, local) = EngineHost.loadedTileStats()
                val cacheMb = EngineHost.cacheSizeBytes() / 1e6
                val sb = StringBuilder()
                if (list.isEmpty()) {
                    sb.append("端末内の地図データ: なし\n（その場で Overpass API から取得します。下の「最新データに更新」で全国データを入れると、通信を待たずに表示できます）\n")
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
                sb.append(String.format(Locale.JAPAN, "\n読み込み中のタイル: %d（うち端末内データ %d）\nオンライン取得のキャッシュ: %.1f MB\n", tiles, local, cacheMb))
                sb.append("配布元: ").append(prefs.manifestUrl.ifBlank { "未設定" }).append('\n')
                prefs.availableVersion?.let { sb.append("配布元の最新版: ").append(it).append('\n') }
                a.runOnUiThread { status.text = sb.toString() }
            }
        }

        val syncListener = DataSyncState.Listener {
            progress.text = if (DataSyncState.running) {
                "${DataSyncState.title}：${DataSyncState.message ?: ""}" + if (DataSyncState.percent >= 0) "（${DataSyncState.percent}%）" else ""
            } else DataSyncState.lastResult ?: ""
            if (!DataSyncState.running) refresh()
        }
        DataSyncState.addListener(syncListener)
        syncListener.onChanged()

        col.addView(a.button("最新データに更新（ダウンロード）") {
            if (prefs.manifestUrl.isBlank()) {
                Toast.makeText(a, "配布元（目録 URL）を設定してください", Toast.LENGTH_LONG).show()
                editManifestUrl(a, prefs) { refresh() }
            } else {
                DataSyncService.download(a)
            }
        })
        col.addView(a.button("地方を指定して端末内で作成（Overpass）") { chooseRegions(a) })
        col.addView(a.button("更新・作成を中止") { DataSyncService.cancel(a) })
        val online = CheckBox(a).apply {
            text = "端末内データに無い範囲はオンラインで取得する"
            setTextColor(Color.WHITE)
            isChecked = prefs.onlineTiles
            setOnCheckedChangeListener { _, v -> prefs.onlineTiles = v }
        }
        col.addView(online)
        val auto = CheckBox(a).apply {
            text = "起動時に新しい地図データを確認する（1日1回）"
            setTextColor(Color.WHITE)
            isChecked = prefs.autoCheckUpdate
            setOnCheckedChangeListener { _, v -> prefs.autoCheckUpdate = v }
        }
        col.addView(auto)
        col.addView(a.button("配布元（目録 URL）の設定") { editManifestUrl(a, prefs) { refresh() } })
        col.addView(a.button("Overpass サーバーの設定") { editEndpoints(a, prefs) })
        col.addView(a.button("オンライン取得のキャッシュを削除") {
            EngineHost.clearCache()
            Toast.makeText(a, "削除しました", Toast.LENGTH_SHORT).show()
            refresh()
        })
        col.addView(a.button("ダウンロード・作成したデータを削除") {
            thread {
                for (i in HwdbManager.list(a)) if (i.kind != HwdbManager.Kind.BUNDLED) i.file?.delete()
                HwdbManager.notifyChanged()
                a.runOnUiThread { refresh() }
            }
        })
        col.addView(a.label("地図データは OpenStreetMap（© OpenStreetMap contributors, ODbL）から作成しています。全国データは PC 用ツールや GitHub Actions で作成・配布できます（README 参照）。", 12f, color = 0xFF8D96A3.toInt()))
        refresh()
        AlertDialog.Builder(a, THEME)
            .setTitle("地図データ")
            .setView(a.scroll(col))
            .setPositiveButton("閉じる", null)
            .setOnDismissListener { DataSyncState.removeListener(syncListener) }
            .show()
    }

    private fun chooseRegions(a: Activity) {
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

    private fun editManifestUrl(a: Activity, prefs: Prefs, done: () -> Unit) {
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

    private fun editEndpoints(a: Activity, prefs: Prefs) {
        val edit = EditText(a)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
        edit.setText(prefs.endpoints.joinToString("\n"))
        edit.setTextColor(Color.WHITE)
        AlertDialog.Builder(a, THEME)
            .setTitle("Overpass API（1 行に 1 つ、上から順に使用）")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                prefs.endpoints = edit.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            }
            .setNeutralButton("既定に戻す") { _, _ -> prefs.endpoints = Prefs.DEFAULT_ENDPOINTS }
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

    fun showTraffic(a: Activity) {
        val prefs = Prefs(a)
        val col = a.column()
        col.addView(a.label("取得元", 15f, bold = true))
        val providers = listOf("none" to "取得しない", "here" to "HERE Traffic", "tomtom" to "TomTom Traffic", "google" to "Google Routes")
        val pg = RadioGroup(a)
        val ids = HashMap<Int, String>()
        for ((k, label) in providers) {
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
        val shortRange = CheckBox(a).apply {
            text = "通信量を抑える（前方 60km まで）"
            setTextColor(Color.WHITE)
            isChecked = prefs.trafficShortRange
        }
        col.addView(shortRange)
        val voice = CheckBox(a).apply {
            text = "渋滞・ETCカード・給油を音声で知らせる"
            setTextColor(Color.WHITE)
            isChecked = prefs.trafficVoice
        }
        col.addView(voice)
        col.addView(a.label("渋滞情報は高速道路を走行中だけ取得し、到着予定時刻と左の帯の色（赤＝渋滞）に反映します。施設の行には手前の渋滞の長さ・事故・通行止めを表示します。", 12f, color = 0xFF8D96A3.toInt()))

        fun save() {
            prefs.trafficProvider = current
            prefs.setTrafficKey(current, keyEdit.text.toString())
            prefs.trafficIntervalMin = iids[ig.checkedRadioButtonId] ?: 5
            prefs.trafficShortRange = shortRange.isChecked
            prefs.trafficVoice = voice.isChecked
        }
        AlertDialog.Builder(a, THEME)
            .setTitle("渋滞情報（リアルタイム）")
            .setView(a.scroll(col))
            .setPositiveButton("保存") { _, _ ->
                save()
                EngineHost.refreshTraffic()
            }
            .setNeutralButton("保存して今すぐ取得") { _, _ ->
                save()
                EngineHost.refreshTraffic()
                Toast.makeText(a, "高速道路を走行中なら取得します", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ================================================================== オービス

    interface CameraFileActions {
        fun importCameraCsv()
        fun exportCameraCsv()
    }

    fun showCameras(a: Activity, files: CameraFileActions) {
        val prefs = Prefs(a)
        val store = UserCameraStore(a)
        val col = a.column()
        val count = a.label("登録済み: ${store.count()} 件")
        col.addView(count)
        val voice = CheckBox(a).apply {
            text = "接近を音声で知らせる（1km・500m 手前）"
            setTextColor(Color.WHITE)
            isChecked = prefs.cameraVoice
            setOnCheckedChangeListener { _, v -> prefs.cameraVoice = v }
        }
        col.addView(voice)
        val inList = CheckBox(a).apply {
            text = "施設の一覧にも並べる（オフでも 2km 以内は上部に表示）"
            setTextColor(Color.WHITE)
            isChecked = prefs.camerasInList
            setOnCheckedChangeListener { _, v -> prefs.camerasInList = v }
        }
        col.addView(inList)
        col.addView(a.button("現在地をオービスとして登録（進行方向つき）") {
            EngineHost.registerCameraHere { ok ->
                Toast.makeText(a, if (ok) "登録しました" else "現在地が未取得です", Toast.LENGTH_SHORT).show()
                count.text = "登録済み: ${store.count()} 件"
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
                count.text = "登録済み: ${store.count()} 件"
                Toast.makeText(a, "$n 件削除しました", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(a.button("登録をすべて削除") {
            AlertDialog.Builder(a, THEME)
                .setMessage("登録したオービスをすべて削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    store.clear()
                    EngineHost.reloadUserCameras()
                    count.text = "登録済み: 0 件"
                }
                .setNegativeButton("キャンセル", null)
                .show()
        })
        col.addView(a.label(
            "地図データに含まれる速度取締機（OpenStreetMap の highway=speed_camera）に加えて、自分で登録・取り込みした地点を表示します。\n" +
                "CSV 形式: 緯度,経度,進行方位(度),制限速度,名称（1 行 1 件、見出し行は任意）。\n" +
                "※ 位置は網羅されていません。制限速度を守って安全に運転してください。",
            12f, color = 0xFF8D96A3.toInt()
        ).apply { gravity = Gravity.START })
        AlertDialog.Builder(a, THEME)
            .setTitle("オービス（速度取締機）")
            .setView(a.scroll(col))
            .setPositiveButton("閉じる", null)
            .show()
    }
}
