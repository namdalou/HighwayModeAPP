package com.example.highwaymode.data

import android.content.Context
import android.content.SharedPreferences
import com.example.highwaymode.BuildConfig
import com.example.highwaymode.core.net.OverpassHttp

/** アプリ設定・学習値の保存 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("highway_mode", Context.MODE_PRIVATE)

    /** 運転傾向（制限速度に対する速度比）の学習値 */
    var driverProfile: String?
        get() = sp.getString(KEY_DRIVER, null)
        set(v) = sp.edit().putString(KEY_DRIVER, v).apply()

    /** 表示モード（0=大きく3件, 1=一覧, 2=SA/PAのみ） */
    var displayMode: Int
        get() = sp.getInt(KEY_MODE, 0)
        set(v) = sp.edit().putInt(KEY_MODE, v).apply()

    // ------------------------------------------------------------------ 地図データ

    /** Overpass API のエンドポイント（改行区切り） */
    var endpoints: List<String>
        get() {
            val raw = sp.getString(KEY_ENDPOINTS, null) ?: return DEFAULT_ENDPOINTS
            val list = raw.lines().map { it.trim() }.filter { it.startsWith("https://") || it.startsWith("http://") }
            return list.ifEmpty { DEFAULT_ENDPOINTS }
        }
        set(v) = sp.edit().putString(KEY_ENDPOINTS, v.joinToString("\n")).apply()

    /** タイルキャッシュの有効期間 [日] */
    var cacheDays: Int
        get() = sp.getInt(KEY_CACHE_DAYS, 60)
        set(v) = sp.edit().putInt(KEY_CACHE_DAYS, v).apply()

    /** 地図データ（HWDB）の配布目録 URL。空なら未設定 */
    var manifestUrl: String
        get() = sp.getString(KEY_MANIFEST, null) ?: BuildConfig.HWDB_MANIFEST_URL
        set(v) = sp.edit().putString(KEY_MANIFEST, v.trim()).apply()

    /** 起動時に地図データの更新を確認する（1 日 1 回） */
    var autoCheckUpdate: Boolean
        get() = sp.getBoolean(KEY_AUTO_CHECK, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_CHECK, v).apply()

    var lastUpdateCheckMs: Long
        get() = sp.getLong(KEY_LAST_CHECK, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_CHECK, v).apply()

    /** 目録で見つかった新しい版（通知済みの版） */
    var availableVersion: String?
        get() = sp.getString(KEY_AVAILABLE, null)
        set(v) = sp.edit().putString(KEY_AVAILABLE, v).apply()

    /** 地図データ（HWDB）に無い範囲を Overpass API からその場で取得する */
    var onlineTiles: Boolean
        get() = sp.getBoolean(KEY_ONLINE, true)
        set(v) = sp.edit().putBoolean(KEY_ONLINE, v).apply()

    // ------------------------------------------------------------------ 渋滞情報

    /** 交通情報の取得元: none / here / tomtom / google */
    var trafficProvider: String
        get() = sp.getString(KEY_TRAFFIC_PROVIDER, "none") ?: "none"
        set(v) = sp.edit().putString(KEY_TRAFFIC_PROVIDER, v).apply()

    fun trafficKey(provider: String): String = sp.getString("traffic_key_$provider", "") ?: ""

    fun setTrafficKey(provider: String, key: String) = sp.edit().putString("traffic_key_$provider", key.trim()).apply()

    /** 取得間隔 [分]（0=取得しない, 3, 5, -1=自動: 渋滞があれば 3 分・なければ 5 分） */
    var trafficIntervalMin: Int
        get() = sp.getInt(KEY_TRAFFIC_INTERVAL, 5)
        set(v) = sp.edit().putInt(KEY_TRAFFIC_INTERVAL, v).apply()

    /** モバイル通信量を抑える（渋滞情報は前方 60km まで） */
    var trafficShortRange: Boolean
        get() = sp.getBoolean(KEY_TRAFFIC_SHORT, false)
        set(v) = sp.edit().putBoolean(KEY_TRAFFIC_SHORT, v).apply()

    // ------------------------------------------------------------------ オービス・音声

    /** オービス接近を音声で知らせる */
    var cameraVoice: Boolean
        get() = sp.getBoolean(KEY_CAMERA_VOICE, true)
        set(v) = sp.edit().putBoolean(KEY_CAMERA_VOICE, v).apply()

    /** オービスを一覧（施設の行）にも表示する */
    var camerasInList: Boolean
        get() = sp.getBoolean(KEY_CAMERA_LIST, false)
        set(v) = sp.edit().putBoolean(KEY_CAMERA_LIST, v).apply()

    /** 渋滞・ETC カード未挿入・給油を音声で知らせる */
    var trafficVoice: Boolean
        get() = sp.getBoolean(KEY_TRAFFIC_VOICE, true)
        set(v) = sp.edit().putBoolean(KEY_TRAFFIC_VOICE, v).apply()

    companion object {
        private const val KEY_DRIVER = "driver_profile"
        private const val KEY_MODE = "display_mode"
        private const val KEY_ENDPOINTS = "overpass_endpoints"
        private const val KEY_CACHE_DAYS = "cache_days"
        private const val KEY_MANIFEST = "hwdb_manifest_url"
        private const val KEY_AUTO_CHECK = "hwdb_auto_check"
        private const val KEY_LAST_CHECK = "hwdb_last_check"
        private const val KEY_AVAILABLE = "hwdb_available"
        private const val KEY_ONLINE = "online_tiles"
        private const val KEY_TRAFFIC_PROVIDER = "traffic_provider"
        private const val KEY_TRAFFIC_INTERVAL = "traffic_interval"
        private const val KEY_TRAFFIC_SHORT = "traffic_short"
        private const val KEY_CAMERA_VOICE = "camera_voice"
        private const val KEY_CAMERA_LIST = "camera_list"
        private const val KEY_TRAFFIC_VOICE = "traffic_voice"

        val DEFAULT_ENDPOINTS: List<String> = OverpassHttp.DEFAULT_ENDPOINTS
    }
}
