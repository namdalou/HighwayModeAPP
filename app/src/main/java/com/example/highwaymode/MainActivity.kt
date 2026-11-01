package com.example.highwaymode

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.example.highwaymode.car.AutoDiagnostics
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.FacilityView
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.data.UserCameraStore
import com.example.highwaymode.dual.DualMode
import com.example.highwaymode.engine.DemoPreset
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.engine.RunDiagnostics
import com.example.highwaymode.ui.DualView
import com.example.highwaymode.ui.FacilityDetailDialog
import com.example.highwaymode.ui.HighwayPanelView
import com.example.highwaymode.ui.ModeBarView
import com.example.highwaymode.ui.SettingsDialogs
import com.example.spectrum.DiagnosticsActivity
import com.example.spectrum.MediaListenerService
import com.example.spectrum.MediaTracker
import com.example.spectrum.NowPlaying
import com.example.spectrum.PlayerView
import com.example.spectrum.SessionScanner
import com.example.spectrum.ShizukuHelper
import com.example.spectrum.SpectrumEngine
import kotlin.concurrent.thread

/**
 * スマホの画面。Spectrum（音楽）とハイウェイモードを 1 つにまとめたもの。
 *  - 高速道路の外: Spectrum
 *  - 高速道路の対象エリアに入ると自動で 2 画面（設定で変更可）、出ると Spectrum に戻る
 *  - 下部のバーで いつでも 音楽 / 2画面 / ハイウェイ を切替（右端の ⋮ でメニュー）
 */
