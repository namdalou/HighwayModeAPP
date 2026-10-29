package com.example.highwaymode

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.FacilityView
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.data.UserCameraStore
import com.example.highwaymode.engine.DemoPreset
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.ui.FacilityDetailDialog
import com.example.highwaymode.ui.HighwayPanelView
import com.example.highwaymode.ui.SettingsDialogs
import kotlin.concurrent.thread

class MainActivity : Activity(), HighwayPanelView.Callbacks, SettingsDialogs.CameraFileActions {

    private lateinit var panel: HighwayPanelView
    private lateinit var prefs: Prefs
    private val listener = EngineHost.SnapshotListener { s -> panel.setSnapshot(s) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupEdgeToEdge()
        panel = HighwayPanelView(this)
        panel.callbacks = this
        panel.displayMode = prefs.displayMode
        panel.setOnApplyWindowInsetsListener { v, insets ->
            applyInsets(v as HighwayPanelView, insets)
            insets
        }
        setContentView(panel)
    }

    override fun onStart() {
        super.onStart()
        panel.showCamerasInList = prefs.camerasInList
        EngineHost.addListener(listener)
        EngineHost.latest?.let { panel.setSnapshot(it) }
        ensurePermissionsAndStart()
        SettingsDialogs.checkUpdateOnStart(this)
    }

    override fun onStop() {
        EngineHost.removeListener(listener)
        super.onStop()
    }

    // ------------------------------------------------------------------ 権限と起動

    private fun ensurePermissionsAndStart() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERMS)
        } else {
            startTracking()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startTracking()
        } else {
            val coarseOnly = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            panel.showMessage(
                if (coarseOnly) "「おおよその位置」では車線や距離を判定できません。\n「正確な位置」を許可してください。\n（タップで再度確認）"
                else "現在地の取得に位置情報の権限が必要です。\n（タップで再度確認）"
            )
        }
    }

    private fun startTracking() {
        panel.showMessage(null)
        TrackingService.start(this)
    }

    override fun onOverlayTap() {
        ensurePermissionsAndStart()
    }

    // ------------------------------------------------------------------ 画面の操作

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
        if (EngineHost.demoRunning) items.add("デモ走行を停止" to { EngineHost.stopDemo() })
        else items.add("デモ走行…" to { showDemoMenu() })
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
            高速道路・有料道路・都市高速を走行中に、前方の IC / JCT / SA / PA / スマートIC / 本線料金所までの距離と到着予定時刻を表示します。

            ・対象: 全国の高速道路・有料道路・都市高速と、IC のある主要バイパス（名豊道路・名阪国道など）。
            ・進行方向: ジャイロ（重力方向へ射影した回転率）と GPS ドップラー方位をカルマンフィルタで融合し、隠れマルコフモデルで上下線・ランプを判定します。トンネル内は推測航法で道路に沿って進めます。Android Auto 接続時は車速信号（と車両のジャイロ）も使います。
            ・到着予測: 制限速度・カーブ・料金所・あなたの運転傾向（学習）・現在の速度・直近の流れに加え、設定した場合はリアルタイムの渋滞情報（HERE / TomTom / Google）を反映します。
            ・オービス: 地図データの速度取締機と、自分で登録・取り込みした地点を表示・音声案内します。

            地図データ © OpenStreetMap contributors（ODbL）
            データ取得: 同梱・配布データ（HWDB）／Overpass API
            施設・設備・ETC専用の情報は OpenStreetMap の登録内容に依存します。運転中は画面を注視せず、安全を最優先にしてください。
        """.trimIndent()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("ハイウェイモードについて")
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

    @Suppress("DEPRECATION")
    private fun applyInsets(v: HighwayPanelView, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setInsets(i.left, i.top, i.right, i.bottom)
        } else {
            v.setInsets(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
        }
    }

    companion object {
        private const val REQ_PERMS = 10
        private const val REQ_IMPORT = 20
        private const val REQ_EXPORT = 21
    }
}
