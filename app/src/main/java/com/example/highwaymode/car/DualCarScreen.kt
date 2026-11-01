package com.example.highwaymode.car

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.highwaymode.R
import com.example.highwaymode.core.dual.Box
import com.example.highwaymode.core.dual.DualFrame
import com.example.highwaymode.core.dual.DualGeometry
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.dual.DualMode
import com.example.highwaymode.dual.ModeBarRenderer
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.ui.PanelRenderer
import com.example.spectrum.MediaTracker
import com.example.spectrum.NowPlaying
import com.example.spectrum.PlayerRenderer
import com.example.spectrum.SpectrumEngine

/**
 * 車の画面（Android Auto）。ナビ用テンプレートの地図面に、Spectrum とハイウェイモードを描く。
 *
 *  - 高速道路の外: Spectrum を全画面（従来の Spectrum と同じ）
 *  - 高速道路の対象エリアに入ると自動で 2 画面（左: Spectrum の縦レイアウト / 右: ハイウェイモード）
 *  - 対象エリア内は下部に切替バー（音楽 / 2画面 / ハイウェイ）
 *  - タッチ操作のできない車でも使えるよう、同じ切替を右上のボタン（画面切替）にも置く
 *
 * 描画は専用スレッドで行う。Spectrum が再生中で見えている間は約 30fps（Android Auto の映像と同じ程度）、
 * 一時停止中は約 15fps、ハイウェイだけのときは 10fps（電池の節約）。
 * ハイウェイモードの画面は内容が変わったとき（約 1 秒ごと）だけ描き直し、間は記録した描画を再生する。
 *
 * 表示する状態は、毎フレーム エンジンの最新の状態（EngineHost.latest）を読む。
 * 画面の状態（他の画面を重ねた・Android Auto の別のアプリを前に出した）によらず、スマホの画面と同じ内容になる。
 */
