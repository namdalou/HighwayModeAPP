package com.example.highwaymode.engine

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.example.highwaymode.TrackingService
import com.example.spectrum.NowPlaying

/**
 * 「スマホでアプリを開かずに Android Auto だけで使う」ための動作状態の確認（車の画面・スマホの両方で表示）。
 *
 * Android は、画面に出ていないアプリの GPS・通信・常駐を制限する。車の画面だけで使っていると、
 * スマホのアプリは「画面に出ていない」扱いになり、IC の判定（GPS）・渋滞情報（通信）・ジャケット画像の取得が
 * 止まることがある。電池の最適化を「制限なし（最適化しない）」にすると、裏からでも常駐サービスを起動できる。
 */
object RunDiagnostics {

    enum class Fix(val label: String) {
        BATTERY("電池の最適化を解除"),
        APP_SETTINGS("アプリの設定を開く"),
        DATA_SAVER("データセーバーの例外にする"),
        NOTIFICATION_ACCESS("通知へのアクセスを許可")
    }

    class Item(val title: String, val value: String, val ok: Boolean, val fix: Fix? = null)

    fun collect(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val now = SystemClock.elapsedRealtimeNanos()

        // ---- 常駐サービス
        out += when {
            TrackingService.running && TrackingService.mode == TrackingService.Mode.LOCATION ->
                Item("常駐サービス", "動作中（位置情報を常に受信）", true)
            TrackingService.running && TrackingService.mode == TrackingService.Mode.DATA_ONLY ->
                Item("常駐サービス", "動作中（位置情報は制限中: ${TrackingService.lastError ?: "理由不明"}）", false, Fix.BATTERY)
            else -> Item(
                "常駐サービス",
                "停止中" + ((TrackingService.lastStartError ?: TrackingService.lastError)?.let { "（$it）" } ?: ""),
                false, Fix.BATTERY
            )
        }

        // ---- GPS
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val phoneAge = EngineHost.lastPhoneFixNanos.takeIf { it > 0 }?.let { (now - it) / 1e9 }
        val carAge = EngineHost.lastVehicleFixNanos.takeIf { it > 0 }?.let { (now - it) / 1e9 }
        // 電池の節約で測位の間隔をあけている間は、その間隔の分だけ待つ
        val power = EngineHost.powerLevel
        val okAge = 10.0 + power.gpsIntervalMs / 1000.0
        out += when {
            !fine && !coarse -> Item("位置情報", "権限がありません（スマホで許可してください）", false, Fix.APP_SETTINGS)
            !fine -> Item("位置情報", "「おおよその位置」だけ許可されています（「正確な位置」を許可）", false, Fix.APP_SETTINGS)
            phoneAge != null && phoneAge < okAge -> Item("位置情報", "スマホの GPS を受信中", true)
            carAge != null && carAge < okAge -> Item("位置情報", "車の GPS を受信中（スマホの GPS は停止中）", true)
            else -> Item(
                "位置情報",
                "GPS を受信していません" + (phoneAge?.let { "（最後の受信 ${ageText(it)}前）" } ?: ""),
                false, Fix.BATTERY
            )
        }

        // ---- 電池の最適化・バックグラウンドの制限
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = try {
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Exception) {
            false
        }
        val restricted = if (Build.VERSION.SDK_INT >= 28) {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        } else false
        out += when {
            restricted -> Item("電池", "「制限」になっています（車の画面だけでは動きません）。「制限なし」にしてください", false, Fix.APP_SETTINGS)
            ignoring -> Item("電池", "最適化しない（制限なし）", true)
            else -> Item("電池", "最適化されています。「制限なし」にすると、スマホで開かなくても動きます", false, Fix.BATTERY)
        }
        if (pm.isPowerSaveMode) {
            out += Item("省電力モード", "オン（GPS・通信が止められることがあります）", false)
        }
        if (EngineHost.running) {
            out += Item("電池の節約", power.label, true)
        }

        // ---- 通信
        val net = networkProblem(ctx)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val saver = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        out += when {
            net != null -> Item("通信", net, false, if (saver) Fix.DATA_SAVER else Fix.BATTERY)
            saver -> Item("通信", "データセーバーで裏での通信が制限されています", false, Fix.DATA_SAVER)
            else -> Item("通信", "利用できます", true)
        }

        // ---- 渋滞情報
        EngineHost.latest?.traffic?.let { tr ->
            val msg = tr.message
            val fetched = tr.fetchedWallMs
            out += when {
                msg != null -> Item("渋滞情報", msg, false)
                fetched != null -> Item("渋滞情報", "${tr.provider ?: ""} ${ageText((System.currentTimeMillis() - fetched) / 1000.0)}前に取得", true)
                else -> Item("渋滞情報", "未取得", false)
            }
        }

        // ---- 曲情報（ジャケット）
        val listenerOn = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true
        out += when {
            !listenerOn -> Item("曲情報", "通知へのアクセスが未許可（曲名・ジャケットを取得できません）", false, Fix.NOTIFICATION_ACCESS)
            NowPlaying.hasInfo && NowPlaying.art == null -> Item("曲情報", "曲名は取得中・ジャケット画像なし（再生中のアプリが画像を出していません）", true)
            NowPlaying.hasInfo -> Item("曲情報", "取得中", true)
            else -> Item("曲情報", if (NowPlaying.listenerConnected) "再生中の曲がありません" else "通知へのアクセスに再接続中", true)
        }
        return out
    }

