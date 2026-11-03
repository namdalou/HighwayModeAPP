package com.example.highwaymode.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

object Formats {
    private val clockFmt = ThreadLocal.withInitial { SimpleDateFormat("H:mm", Locale.JAPAN) }

    /**
     * 到着予定を残り時間で出す（設定「到着予定の表示」。false = 到着時刻「17:50」, true = 残り時間「あと12分」）。
     * 描画のたびに設定を読まないよう、設定を変えたとき・起動時に [com.example.highwaymode.data.AppSettings] が写す
     */
    @Volatile
    var etaRemaining = false

    /** 「3.9km」「18km」「800m」 */
    fun distance(m: Double): String {
        val p = distanceParts(m)
        return p.first + p.second
    }

    /** 数値部と単位部（大きく表示するため分けて返す） */
    fun distanceParts(m: Double): Pair<String, String> = when {
        m < 1000 -> (((m / 10.0).roundToInt() * 10).coerceAtLeast(0).toString()) to "m"
        m < 10_000 -> String.format(Locale.US, "%.1f", m / 1000.0) to "km"
        else -> (m / 1000.0).roundToInt().toString() to "km"
    }

    fun clock(epochMs: Long): String = clockFmt.get()!!.format(Date(epochMs))

    /** 「約 12 分」「1 時間 05 分」 */
    fun duration(sec: Double): String {
        val min = (sec / 60.0).roundToInt()
        return if (min < 60) "約${min}分" else String.format(Locale.US, "%d時間%02d分", min / 60, min % 60)
    }

    /** 残り時間「あと12分」「あと1時間45分」（1 分未満も「あと1分」） */
    fun remaining(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val min = ceil((epochMs - nowMs) / 60_000.0).toInt().coerceAtLeast(1)
        return if (min < 100) "あと${min}分" else String.format(Locale.US, "あと%d時間%02d分", min / 60, min % 60)
    }

    /** 施設の行の到着予定（設定に合わせて「17:50」または「あと12分」） */
    fun eta(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        if (etaRemaining) remaining(epochMs, nowMs) else clock(epochMs)

    /** 文中の到着予定（「17:50着」または「あと12分」） */
    fun etaLabel(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        if (etaRemaining) remaining(epochMs, nowMs) else clock(epochMs) + "着"
}
