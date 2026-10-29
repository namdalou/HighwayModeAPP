package com.example.highwaymode.car

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import com.example.highwaymode.core.vehicle.TollCardState
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.engine.LocationInput
import java.util.concurrent.Executor

/**
 * Android Auto で車から得られる情報をエンジンへ渡す（Car App Library の CarHardwareManager）。
 *  - 車速（生の車速・メーター表示速度）: 速度フィルタ・推測航法・停止判定に使う
 *  - ETC カードの状態: 未挿入・無効なら画面と音声で知らせる
 *  - 燃料・電池残量と航続可能距離: 次の GS / 充電設備のある SA/PA と照らして給油を案内
 *  - 車両のジャイロ・GPS（対応車のみ）
 * 車種・ヘッドユニットによっては一部の情報が提供されない（その場合は何もしない）。
 */
class CarVehicleInput(private val carContext: CarContext) {

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executor { r -> main.post(r) }
    private var info: CarInfo? = null
    private var sensors: CarSensors? = null
    private val registered = HashSet<String>()

    private val speedListener = OnCarDataAvailableListener<Speed> { d ->
        val raw = d.rawSpeedMetersPerSecond.okValue()?.toDouble()
        val disp = d.displaySpeedMetersPerSecond.okValue()?.toDouble()
        if (raw != null || disp != null) EngineHost.onVehicleSpeed(raw, disp)
    }

    private val tollListener = OnCarDataAvailableListener<TollCard> { d ->
        val st = when (d.cardState.okValue()) {
            TollCard.TOLLCARD_STATE_VALID -> TollCardState.VALID
            TollCard.TOLLCARD_STATE_INVALID -> TollCardState.INVALID
            TollCard.TOLLCARD_STATE_NOT_INSERTED -> TollCardState.NOT_INSERTED
            else -> TollCardState.UNKNOWN
        }
        EngineHost.onVehicleTollCard(st)
    }

    private val energyListener = OnCarDataAvailableListener<EnergyLevel> { d ->
        EngineHost.onVehicleEnergy(
            d.rangeRemainingMeters.okValue()?.toDouble(),
            d.fuelPercent.okValue()?.toDouble(),
            d.batteryPercent.okValue()?.toDouble(),
            d.energyIsLow.okValue()
        )
    }

    private val profileListener = OnCarDataAvailableListener<EnergyProfile> { d ->
        val types = d.fuelTypes.okValue()
        if (types != null && types.isNotEmpty()) {
            EngineHost.onVehicleModel(null, types.all { it == EnergyProfile.FUEL_TYPE_ELECTRIC })
        }
    }

    private val modelListener = OnCarDataAvailableListener<Model> { d ->
        val name = listOfNotNull(d.manufacturer.okValue(), d.name.okValue()).joinToString(" ").ifBlank { null }
        EngineHost.onVehicleModel(name, null)
    }

    private val gyroListener = OnCarDataAvailableListener<Gyroscope> { d ->
        val v = d.rotations.okValue() ?: return@OnCarDataAvailableListener
        if (v.size >= 3) EngineHost.onVehicleGyro(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
    }

    private val locationListener = OnCarDataAvailableListener<CarHardwareLocation> { d ->
        val loc = d.location.okValue() ?: return@OnCarDataAvailableListener
        EngineHost.onVehicleLocation(LocationInput.toFix(loc))
    }

    private fun <T> CarValue<T>.okValue(): T? = if (status == CarValue.STATUS_SUCCESS) value else null

    private fun has(p: String) = carContext.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private inline fun attempt(name: String, block: () -> Unit) {
        if (name in registered) return
        try {
            block()
            registered.add(name)
        } catch (e: Exception) {
            // 非対応のホスト・権限なし・API レベル不足
            Log.i(TAG, "$name unavailable: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    fun start() {
        if (carContext.carAppApiLevel < 3) {
            Log.i(TAG, "Car API level ${carContext.carAppApiLevel}: vehicle data not available")
            return
        }
        val hw = try {
            carContext.getCarService(CarHardwareManager::class.java)
        } catch (e: Exception) {
            Log.i(TAG, "CarHardwareManager unavailable: ${e.message}")
            return
        }
        val ci = try { hw.carInfo } catch (e: Exception) { null }
        val cs = try { hw.carSensors } catch (e: Exception) { null }
        info = ci
        sensors = cs
        if (ci != null) {
            if (has(PERMISSION_CAR_SPEED)) attempt("speed") { ci.addSpeedListener(exec, speedListener) }
            attempt("toll") { ci.addTollListener(exec, tollListener) }
            if (has(PERMISSION_CAR_FUEL)) {
                attempt("energy") { ci.addEnergyLevelListener(exec, energyListener) }
                attempt("profile") { ci.fetchEnergyProfile(exec, profileListener) }
            }
            attempt("model") { ci.fetchModel(exec, modelListener) }
        }
        if (cs != null) {
            attempt("gyro") { cs.addGyroscopeListener(CarSensors.UPDATE_RATE_FASTEST, exec, gyroListener) }
            if (has(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                attempt("location") { cs.addCarHardwareLocationListener(CarSensors.UPDATE_RATE_FASTEST, exec, locationListener) }
            }
        }
    }

    /** 権限が後から許可されたとき */
    fun restart() = start()

    fun stop() {
        val ci = info
        val cs = sensors
        try {
            if (ci != null) {
                if ("speed" in registered) ci.removeSpeedListener(speedListener)
                if ("toll" in registered) ci.removeTollListener(tollListener)
                if ("energy" in registered) ci.removeEnergyLevelListener(energyListener)
            }
            if (cs != null) {
                if ("gyro" in registered) cs.removeGyroscopeListener(gyroListener)
                if ("location" in registered) cs.removeCarHardwareLocationListener(locationListener)
            }
        } catch (e: Exception) {
            Log.i(TAG, "stop: ${e.message}")
        }
        registered.clear()
    }

    companion object {
        private const val TAG = "CarVehicleInput"
        const val PERMISSION_CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED"
        const val PERMISSION_CAR_FUEL = "com.google.android.gms.permission.CAR_FUEL"
    }
}
