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
import com.example.highwaymode.core.engine.FacilityView
import com.example.highwaymode.data.AppSettings
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.dual.DualMode
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.engine.RunDiagnostics
import com.example.highwaymode.nav.NavNotifications
import com.example.highwaymode.ui.DevAccess
import com.example.highwaymode.ui.DualView
import com.example.highwaymode.ui.FacilityDetailDialog
import com.example.highwaymode.ui.HighwayPanelView
import com.example.highwaymode.ui.ModeBarView
import com.example.highwaymode.ui.ScreenOptions
import com.example.highwaymode.ui.SettingsActivity
import com.example.highwaymode.ui.SettingsDialogs
import com.example.spectrum.MediaListenerService
import com.example.spectrum.MediaTracker
import com.example.spectrum.NowPlaying
import com.example.spectrum.PlayerView
import com.example.spectrum.SpectrumEngine
import com.example.spectrum.SpectrumStyle

/**
 * スマホの画面。Spectrum（音楽）とハイウェイモードを 1 つにまとめたもの。
 *  - 高速道路の外: Spectrum
 *  - 高速道路の対象エリアに入ると自動で 2 画面（設定で変更可）、出ると Spectrum に戻る
 *  - 下部のバーで いつでも 音楽 / 2画面 / ハイウェイ を切替（右端の ⋮ で設定画面）
 *  - 音楽の画面の何もない場所を 20 回続けてタップ → パスワード → 開発者向け設定
 */
class MainActivity : Activity(), HighwayPanelView.Callbacks, ModeBarView.Callbacks, DualMode.Listener {

    private lateinit var panel: HighwayPanelView
    private lateinit var player: PlayerView
    private lateinit var bar: ModeBarView
    private lateinit var dual: DualView
    private lateinit var prefs: Prefs

    // 権限のボタン（足りないものだけ表示。Spectrum と同じく画面の下に並べる）
    private lateinit var locBtn: Button
    private lateinit var micBtn: Button
    private lateinit var accessBtn: Button

    private val listener = EngineHost.SnapshotListener { s ->
        panel.setSnapshot(s)
        dual.setSnapshot(s)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // スマホの画面で開いているかを数える（Android Auto の切断後に常駐を止めるかどうかの判定に使う）
        AppPresence.install(this)
        NowPlaying.init(this)
        prefs = Prefs(this)
        AppSettings.apply(this)
        // 画面を消さない（設定。既定はいつも）・画面の向き
        ScreenOptions.applyKeepScreenOn(this, prefs, DualMode.highwayActive)
        ScreenOptions.applyOrientation(this, prefs)
        setupEdgeToEdge()

        panel = HighwayPanelView(this)
        panel.callbacks = this
        panel.displayMode = prefs.displayMode
        player = PlayerView(this)
        // 何もない場所を 20 回続けてタップ → パスワードを聞いて開発者向け設定（Spectrum の診断画面もここから）
        player.onSecretTap = { DevAccess.prompt(this) }
        // カーナビの案内の帯をタップ → Google マップを開く。通知の受け取り口もここで用意する（エンジンが動いていなくても帯を出す）
        player.onGuideTap = { NavNotifications.openNavApp(this) }
        NavNotifications.install(this)
        // スペアナを長押し → 表示の種類を順に切り替える（LED → バー → … → メーター）
        player.onSpectrumLongPress = {
            val next = SpectrumStyle.nextVisual(SpectrumStyle.visual)
            SpectrumStyle.setVisual(this, next)
            Toast.makeText(this, "スペアナ: ${SpectrumStyle.VISUAL_LABELS[next]}（長押しで切り替え）", Toast.LENGTH_SHORT).show()
        }
        bar = ModeBarView(this)
        bar.callbacks = this
        dual = DualView(this, player, panel, bar)

        locBtn = smallButton("位置情報を許可(ハイウェイ用)") { requestRuntime(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        micBtn = smallButton("マイク権限を許可(スペアナ用)") { requestRuntime(arrayOf(Manifest.permission.RECORD_AUDIO)) }
        accessBtn = smallButton("通知アクセスを許可(曲情報用)") { openNotificationAccess() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in listOf(locBtn, micBtn, accessBtn)) addView(b, LinearLayout.LayoutParams(0, -2, 1f))
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
        // 設定画面から戻ったとき、設定の変更を反映する
        panel.displayMode = prefs.displayMode
        panel.showCamerasInList = prefs.camerasInList
        ScreenOptions.applyOrientation(this, prefs)
        EngineHost.addListener(listener)
        EngineHost.latest?.let {
            panel.setSnapshot(it)
            dual.setSnapshot(it)
        }
        DualMode.attach(this)
        DualMode.addListener(this)
        dual.setLayout(DualMode.layout, animate = false)
        updateHints()
        ScreenOptions.applyKeepScreenOn(this, prefs, DualMode.highwayActive)
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
        // 「高速道路を走行中だけ画面を消さない」の切り替え
        ScreenOptions.applyKeepScreenOn(this, prefs, DualMode.highwayActive)
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
        // Shizuku のボタンは出さない（開発者向け設定から使える）
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
        // 表示中の車の画面にも反映する
        AppSettings.changed(this)
    }

    override fun onReroute() {
        EngineHost.post { it.forceRematch() }
        Toast.makeText(this, "現在地の道路を再探索します", Toast.LENGTH_SHORT).show()
    }

    /** 下部のバーの［⋮］・ハイウェイ側の［設定］: 設定画面を開く */
    override fun onMenu() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Android Auto で使ったことがあり、電池の最適化が有効なままなら、一度だけ案内する */
    private fun maybeShowBackgroundHint() {
        if (!prefs.carUsedOnce || prefs.backgroundHintShown) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.backgroundHintShown = true
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Android Auto だけで使う場合")
            .setMessage(RunDiagnostics.GUIDE + "\n\n（あとから 設定 →「スマホを開かずに Android Auto で使う」でも確認できます）")
            .setPositiveButton(RunDiagnostics.Fix.BATTERY.label) { _, _ -> SettingsDialogs.openFix(this, RunDiagnostics.Fix.BATTERY) }
            .setNegativeButton("あとで", null)
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
    }
}
