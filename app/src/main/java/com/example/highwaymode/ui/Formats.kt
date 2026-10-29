package com.example.highwaymode.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object Formats {
    private val clockFmt = ThreadLocal.withInitial { SimpleDateFormat("H:mm", Locale.JAPAN) }

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
}
