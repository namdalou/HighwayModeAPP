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

    /**
     * 渋滞・ETC カード未挿入・給油を音声で知らせる（v2.3 まで。v2.4 から種類ごとの設定の初期値にだけ使う）
     */
    private val trafficVoiceLegacy: Boolean
        get() = sp.getBoolean(KEY_TRAFFIC_VOICE, true)

    /** 前方の渋滞を音声で知らせる */
    var voiceJam: Boolean
        get() = sp.getBoolean(KEY_VOICE_JAM, trafficVoiceLegacy)
        set(v) = sp.edit().putBoolean(KEY_VOICE_JAM, v).apply()

    /** ETC カードの未挿入・無効を音声で知らせる */
    var voiceEtc: Boolean
        get() = sp.getBoolean(KEY_VOICE_ETC, trafficVoiceLegacy)
        set(v) = sp.edit().putBoolean(KEY_VOICE_ETC, v).apply()

    /** 給油・充電の目安を音声で知らせる */
    var voiceFuel: Boolean
        get() = sp.getBoolean(KEY_VOICE_FUEL, trafficVoiceLegacy)
        set(v) = sp.edit().putBoolean(KEY_VOICE_FUEL, v).apply()

    /** 音声案内の音量（0=標準, 1=やや小さく, 2=小さく）。音楽の音量に対する大きさ */
    var voiceVolume: Int
        get() = sp.getInt(KEY_VOICE_VOLUME, 0).coerceIn(0, VOICE_VOLUMES.size - 1)
        set(v) = sp.edit().putInt(KEY_VOICE_VOLUME, v).apply()

    /** 音声案内の話す速さ（0=ゆっくり, 1=標準, 2=速め） */
    var voiceRate: Int
        get() = sp.getInt(KEY_VOICE_RATE, 1).coerceIn(0, VOICE_RATES.size - 1)
        set(v) = sp.edit().putInt(KEY_VOICE_RATE, v).apply()

    // ------------------------------------------------------------------ 表示・画面（v2.4）

    /** 高速道路を出てから音楽の画面に戻るまで [秒] */
    var exitDelaySec: Int
        get() = sp.getInt(KEY_EXIT_DELAY, EXIT_DELAYS[0]).let { v -> if (v in EXIT_DELAYS) v else EXIT_DELAYS[0] }
        set(v) = sp.edit().putInt(KEY_EXIT_DELAY, v).apply()

    /** 到着予定の表示（0=到着時刻「17:50」, 1=残り時間「あと12分」） */
    var etaStyle: Int
        get() = sp.getInt(KEY_ETA_STYLE, 0).coerceIn(0, 1)
        set(v) = sp.edit().putInt(KEY_ETA_STYLE, v).apply()

    /** スマホの画面の向き（0=自動, 1=縦に固定, 2=横に固定） */
    var orientation: Int
        get() = sp.getInt(KEY_ORIENTATION, 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt(KEY_ORIENTATION, v).apply()

    /** スマホの画面を消さない（0=いつも, 1=高速道路を走行中だけ, 2=端末の設定どおり） */
    var keepScreenOn: Int
        get() = sp.getInt(KEY_KEEP_SCREEN, 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt(KEY_KEEP_SCREEN, v).apply()

    /** Google マップのナビに合わせる（ナビの通知から、JCT の乗り換え先・降りる IC を読み取る） */
    var navSync: Boolean
        get() = sp.getBoolean(KEY_NAV_SYNC, true)
        set(v) = sp.edit().putBoolean(KEY_NAV_SYNC, v).apply()

    /** 音楽の画面に Google マップの次の案内（矢印・距離・案内の文）を出す（一般道でも。v2.6） */
    var navBanner: Boolean
        get() = sp.getBoolean(KEY_NAV_BANNER, true)
        set(v) = sp.edit().putBoolean(KEY_NAV_BANNER, v).apply()

    /**
     * 車の画面: 右上のボタンが出たときに一覧を下げる量を足す（0 = 自動、1 = 少し多め +16dp、2 = 多め +32dp）。
     * ボタンの下端を車（Android Auto の版）が正しく知らせてこないときに使う（v2.6.1）
     */
    var stripExtra: Int
        get() = sp.getInt(KEY_STRIP_EXTRA, 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt(KEY_STRIP_EXTRA, v.coerceIn(0, 2)).apply()

    /** 電池の節約（高速道路から離れている・停車中は GPS の間隔をあける）。オフならいつも毎秒測位 */
    var batterySaver: Boolean
        get() = sp.getBoolean(KEY_BATTERY_SAVER, true)
        set(v) = sp.edit().putBoolean(KEY_BATTERY_SAVER, v).apply()

    /** 高速道路の対象エリアに入ったら自動で 2 画面にし、出たら Spectrum（音楽）に戻す */
    var autoSwitch: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_SWITCH, v).apply()

    /** 対象エリアに入ったときの画面（"SPLIT" / "HIGHWAY" / "MUSIC"） */
    var highwayLayout: String
        get() = sp.getString(KEY_HIGHWAY_LAYOUT, "SPLIT") ?: "SPLIT"
        set(v) = sp.edit().putString(KEY_HIGHWAY_LAYOUT, v).apply()

    /** 「Android Auto に表示されない場合」の案内を表示済みか（インストール元ごと） */
    var autoHintShownFor: String?
        get() = sp.getString(KEY_AUTO_HINT, null)
        set(v) = sp.edit().putString(KEY_AUTO_HINT, v).apply()

    /** Android Auto で使ったことがある（スマホを開かずに使うための案内に使う） */
    var carUsedOnce: Boolean
        get() = sp.getBoolean(KEY_CAR_USED, false)
        set(v) = sp.edit().putBoolean(KEY_CAR_USED, v).apply()

    /** 「Android Auto だけで使うための設定」の案内を表示済み */
    var backgroundHintShown: Boolean
        get() = sp.getBoolean(KEY_BG_HINT, false)
        set(v) = sp.edit().putBoolean(KEY_BG_HINT, v).apply()

    companion object {
        private const val KEY_CAR_USED = "car_used_once"
        private const val KEY_BG_HINT = "background_hint_shown"
        private const val KEY_AUTO_HINT = "auto_hint_shown_for"
        private const val KEY_AUTO_SWITCH = "dual_auto_switch"
        private const val KEY_HIGHWAY_LAYOUT = "dual_highway_layout"
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
        private const val KEY_VOICE_JAM = "voice_jam"
        private const val KEY_VOICE_ETC = "voice_etc"
        private const val KEY_VOICE_FUEL = "voice_fuel"
        private const val KEY_VOICE_VOLUME = "voice_volume"
        private const val KEY_VOICE_RATE = "voice_rate"
        private const val KEY_EXIT_DELAY = "dual_exit_delay_sec"
        private const val KEY_ETA_STYLE = "eta_style"
        private const val KEY_ORIENTATION = "screen_orientation"
        private const val KEY_KEEP_SCREEN = "keep_screen_on"
        private const val KEY_BATTERY_SAVER = "battery_saver"
        private const val KEY_NAV_SYNC = "nav_sync_google_maps"
        private const val KEY_NAV_BANNER = "nav_banner_music"
        private const val KEY_STRIP_EXTRA = "car_strip_extra"

        val DEFAULT_ENDPOINTS: List<String> = OverpassHttp.DEFAULT_ENDPOINTS

        /** 高速道路を出てから音楽に戻るまでの選択肢 [秒]（先頭が標準） */
        val EXIT_DELAYS = intArrayOf(15, 30, 60)

        /** 音声案内の音量（[voiceVolume] の順。TextToSpeech の音量 0〜1） */
        val VOICE_VOLUMES = floatArrayOf(1.0f, 0.7f, 0.45f)

        /** 音声案内の話す速さ（[voiceRate] の順。1 = 標準） */
        val VOICE_RATES = floatArrayOf(0.85f, 1.0f, 1.2f)
    }
}