    /** いちばん重要な問題（車の画面の短い案内用）。無ければ null */
    fun headline(ctx: Context): String? {
        val bad = collect(ctx).filter { !it.ok }
        if (bad.isEmpty()) return null
        return bad.first().let { "${it.title}: ${it.value}" }
    }

    /** 文字にまとめる（スマホのダイアログ・共有用） */
    fun asText(items: List<Item>): String = buildString {
        for (i in items) {
            append(if (i.ok) "○ " else "× ")
            append(i.title).append("：").append(i.value).append('\n')
        }
    }.trimEnd()

    /**
     * このアプリから通信できない理由（できるなら null）。
     * ネットワークはあるのに、このアプリには「無い」と見える場合は、裏での通信が止められている。
     */
    @Suppress("DEPRECATION")
    fun networkProblem(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        if (n != null) {
            val caps = cm.getNetworkCapabilities(n)
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                val blocked = try {
                    cm.activeNetworkInfo?.detailedState == NetworkInfo.DetailedState.BLOCKED
                } catch (e: Exception) {
                    false
                }
                return if (blocked) BLOCKED else null
            }
        }
        val any = try {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
        } catch (e: Exception) {
            false
        }
        return if (any) BLOCKED else "通信できません（圏外・機内モード）"
    }

    const val BLOCKED = "スマホの省電力機能で、裏での通信が止められています"

    /** 設定画面を開く Intent（スマホ） */
    fun intentFor(ctx: Context, fix: Fix): List<Intent> {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        return when (fix) {
            // 一覧から「最適化しない」を選ぶ画面（特別な権限が不要）。開けない端末はアプリの設定 →「電池」へ
            Fix.BATTERY -> listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details)
            Fix.APP_SETTINGS -> listOf(details)
            Fix.DATA_SAVER -> listOf(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, pkg), details)
            Fix.NOTIFICATION_ACCESS -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), details)
        }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** 案内文（スマホのダイアログ） */
    const val GUIDE = "Android Auto の画面だけで使う（スマホでアプリを開かない）場合は、スマホの\n" +
        "  設定 → アプリ → Spectrum ハイウェイ → 電池（バッテリー）→「制限なし」\n" +
        "（または「電池の最適化」で「最適化しない」）にしてください。\n" +
        "Android の省電力機能が、画面に出ていないアプリの GPS・通信を止めるためです。"

    private fun ageText(sec: Double): String = when {
        sec < 60 -> "${sec.toInt()}秒"
        sec < 3600 -> "${(sec / 60).toInt()}分"
        else -> "${(sec / 3600).toInt()}時間"
    }
}
