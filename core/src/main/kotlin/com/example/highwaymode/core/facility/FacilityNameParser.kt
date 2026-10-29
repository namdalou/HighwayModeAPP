package com.example.highwaymode.core.facility

import com.example.highwaymode.core.graph.NameNorm

/**
 * OSM の施設名（「辰野PA」「談合坂サービスエリア（上り線）」「諏訪湖スマートIC」等）を
 * 基本名・種別・上下線注記・ETC 専用フラグに分解する。
 */
object FacilityNameParser {

    class Parsed(
        val base: String,
        val type: FacilityType?,
        val etcOnly: Boolean,
        val direction: String?,
        val highwayOasis: Boolean
    )

    private val SUFFIXES: List<Pair<String, FacilityType>> = listOf(
        "スマートインターチェンジ" to FacilityType.SIC,
        "スマートインター" to FacilityType.SIC,
        "スマートIC" to FacilityType.SIC,
        "SIC" to FacilityType.SIC,
        "インターチェンジ" to FacilityType.IC,
        "インター" to FacilityType.IC,
        "IC" to FacilityType.IC,
        "ジャンクション" to FacilityType.JCT,
        "JCT" to FacilityType.JCT,
        "サービスエリア" to FacilityType.SA,
        "SA" to FacilityType.SA,
        "パーキングエリア" to FacilityType.PA,
        "PA" to FacilityType.PA,
        "本線料金所" to FacilityType.TB,
        "料金所" to FacilityType.TB,
        "TB" to FacilityType.TB,
        "出入口" to FacilityType.IC,
        "出口" to FacilityType.IC,
        "入口" to FacilityType.IC,
        "ランプ" to FacilityType.IC
    )

    private val DIRECTION_WORDS = listOf("上り", "下り", "内回り", "外回り", "行き", "方面", "上下", "集約", "線")
    private val PAREN = Regex("[(（\\[［]([^)）\\]］]*)[)）\\]］]")
    private val TRAILING_DIR = Regex("(上り線|下り線|上下線|上り|下り|内回り|外回り)$")

    fun parse(raw: String?): Parsed {
        if (raw.isNullOrBlank()) return Parsed("", null, false, null, false)
        var s = NameNorm.toHalfWidth(raw).trim()
        var direction: String? = null
        var etc = false

        // 括弧内の注記（上下線・ETC専用など）を取り除く
        s = PAREN.replace(s) { m ->
            val inner = m.groupValues[1]
            if (inner.contains("ETC", ignoreCase = true)) etc = true
            if (DIRECTION_WORDS.any { inner.contains(it) }) direction = inner.trim()
            ""
        }.trim()

        s = s.replace(" ", "").replace("　", "")
        TRAILING_DIR.find(s)?.let { m ->
            if (direction == null) direction = m.value
            s = s.substring(0, m.range.first)
        }
        if (s.contains("ETC専用", ignoreCase = true)) {
            etc = true
            s = s.replace("ETC専用", "", ignoreCase = true)
        }

        var oasis = false
        if (s.endsWith("ハイウェイオアシス")) {
            oasis = true
            s = s.removeSuffix("ハイウェイオアシス")
        }

        var type: FacilityType? = null
        val upper = s.uppercase()
        for ((suffix, t) in SUFFIXES) {
            if (upper.endsWith(suffix.uppercase()) && upper.length >= suffix.length) {
                type = t
                s = s.substring(0, s.length - suffix.length)
                break
            }
        }
        // 「○○SAスマートIC」→ 基本名「○○SA」のままにせず「○○」へ
        if (type == FacilityType.SIC) {
            for (x in listOf("SA", "PA", "サービスエリア", "パーキングエリア")) {
                if (s.uppercase().endsWith(x.uppercase()) && s.length > x.length) {
                    s = s.substring(0, s.length - x.length)
                    break
                }
            }
        }
        s = s.trim().trimEnd('・', '-', '_').trim()
        if (type == FacilityType.SIC) etc = true
        return Parsed(s, type, etc, direction, oasis)
    }
}
