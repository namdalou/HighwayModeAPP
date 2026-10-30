package com.example.highwaymode.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.highwaymode.TrackingService
import com.example.highwaymode.engine.EngineHost
import com.example.spectrum.SpectrumEngine

/**
 * Android Auto の 1 回の接続。エンジン（測位・地図・渋滞情報）を起動し、
 * 車両の情報（車速・ETC カード・燃料・ジャイロ）を流し込む。画面は Spectrum とハイウェイモードの共用画面。
 */
class HighwaySession : Session() {

    private var vehicle: CarVehicleInput? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val ctx = carContext
        // エンジン（測位・地図・渋滞情報）を起動。常駐サービスは画面を切り替えても測位を続けるため
        EngineHost.start(ctx)
        try {
            TrackingService.start(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "foreground service not started: ${e.message}")
        }
        EngineHost.setCarConnected(true)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                vehicle?.stop()
                vehicle = null
                EngineHost.setCarConnected(false)
                // 常駐サービスが動いていなければ（起動できなかった・スマホ側で停止済み）エンジンも止める
                if (!TrackingService.running) EngineHost.stop()
            }
        })
        val main = DualCarScreen(ctx) { startVehicleInput() }
        val missing = PERMISSIONS.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        return if (missing.contains(Manifest.permission.ACCESS_FINE_LOCATION) || missing.isNotEmpty() && !askedOnce) {
            askedOnce = true
            ctx.getCarService(androidx.car.app.ScreenManager::class.java).push(main)
            PermissionScreen(ctx, missing) {
                EngineHost.restartLocation()
                SpectrumEngine.retry(ctx.applicationContext)
                startVehicleInput()
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
        private const val TAG = "HighwaySession"
        private var askedOnce = false

        val PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            CarVehicleInput.PERMISSION_CAR_SPEED,
            CarVehicleInput.PERMISSION_CAR_FUEL
        )
    }
}