class MainActivity : Activity(), HighwayPanelView.Callbacks, SettingsDialogs.CameraFileActions,
    ModeBarView.Callbacks, DualMode.Listener {

    private lateinit var panel: HighwayPanelView
    private lateinit var player: PlayerView
    private lateinit var bar: ModeBarView
    private lateinit var dual: DualView
    private lateinit var prefs: Prefs

    // 権限のボタン（足りないものだけ表示。Spectrum と同じく画面の下に並べる）
    private lateinit var locBtn: Button
    private lateinit var micBtn: Button
    private lateinit var accessBtn: Button
    private lateinit var dumpBtn: Button

    private val listener = EngineHost.SnapshotListener { s ->
        panel.setSnapshot(s)
        dual.setSnapshot(s)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NowPlaying.init(this)
        prefs = Prefs(this)
        // 車載画面で表示し続けるため、画面を消さない
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupEdgeToEdge()

        panel = HighwayPanelView(this)
        panel.callbacks = this
        panel.displayMode = prefs.displayMode
        player = PlayerView(this)
        bar = ModeBarView(this)
        bar.callbacks = this
        dual = DualView(this, player, panel, bar)

        locBtn = smallButton("位置情報を許可(ハイウェイ用)") { requestRuntime(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        micBtn = smallButton("マイク権限を許可(スペアナ用)") { requestRuntime(arrayOf(Manifest.permission.RECORD_AUDIO)) }
        accessBtn = smallButton("通知アクセスを許可(曲情報用)") { openNotificationAccess() }
        dumpBtn = smallButton("音声読み取りを許可(Shizuku)") { requestDumpWithShizuku() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in listOf(locBtn, micBtn, accessBtn, dumpBtn)) addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            applySystemBarPadding(this)
            addView(dual, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(row, LinearLayout.LayoutParams(-1, -2))
        })
    }

    override fun onStart() {
        super.onStart()
        panel.showCamerasInList = prefs.camerasInList
        EngineHost.addListener(listener)
        EngineHost.latest?.let {
            panel.setSnapshot(it)
            dual.setSnapshot(it)
        }
        DualMode.attach(this)
        DualMode.addListener(this)
        dual.setLayout(DualMode.layout, animate = false)
        updateHints()
        ensurePermissionsAndStart()
        SettingsDialogs.checkUpdateOnStart(this)
        AutoDiagnostics.maybeShowOnce(this, prefs)
        maybeShowBackgroundHint()
    }

    override fun onResume() {
        super.onResume()
        // Spectrum: 通知へのアクセスが許可済みなのに未接続なら、つなぎ直す
        val listenerOk = isListenerEnabled()
        if (listenerOk && !NowPlaying.listenerConnected) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, MediaListenerService::class.java))
            } catch (e: Exception) {
                // 次回の起動で再試行
            }
        }
        MediaTracker.start(this)
        SpectrumEngine.retry(applicationContext)
        updateButtons()
    }

    override fun onStop() {
        EngineHost.removeListener(listener)
        DualMode.removeListener(this)
        DualMode.detach()
        super.onStop()
    }

    // ------------------------------------------------------------------ 画面構成

    override fun onDualChanged() {
        dual.setLayout(DualMode.layout, animate = true)
        updateHints()
    }

    private fun updateHints() {
        bar.setSplitHint(if (DualMode.highwayActive && prefs.autoSwitch) "高速道路を出ると自動で音楽に戻ります" else null)
    }

    override fun onSelectLayout(l: DualLayout) {
        DualMode.select(l)
    }

    override fun onPlayPause() {
        NowPlaying.playPause()
    }

    // ------------------------------------------------------------------ 権限と起動

    private fun ensurePermissionsAndStart() {
        val need = ArrayList<String>()
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERMS)
        }
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) startTracking()
    }

    /** 権限ボタンから。以前に「許可しない」が続いていて確認が出ない場合は、設定画面へ案内する */
    private fun requestRuntime(perms: Array<String>) {
        requestPermissions(perms, REQ_BUTTON)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS && requestCode != REQ_BUTTON) return
        SpectrumEngine.retry(applicationContext)
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startTracking()
        } else {
            val coarseOnly = has(Manifest.permission.ACCESS_COARSE_LOCATION)
            panel.showMessage(
                if (coarseOnly) "「おおよその位置」では車線や距離を判定できません。\n「正確な位置」を許可してください。\n（タップで再度確認）"
                else "高速道路の判定に位置情報の権限が必要です。\n（タップで再度確認）"
            )
        }
        updateButtons()
        if (requestCode == REQ_BUTTON) {
            // 確認を出しても許可されず、もう確認が出ない状態 → アプリの設定画面へ
            val blocked = permissions.indices.any { i ->
                grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(permissions[i])
            }
            if (blocked) {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("権限の設定")
                    .setMessage("確認の画面が出ない状態です。アプリの設定 →「権限」で許可してください。")
                    .setPositiveButton("設定を開く") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        } catch (e: Exception) {
                            Toast.makeText(this, "設定を開けませんでした", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("閉じる", null)
                    .show()
            }
        }
    }

    private fun startTracking() {
        panel.showMessage(null)
        TrackingService.start(this)
    }

    override fun onOverlayTap() {
        requestRuntime(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** 足りない権限のボタンだけを出す */
    private fun updateButtons() {
        locBtn.visibility = if (has(Manifest.permission.ACCESS_FINE_LOCATION)) View.GONE else View.VISIBLE
        micBtn.visibility = if (has(Manifest.permission.RECORD_AUDIO)) View.GONE else View.VISIBLE
        accessBtn.visibility = if (isListenerEnabled()) View.GONE else View.VISIBLE
        // Amazon Music など音声の番号を知らせないアプリも Shizuku なしで自動で探すので、
        // Shizuku のボタンは常には出さない（メニュー →「音楽（Spectrum）」から使える）
        dumpBtn.visibility = View.GONE
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "設定を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    /** Spectrum: Shizuku で DUMP 権限（音声の番号の読み取り）を許可する */
    private fun requestDumpWithShizuku() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("音声の読み取りを許可")
            .setMessage(
                "Amazon Music・YouTube など音声の番号を知らせないアプリは、Shizuku が無くても\n" +
                    "再生中の音を自動で探してスペアナを動かします（見つかるまで数秒〜1 分ほど）。\n\n" +
                    "それでもスペアナが動かないときだけ、この設定を使ってください。\n" +
                    "Shizuku アプリを起動した状態で「許可する」を押してください。\n" +
                    "(Shizuku はワイヤレスデバッグで開始できます。一度許可すれば Shizuku は止めても大丈夫です)"
            )
            .setPositiveButton("許可する") { _, _ ->
                dumpBtn.isEnabled = false
                ShizukuHelper.grantDump(this) { ok, msg ->
                    dumpBtn.isEnabled = true
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (!ok && !ShizukuHelper.isRunning()) ShizukuHelper.openShizuku(this)
                    updateButtons()
                }
            }
            .setNeutralButton("今後表示しない") { _, _ ->
                getSharedPreferences(MediaListenerService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_HIDE_DUMP_BUTTON, true).apply()
                updateButtons()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        textSize = 12f
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------ ハイウェイモードの操作

    override fun onFacilityTap(f: FacilityView) {
        FacilityDetailDialog.show(this, f)
    }

    override fun onToggleDisplay() {
        val next = (panel.displayMode + 1) % 3
        panel.displayMode = next
        prefs.displayMode = next
    }

    override fun onReroute() {
        EngineHost.post { it.forceRematch() }
        Toast.makeText(this, "現在地の道路を再探索します", Toast.LENGTH_SHORT).show()
    }

    override fun onMenu() {
        val generalOn = EngineHost.latest?.status == EngineStatus.GENERAL_ROAD_MODE
        val items = ArrayList<Pair<String, () -> Unit>>()
        // 横画面ではハイウェイモードの下のボタンを出さない（一覧を大きくするため）ので、ここにも置く
        val modeLabel = when (panel.displayMode) {
            1 -> "一覧"
            2 -> "SA/PA のみ"
            else -> "大きく"
        }
        items.add("ハイウェイの表示を切替（いま: $modeLabel）" to { onToggleDisplay() })
        items.add("現在地の道路を再探索（上下線を取り違えたとき）" to { onReroute() })
        if (EngineHost.demoRunning) items.add("デモ走行を停止" to { EngineHost.stopDemo() })
        else items.add("デモ走行…（自動切替も確認できます）" to { showDemoMenu() })
        items.add("画面の自動切替（高速道路で 2 画面）" to { showDualSettings() })
        items.add("音楽（Spectrum）: 曲情報・音声の設定と診断" to { showMusicSettings() })
        items.add((if (generalOn) "一般道モードを解除" else "一般道モード（高速道路の判定を止める）") to {
            EngineHost.post { it.generalRoadMode = !generalOn }
        })
        items.add("運転傾向の学習をリセット" to {
            EngineHost.post { it.driver.reset() }
            prefs.driverProfile = null
            Toast.makeText(this, "リセットしました", Toast.LENGTH_SHORT).show()
        })
        items.add("地図データ（更新・作成・オンライン取得）" to { SettingsDialogs.showMapData(this) })
        items.add("渋滞情報（取得元・間隔）" to { SettingsDialogs.showTraffic(this) })
        items.add("オービス（音声・登録・CSV）" to {
            SettingsDialogs.showCameras(this, this)
        })
        items.add("Android Auto だけで使うための設定（動作状態）" to { showRunStatus() })
        items.add("Android Auto の状態（一覧に出ないとき）" to { AutoDiagnostics.show(this) })
        items.add("このアプリについて" to { showAbout() })
        items.add("終了" to {
            TrackingService.stop(this)
            finishAndRemoveTask()
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("メニュー")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second.invoke() }
            .show()
    }

    /**
     * スマホでアプリを開かずに Android Auto だけで使うための設定と、いまの動作状態。
     * 問題のある項目の設定画面へのボタンを出す。
     */
    private fun showRunStatus() {
        val items = RunDiagnostics.collect(this)
        val fixes = items.filter { !it.ok }.mapNotNull { it.fix }.distinct()
        val msg = RunDiagnostics.GUIDE + "\n\n" + RunDiagnostics.asText(items)
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Android Auto だけで使うための設定")
            .setMessage(msg)
            .setNegativeButton("閉じる", null)
        val primary = fixes.firstOrNull() ?: RunDiagnostics.Fix.BATTERY.takeIf {
            // 問題が無くても、電池の設定はいつでも開けるようにする
            items.none { i -> i.title == "電池" && i.ok }
        }
        if (primary != null) b.setPositiveButton(primary.label) { _, _ -> openFix(primary) }
        val second = fixes.getOrNull(1)
        if (second != null) b.setNeutralButton(second.label) { _, _ -> openFix(second) }
        b.show()
    }

    private fun openFix(fix: RunDiagnostics.Fix) {
        for (i in RunDiagnostics.intentFor(this, fix)) {
            try {
                startActivity(i)
                if (fix == RunDiagnostics.Fix.BATTERY) {
                    Toast.makeText(this, "一覧（すべてのアプリ）から「Spectrum ハイウェイ」を「最適化しない」にしてください", Toast.LENGTH_LONG).show()
                }
                return
            } catch (e: Exception) {
                // 次の画面を試す
            }
        }
        Toast.makeText(this, "設定を開けませんでした", Toast.LENGTH_SHORT).show()
    }

    /** Android Auto で使ったことがあり、電池の最適化が有効なままなら、一度だけ案内する */
    private fun maybeShowBackgroundHint() {
        if (!prefs.carUsedOnce || prefs.backgroundHintShown) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.backgroundHintShown = true
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Android Auto だけで使う場合")
            .setMessage(RunDiagnostics.GUIDE + "\n\n（あとからメニュー →「Android Auto だけで使うための設定」でも確認できます）")
            .setPositiveButton(RunDiagnostics.Fix.BATTERY.label) { _, _ -> openFix(RunDiagnostics.Fix.BATTERY) }
            .setNegativeButton("あとで", null)
            .show()
    }

    /** 高速道路に入ったときの画面（自動切替） */
    private fun showDualSettings() {
        val labels = arrayOf(
            "自動で「2画面」にする（左に音楽・右にハイウェイ）",
            "自動で「ハイウェイ」だけにする",
            "自動で切り替えない（下のバーで手動）"
        )
        val current = when {
            !prefs.autoSwitch -> 2
            prefs.highwayLayout == DualLayout.HIGHWAY.name -> 1
            else -> 0
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("高速道路に入ったときの画面")
            .setSingleChoiceItems(labels, current) { d, which ->
                when (which) {
                    0 -> {
                        prefs.autoSwitch = true
                        prefs.highwayLayout = DualLayout.SPLIT.name
                    }
                    1 -> {
                        prefs.autoSwitch = true
                        prefs.highwayLayout = DualLayout.HIGHWAY.name
                    }
                    else -> prefs.autoSwitch = false
                }
                DualMode.settingsChanged()
                d.dismiss()
                if (which != 2) {
                    Toast.makeText(this, "高速道路などに入って 5 秒で切り替わり、出て 15 秒で音楽に戻ります", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** Spectrum の設定（曲情報・音声の読み取り）と診断画面 */
    private fun showMusicSettings() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add((if (isListenerEnabled()) "通知へのアクセス（許可済み）" else "通知へのアクセスを許可（曲名・ジャケット用）") to { openNotificationAccess() })
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            items.add("マイク権限を許可（スペアナ用・録音はしません）" to { requestRuntime(arrayOf(Manifest.permission.RECORD_AUDIO)) })
        }
        items.add((if (SessionScanner.hasPermission(this)) "音声の読み取り（許可済み）" else "音声の読み取りを許可（Shizuku・自動で見つからないとき用）") to {
            getSharedPreferences(MediaListenerService.PREFS, MODE_PRIVATE).edit().putBoolean(KEY_HIDE_DUMP_BUTTON, false).apply()
            requestDumpWithShizuku()
        })
        items.add("Spectrum の診断画面（曲情報の取得元を選ぶ）" to {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("音楽（Spectrum）")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second.invoke() }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showDemoMenu() {
        val presets = DemoPreset.values()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("デモ走行（実際の地図データ上を模擬走行）")
            .setItems(presets.map { it.label }.toTypedArray()) { _, which -> EngineHost.startDemo(presets[which]) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ------------------------------------------------------------------ オービス CSV

    override fun importCameraCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
        startActivityForResult(i, REQ_IMPORT)
    }

    override fun exportCameraCsv() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "highway_cameras.csv")
        startActivityForResult(i, REQ_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val store = UserCameraStore(this)
        thread(name = "camera-csv") {
            val msg = try {
                when (requestCode) {
                    REQ_IMPORT -> {
                        val (added, skipped) = store.import(uri)
                        EngineHost.reloadUserCameras()
                        "取り込みました: $added 件" + if (skipped > 0) "（読めなかった行 $skipped）" else ""
                    }
                    REQ_EXPORT -> {
                        store.export(uri)
                        "書き出しました: ${store.count()} 件"
                    }
                    else -> null
                }
            } catch (e: Exception) {
                "失敗しました: ${e.message}"
            }
            if (msg != null) runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    private fun showAbout() {
        val msg = """
            音楽（Spectrum）とハイウェイモードを 1 つにまとめたアプリです。

            ・高速道路の外: 再生中の曲（ジャケット・曲名・操作）とスペクトラムアナライザーを表示します。
            ・高速道路・有料道路・都市高速・主要バイパス（名豊道路・名阪国道など）に入ると、自動で 2 画面（左に音楽、右にハイウェイモード）に切り替わり、出ると音楽の画面に戻ります。下のバーでいつでも切り替えられます。
            ・ハイウェイモード: 前方の IC / JCT / SA / PA / スマート IC / 本線料金所までの距離と到着予定時刻、SA/PA の設備、ETC 専用、渋滞・事故・通行止め、オービスを表示します。
            ・進行方向: ジャイロと GPS ドップラー方位をカルマンフィルタで融合し、隠れマルコフモデルで上下線・ランプを判定します。トンネル内は推測航法。Android Auto 接続時は車速信号（と車両のジャイロ）も使います。
            ・到着予測: 制限速度・カーブ・料金所・運転傾向（学習）・現在の速度・直近の流れに加え、設定した場合はリアルタイムの渋滞情報（HERE / TomTom / Google）を反映します。

            地図データ © OpenStreetMap contributors（ODbL）
            施設・設備・ETC専用の情報は OpenStreetMap の登録内容に依存します。運転中は画面を注視せず、安全を最優先にしてください。
        """.trimIndent()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.app_name))
            .setMessage(msg)
            .setPositiveButton("閉じる", null)
            .show()
    }

    // ------------------------------------------------------------------ 全画面（エッジツーエッジ）

    @Suppress("DEPRECATION")
    private fun setupEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** システムバー・切り欠きと重ならないよう余白を入れる */
    @Suppress("DEPRECATION")
    private fun applySystemBarPadding(v: View) {
        v.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    companion object {
        private const val REQ_PERMS = 10
        private const val REQ_BUTTON = 11
        private const val REQ_IMPORT = 20
        private const val REQ_EXPORT = 21
        /** Spectrum と同じキー（「今後表示しない」） */
        private const val KEY_HIDE_DUMP_BUTTON = "hide_dump_button"
    }
}
