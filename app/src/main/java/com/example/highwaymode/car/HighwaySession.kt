package com.example.highwaymode.car

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.example.highwaymode.TrackingService
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.engine.EngineHost
import com.example.spectrum.MediaListenerService
import com.example.spectrum.MediaTracker
import com.example.spectrum.NowPlaying
import com.example.spectrum.SpectrumEngine

/**
 * Android Auto の 1 回の接続。エンジン（測位・地図・渋滞情報）を起動し、
 * 車両の情報（車速・ETC カード・燃料・ジャイロ）を流し込む。画面は Spectrum とハイウェイモードの共用画面。
 *
 * スマホでアプリを開いていなくても動くよう、車の画面が表示されている間（STARTED）に常駐サービスを起動し、
 * 起動できない・位置情報の種類にできないときは、少しずつ間隔をあけて繰り返し試みる。
 */
class HighwaySession : Session() {

    private var vehicle: CarVehicleInput? = null
    private val main = Handler(Looper.getMainLooper())
    private var attempts = 0

    /** 常駐サービスの起動・位置情報の種類への切り替えを、成功するまで繰り返す */
    private val ensure = object : Runnable {
        override fun run() {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            if (CarRuntime.ensure(carContext)) {
                attempts = 0
                return
            }
            val delay = RETRY_MS.getOrElse(attempts) { RETRY_MS.last() }
            attempts++
            main.postDelayed(this, delay)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                attempts = 0
                main.removeCallbacks(ensure)
                main.post(ensure)
            }

            override fun onResume(owner: LifecycleOwner) {
                // 画面が前面に来たとき（他のアプリから戻った）にも試す
                CarRuntime.ensure(carContext)
            }

            override fun onStop(owner: LifecycleOwner) {
                main.removeCallbacks(ensure)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                main.removeCallbacks(ensure)
                vehicle?.stop()
                vehicle = null
                EngineHost.setCarConnected(false)
                // 常駐サービスが動いていなければ（起動できなかった・スマホ側で停止済み）エンジンも止める
                if (!TrackingService.running) EngineHost.stop()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val ctx = carContext
        Prefs(ctx).carUsedOnce = true
        // エンジン（測位・地図・渋滞情報）を起動。常駐サービスは画面を切り替えても・スマホで開いていなくても測位を続けるため
        EngineHost.start(ctx)
        EngineHost.setCarConnected(true)
        // 止まっていれば起動（スマホで開いて既に動いているなら、そのまま使う）
        CarRuntime.ensure(ctx)
        CarRuntime.reconnectMedia(ctx)
        val main = DualCarScreen(ctx) { startVehicleInput() }
        val missing = PERMISSIONS.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        return if (missing.contains(Manifest.permission.ACCESS_FINE_LOCATION) || missing.isNotEmpty() && !askedOnce) {
            askedOnce = true
            ctx.getCarService(androidx.car.app.ScreenManager::class.java).push(main)
            PermissionScreen(ctx, missing) {
                EngineHost.restartLocation()
                SpectrumEngine.retry(ctx.applicationContext)
                startVehicleInput()
                CarRuntime.ensure(ctx)
            }
        } else {
            startVehicleInput()
            main
        }
    }

    private fun startVehicleInput() {
        if (vehicle != null) {
            vehicle?.restart()
            return
        }
        vehicle = CarVehicleInput(carContext).also { it.start() }
    }

    companion object {
        private var askedOnce = false

        /** 再試行の間隔 [ms]（以後は最後の値の間隔で続ける） */
        private val RETRY_MS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000, 120_000)

        val PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            CarVehicleInput.PERMISSION_CAR_SPEED,
            CarVehicleInput.PERMISSION_CAR_FUEL
        )
    }
}

/** 車の画面から使う、スマホ側の常駐・曲情報の準備 */
object CarRuntime {

    /**
     * 常駐サービスを起動し、位置情報の種類で動くよう試みる（車の画面の表示・操作のたびに呼んでよい）。
     * @return 位置情報の種類で動作中
     */
    fun ensure(ctx: Context): Boolean = TrackingService.ensure(ctx)

    /** 曲情報: 通知へのアクセスが許可済みなのに未接続なら、つなぎ直す。メディアの追跡も始める */
    fun reconnectMedia(ctx: Context) {
        NowPlaying.init(ctx)
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true
        if (enabled && !NowPlaying.listenerConnected) {
            try {
                NotificationListenerService.requestRebind(ComponentName(ctx, MediaListenerService::class.java))
            } catch (e: Exception) {
                // 次の機会に再試行
            }
        }
        MediaTracker.start(ctx)
    }
}