class DualCarScreen(carContext: CarContext, private val onReady: () -> Unit) :
    Screen(carContext), SurfaceCallback, DualMode.Listener {

    companion object {
        private const val TAG = "DualCarScreen"
        /** Spectrum が再生中で見えているときの描画間隔（約 30fps） */
        private const val FRAME_MS = 33L
        /** Spectrum が見えていて一時停止中の描画間隔（曲名の流し表示用。約 15fps） */
        private const val PAUSED_FRAME_MS = 66L
        /** ハイウェイモードだけのときの描画間隔 */
        private const val IDLE_FRAME_MS = 100L
        /** 曲情報の取りこぼしを確認する間隔 */
        private const val HEAL_MS = 2000L
        /** ハイウェイモードの画面を描き直す間隔（時計・残り時間） */
        private const val PANEL_REFRESH_MS = 1000L
        /** 画面構成が変わったときのフェードの長さ */
        private const val FADE_MS = 260L
        /** ハイウェイだけにしてから Spectrum の音声解析を止めるまで（すぐ戻す場合に備える） */
        private const val SPECTRUM_RELEASE_DELAY_MS = 4000L
    }

    private val prefs = Prefs(carContext)
    private val main = Handler(Looper.getMainLooper())

    // ---- 描画部品
    // 2 画面の左側は、運転中でも読めるよう曲名・アルバム名・アーティスト名を大きくした表示（large）
    private val music = PlayerRenderer().apply {
        onArtTap = { openPlayingAppOnCar() }
    }
    private val panel = PanelRenderer(1f).apply {
        showButtons = false
        attribution = true
        // 時刻は Android Auto が出すので省き、見出しの右側は右上のボタンのために空ける
        showClock = false
        displayMode = prefs.displayMode
    }
    private val bar = ModeBarRenderer(1f)
    private val dividerPaint = Paint().apply { color = Color.rgb(24, 38, 50) }
    private val fadePaint = Paint()

    // ---- 描画面（描画スレッドとメインスレッドで共有）
    @Volatile private var surface: Surface? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var density = 1f
    private val visible = Rect()
    @Volatile private var snap: HighwaySnapshot? = EngineHost.latest
    @Volatile private var lastFrame: DualFrame? = null
    @Volatile private var panelInvalid = true
    /** 自動切替の設定（描画スレッドで設定を読まないよう、メインスレッドで写しておく） */
    @Volatile private var autoSwitch = prefs.autoSwitch

    private var drawThread: HandlerThread? = null
    @Volatile private var drawHandler: Handler? = null

    // ---- ハイウェイモード画面の記録（Android 10 以降）
    private var panelNode: Any? = null
    private var panelDrawnAt = 0L
    private var panelBoxKey = ""

    // ---- Spectrum の音声解析
    private val specListener: (FloatArray) -> Unit = { music.setSpectrum(it) }
    private var spectrumAcquired = false
    private val releaseSpectrum = Runnable { stopSpectrum() }

    private val snapshotListener = EngineHost.SnapshotListener { s ->
        snap = s
        panelInvalid = true
    }

    private val frame = object : Runnable {
        override fun run() {
            val next = try {
                drawFrame()
            } catch (e: Exception) {
                Log.w(TAG, "draw failed: ${e.message}")
                IDLE_FRAME_MS
            }
            drawHandler?.postDelayed(this, next)
        }
    }

    /** 曲情報が空になっていたら取り直す（Spectrum と同じ） */
    private val heal = object : Runnable {
        override fun run() {
            MediaTracker.heal(carContext)
            main.postDelayed(this, HEAL_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@DualCarScreen)
                DualMode.attach(carContext)
                DualMode.addListener(this@DualCarScreen)
                onReady()
            }

            override fun onStart(owner: LifecycleOwner) {
                // メニュー・施設一覧から戻ったとき、設定の変更を反映する
                panel.displayMode = prefs.displayMode
                panel.showCamerasInList = prefs.camerasInList
                autoSwitch = prefs.autoSwitch
                panelInvalid = true
                EngineHost.addListener(snapshotListener)
                EngineHost.latest?.let { snap = it }
            }

            override fun onResume(owner: LifecycleOwner) {
                MediaTracker.heal(carContext)
                invalidate()
            }

            override fun onStop(owner: LifecycleOwner) {
                EngineHost.removeListener(snapshotListener)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                stopDrawing()
                DualMode.removeListener(this@DualCarScreen)
                DualMode.detach()
                try {
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                } catch (e: Exception) {
                    // ホストとの接続が切れている
                }
            }
        })
    }

    // ================================================================== テンプレート（右上のボタン）

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
        if (!DualMode.barVisible) {
            // 高速道路の外（Spectrum 全画面）
            strip.addAction(action("ハイウェイ表示", R.drawable.ic_car_highway) {
                val d = DualMode.highwayDefault()
                DualMode.select(if (d == DualLayout.MUSIC) DualLayout.SPLIT else d)
            })
            strip.addAction(action("メニュー", R.drawable.ic_car_menu) { screenManager.push(CarMenuScreen(carContext)) })
        } else {
            strip.addAction(action("画面切替", R.drawable.ic_car_split) { DualMode.cycle() })
            strip.addAction(action("施設", R.drawable.ic_car_list) { screenManager.push(FacilityListScreen(carContext)) })
            strip.addAction(action("オービス登録", R.drawable.ic_car_camera) {
                EngineHost.registerCameraHere { ok ->
                    CarToast.makeText(carContext, if (ok) "現在地をオービスとして登録しました" else "現在地が未取得です", CarToast.LENGTH_SHORT).show()
                }
            })
            strip.addAction(action("メニュー", R.drawable.ic_car_menu) { screenManager.push(CarMenuScreen(carContext)) })
        }
        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
            .build()
    }

    /** 文字とアイコンの両方を持つボタン（画面の幅に応じて Android Auto がどちらかを表示する） */
    private fun action(title: String, icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setTitle(title)
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener {
                // 車の画面の操作のたびに、スマホ側の常駐（GPS・通信）が動いているか確かめる
                CarRuntime.ensure(carContext)
                onClick()
            }
            .build()

    /** 画面構成が変わった（自動切替・ボタン操作） */
    override fun onDualChanged() {
        autoSwitch = prefs.autoSwitch
        invalidate()
        panelInvalid = true
        updateSpectrumUse()
        requestFrame()
    }

    // ================================================================== 描画面

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        // 画面サイズが変わったときにも呼ばれる。新しい描画面の大きさで描き直す
        val old = surface
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        density = if (dpi > 0) (dpi / 160f).coerceIn(0.75f, 3f) else 1f
        panel.density = density
        bar.density = density
        // 表示範囲は新しい描画面に合わせて改めて通知されるので、古い値は捨てる
        synchronized(visible) { visible.setEmpty() }
        panelInvalid = true
        MediaTracker.start(carContext)
        updateSpectrumUse()
        // 描画スレッドは 1 本だけ（素早い切り替えで多重に起動しないように）
        if (drawThread == null) {
            val t = HandlerThread("car-draw").also { it.start() }
            drawThread = t
            drawHandler = Handler(t.looper).also { it.post(frame) }
        } else {
            requestFrame()
        }
        if (old != null && old !== surface) {
            drawHandler?.post { releaseSurface(old) }
        }
        main.removeCallbacks(heal)
        main.post(heal)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        stopDrawing()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // 描画面からはみ出す範囲が来たら（切り替え途中の古い値など）無視して全体を使う
        synchronized(visible) {
            if (visibleArea.left < 0 || visibleArea.right > width || visibleArea.width() <= 0) visible.setEmpty()
            else visible.set(visibleArea)
        }
        panelInvalid = true
        requestFrame()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onClick(x: Float, y: Float) {
        CarRuntime.ensure(carContext)
        val f = lastFrame ?: return
        val b = f.bar
        if (b != null && b.contains(x, y)) {
            when (bar.hitAt(x, y)) {
                ModeBarRenderer.Target.MUSIC -> DualMode.select(DualLayout.MUSIC)
                ModeBarRenderer.Target.SPLIT, ModeBarRenderer.Target.OPEN_SPLIT -> DualMode.select(DualLayout.SPLIT)
                ModeBarRenderer.Target.HIGHWAY -> DualMode.select(DualLayout.HIGHWAY)
                ModeBarRenderer.Target.PLAY_PAUSE -> NowPlaying.playPause()
                else -> {}
            }
            requestFrame()
            return
        }
        val m = f.music
        if (m != null && m.contains(x, y)) {
            music.onTap(x, y)
            requestFrame()
            return
        }
        val h = f.highway
        if (h != null && h.contains(x, y)) {
            val hit = panel.hitAt(x, y) ?: return
            val fac = hit.facility
            if (fac != null) {
                screenManager.push(FacilityDetailScreen(carContext, fac))
                return
            }
            // ［︿ この先］［現在地へ］［﹀ 手前へ］: さらに先の施設・渋滞を見る / 戻る
            if (panel.pageButton(hit.button)) {
                panelInvalid = true
                requestFrame()
            }
        }
    }

    private fun requestFrame() {
        val h = drawHandler ?: return
        h.removeCallbacks(frame)
        h.post(frame)
    }

    private fun stopDrawing() {
        main.removeCallbacks(heal)
        val h = drawHandler
        val t = drawThread
        drawHandler = null
        drawThread = null
        h?.removeCallbacksAndMessages(null)
        t?.quitSafely()
        try {
            t?.join(500)
        } catch (e: InterruptedException) {
            // そのまま後始末を続ける
        }
        surface?.let { releaseSurface(it) }
        surface = null
        lastFrame = null
        main.removeCallbacks(releaseSpectrum)
        stopSpectrum()
    }

    private fun releaseSurface(s: Surface) {
        try {
            s.release()
        } catch (e: Exception) {
            // 解放済み
        }
    }

    // ================================================================== Spectrum の音声解析

    /** Spectrum が見えている間だけ音声を解析する */
    private fun updateSpectrumUse() {
        val showing = surface != null && DualMode.layout != DualLayout.HIGHWAY
        if (showing) {
            main.removeCallbacks(releaseSpectrum)
            if (!spectrumAcquired) {
                spectrumAcquired = true
                SpectrumEngine.listeners.add(specListener)
                SpectrumEngine.acquire(carContext.applicationContext)
            }
        } else if (spectrumAcquired) {
            main.removeCallbacks(releaseSpectrum)
            main.postDelayed(releaseSpectrum, SPECTRUM_RELEASE_DELAY_MS)
        }
    }

    private fun stopSpectrum() {
        if (!spectrumAcquired) return
        spectrumAcquired = false
        SpectrumEngine.listeners.remove(specListener)
        SpectrumEngine.release()
    }

    // ================================================================== 1 フレーム（描画スレッド）

    /** @return 次のフレームまでの時間 [ms] */
    private fun drawFrame(): Long {
        val s = surface ?: return IDLE_FRAME_MS
        if (!s.isValid || width <= 0 || height <= 0) return IDLE_FRAME_MS
        val w = width.toFloat()
        val h = height.toFloat()
        // 表示範囲は開き方（アプリ一覧/ホーム画面）で上下の余白が変わるため、
        // Spectrum は上下に画面全体を使い、左右だけ Android Auto の表示範囲に合わせる（従来の Spectrum と同じ）。
        // ハイウェイモードは右上のボタンと重ならないよう、表示範囲の上端から描く。
        val v = synchronized(visible) { Rect(visible) }
        val layout = DualMode.layout
        // エンジンの最新の状態（画面の開始・停止の通知に頼らない）
        val latest = EngineHost.latest
        if (latest != null && latest !== snap) {
            snap = latest
            panelInvalid = true
        }
        music.large = layout == DualLayout.SPLIT
        val f = DualGeometry.car(
            w, h,
            left = if (v.isEmpty) 0f else v.left.toFloat(),
            right = if (v.isEmpty) w else v.right.toFloat(),
            highwayTop = if (v.isEmpty) 0f else v.top.toFloat(),
            layout = layout, barVisible = DualMode.barVisible, density = density
        )
        // 右上のボタン（Android Auto が描く）の分、ハイウェイモードの見出しの右側を空ける。
        // 表示範囲の上端が下がっている（ボタンの分を除いてある）ときは不要
        val reserve = if (!v.isEmpty && v.top > 0) 0f else stripWidthEstimate(w)
        if (reserve != panel.headerRightReserve) {
            panel.headerRightReserve = reserve
            panelInvalid = true
        }
        bar.splitHint = if (DualMode.highwayActive && autoSwitch) "高速道路を出ると自動で音楽に戻ります" else null
        val canvas: Canvas = try {
            s.lockHardwareCanvas()
        } catch (e: Exception) {
            try {
                s.lockCanvas(null)
            } catch (e2: Exception) {
                return IDLE_FRAME_MS
            }
        }
        val now = SystemClock.uptimeMillis()
        try {
            canvas.drawColor(Color.BLACK)
            f.music?.let { m -> music.draw(canvas, m.l.toInt(), m.t.toInt(), m.w.toInt(), m.h.toInt()) }
            f.highway?.let { hb -> drawPanel(canvas, hb, now) }
            f.divider?.let { d -> canvas.drawRect(d.l, d.t, d.r, d.b, dividerPaint) }
            f.bar?.let { b -> bar.draw(canvas, b.l, b.t, b.r, b.b, layout, snap) }
            // 画面構成が変わった直後は、黒から短くフェードイン
            val dt = now - DualMode.changedAtMs
            if (dt in 0 until FADE_MS) {
                val x = 1f - dt.toFloat() / FADE_MS
                fadePaint.color = Color.argb((x * x * 170).toInt(), 0, 0, 0)
                canvas.drawRect(0f, 0f, w, h, fadePaint)
            }
        } finally {
            try {
                s.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                // 描画面が破棄された
            }
        }
        lastFrame = f
        val fading = now - DualMode.changedAtMs < FADE_MS
        return when {
            fading -> FRAME_MS
            f.music == null -> IDLE_FRAME_MS
            NowPlaying.isPlaying || music.animating -> FRAME_MS
            else -> PAUSED_FRAME_MS
        }
    }

    /**
     * 右上のボタン列のおおよその幅。Android Auto は幅の広い画面では文字、狭い画面ではアイコンで出す
     * （ボタン 4 つ: 文字なら 1 つ約 104dp、アイコンなら約 58dp）。
     */
    private fun stripWidthEstimate(surfaceWidth: Float): Float {
        val wideScreen = surfaceWidth / density >= 900f
        return (if (wideScreen) 4 * 104f else 4 * 58f) * density
    }

    /** ハイウェイモードの画面。内容が変わったときだけ描き直し、間は記録を再生する（Android 10 以降） */
    private fun drawPanel(c: Canvas, b: Box, now: Long) {
        if (Build.VERSION.SDK_INT >= 29 && c.isHardwareAccelerated) {
            val node = (panelNode as? RenderNode) ?: RenderNode("highway-panel").also { panelNode = it }
            val key = "${b.l},${b.t},${b.r},${b.b},$width,$height"
            if (panelInvalid || key != panelBoxKey || now - panelDrawnAt >= PANEL_REFRESH_MS || !node.hasDisplayList()) {
                panelInvalid = false
                panelDrawnAt = now
                panelBoxKey = key
                node.setPosition(0, 0, width, height)
                val rc = node.beginRecording(width, height)
                try {
                    panel.draw(rc, b.l, b.t, b.r, b.b, snap, System.currentTimeMillis())
                } finally {
                    node.endRecording()
                }
            }
            c.drawRenderNode(node)
        } else {
            panel.draw(c, b.l, b.t, b.r, b.b, snap, System.currentTimeMillis())
        }
    }

    // ================================================================== Spectrum: 再生中のアプリを開く

    /**
     * Android Auto 上で、再生中の音楽アプリ(Spotify など)の画面へ切り替えを試みる（Spectrum と同じ）。
     * Android Auto は他のアプリを開く方法を公式には用意していないため、
     * 受け付けられる可能性のある指定を順に試し、すべて断られたら案内を出す。
     */
    private fun openPlayingAppOnCar() {
        val pkg = NowPlaying.controller?.packageName ?: NowPlaying.sourcePackage
        if (pkg.isEmpty()) {
            toast("再生中のアプリが分かりません")
            return
        }
        val label = appLabel(pkg)
        val comp = findMediaBrowserService(pkg)
        val candidates = mutableListOf<Intent>()
        if (comp != null) {
            // 車載 OS 向けの「音楽アプリを開く」指定
            candidates += Intent("android.car.intent.action.MEDIA_TEMPLATE")
                .putExtra("android.car.intent.extra.MEDIA_COMPONENT", comp.flattenToString())
            // 音楽アプリのサービスを直接指定
            candidates += Intent().setComponent(comp)
        }
        toast("${label}に切り替えます")
        for (i in candidates) {
            try {
                carContext.startCarApp(i)
                return
            } catch (e: Exception) {
                // 次の方法を試す
            }
        }
        toast("Android Auto の制限で切り替えできません。画面下の${label}のアイコンから開いてください")
    }

    private fun findMediaBrowserService(pkg: String): ComponentName? {
        val intent = Intent("android.media.browse.MediaBrowserService").setPackage(pkg)
        val info = try {
            carContext.packageManager.queryIntentServices(intent, 0).firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return null
        return ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = carContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun toast(msg: String) {
        CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()
    }
}
