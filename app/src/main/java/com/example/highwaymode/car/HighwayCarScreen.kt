package com.example.highwaymode.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.ui.PanelRenderer

/**
 * 車載画面のメイン。ナビ用テンプレートの地図面（Surface）に、スマホと同じハイウェイモードを描く。
 * 右上のボタン: 施設一覧（タップで詳細）・表示切替・再探索・オービス登録。
 */
class HighwayCarScreen(carContext: CarContext, private val onReady: () -> Unit) : Screen(carContext), SurfaceCallback {

    private val prefs = Prefs(carContext)
    private val renderer = PanelRenderer(1f).apply {
        showButtons = false
        displayMode = prefs.displayMode
    }
    private val main = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visible: Rect? = null
    private var stable: Rect? = null
    private var snap: HighwaySnapshot? = EngineHost.latest

    private val listener = EngineHost.SnapshotListener { s ->
        snap = s
        drawFrame()
    }

    /** 時計の分表示などのため、スナップショットが来なくても 5 秒ごとに描き直す */
    private val redraw = object : Runnable {
        override fun run() {
            drawFrame()
            main.postDelayed(this, 5000L)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@HighwayCarScreen)
                onReady()
            }

            override fun onStart(owner: LifecycleOwner) {
                renderer.displayMode = prefs.displayMode
                renderer.showCamerasInList = prefs.camerasInList
                EngineHost.addListener(listener)
                main.post(redraw)
            }

            override fun onStop(owner: LifecycleOwner) {
                EngineHost.removeListener(listener)
                main.removeCallbacks(redraw)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                try {
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                } catch (e: Exception) {
                    // ホストとの接続が切れている
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val modeLabel = when (renderer.displayMode) {
            1 -> "一覧"
            2 -> "SA/PA"
            else -> "大きく"
        }
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("施設").setOnClickListener {
                    screenManager.push(FacilityListScreen(carContext))
                }.build()
            )
            .addAction(
                Action.Builder().setTitle("表示:$modeLabel").setOnClickListener {
                    val next = (renderer.displayMode + 1) % 3
                    renderer.displayMode = next
                    prefs.displayMode = next
                    invalidate()
                    drawFrame()
                }.build()
            )
            .addAction(
                Action.Builder().setTitle("再探索").setOnClickListener {
                    EngineHost.post { it.forceRematch() }
                    CarToast.makeText(carContext, "現在地の道路を再探索します", CarToast.LENGTH_SHORT).show()
                }.build()
            )
            .addAction(
                Action.Builder().setTitle("オービス登録").setOnClickListener {
                    EngineHost.registerCameraHere { ok ->
                        CarToast.makeText(carContext, if (ok) "現在地をオービスとして登録しました" else "現在地が未取得です", CarToast.LENGTH_SHORT).show()
                    }
                }.build()
            )
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setBackgroundColor(CarColor.createCustom(0xFF0A0C10.toInt(), 0xFF0A0C10.toInt()))
            .build()
    }

    // ------------------------------------------------------------------ Surface

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        renderer.density = if (dpi > 0) (dpi / 160f).coerceIn(0.75f, 3f) else 1f
        drawFrame()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        drawFrame()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        stable = Rect(stableArea)
        drawFrame()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    override fun onClick(x: Float, y: Float) {
        val hit = renderer.hitAt(x, y) ?: return
        val f = hit.facility ?: return
        screenManager.push(FacilityDetailScreen(carContext, f))
    }

    private fun drawFrame() {
        val s = surface ?: return
        if (!s.isValid || width <= 0 || height <= 0) return
        val area = visible?.takeIf { !it.isEmpty } ?: stable?.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)
        val c = try {
            s.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.w(TAG, "lockCanvas failed: ${e.message}")
            return
        }
        try {
            renderer.draw(c, area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(), snap, System.currentTimeMillis())
        } finally {
            try {
                s.unlockCanvasAndPost(c)
            } catch (e: Exception) {
                Log.w(TAG, "unlockCanvas failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "HighwayCarScreen"
    }
}
