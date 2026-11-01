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
        val highwayOasis: Boolean,
        /** 種別が「出口」「入口」「ランプ」などから（IC 名などが無い）。「国道2号出口」は行き先の名前 */
        val entryWordOnly: Boolean = false
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

    /** 種別の後ろに付く「出入口」等（「○○IC入口」） */
    private val ENTRY_WORDS = listOf("出入口", "出口", "入口", "ランプ")

    /** 「I.C」「I.C.」「J.C.T」「S.A」「P.A」「S.I.C」（末尾） */
    private val DOTTED = Regex("(S\\.?\\s?)?I\\.\\s?C\\.?$|J\\.\\s?C\\.\\s?T\\.?$|S\\.\\s?A\\.?$|P\\.\\s?A\\.?$", RegexOption.IGNORE_CASE)

    /** 名前の途中の「I.C」（「真岡I.C北ランプ」） */
    private val DOTTED_IC_INNER = Regex("I\\.\\s?C\\.?(?=[^A-Za-z])", RegexOption.IGNORE_CASE)

    /** 「真岡IC北」「○○IC南」: IC 名のあとに向き・番号が付いたランプ名 */
    private val QUALIFIED = Regex("^(.+?)(スマートIC|SIC|IC|インター|JCT)(北|南|東|西|上り|下り|上|下|第一|第二|第三|第1|第2|第3|[0-9]+)$", RegexOption.IGNORE_CASE)

    private val DIRECTION_WORDS = listOf("上り", "下り", "内回り", "外回り", "行き", "方面", "上下", "集約", "線")
    private val PAREN = Regex("[(（\\[［]([^)）\\]］]*)[)）\\]］]")
    private val TRAILING_DIR = Regex("(上り線|下り線|上下線|上り|下り|内回り|外回り)$")

    /** 末尾の種別サフィックス: (サフィックス, 種別, 残り) */
    private fun matchSuffix(s: String): Triple<String, FacilityType, String>? {
        val upper = s.uppercase()
        for ((suffix, t) in SUFFIXES) {
            if (upper.endsWith(suffix.uppercase()) && upper.length > suffix.length) {
                return Triple(suffix, t, s.substring(0, s.length - suffix.length).trimEnd('.', '・', ' '))
            }
        }
        return null
    }

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

        // 「I.C」「I.C.」「J.C.T」「S.A.」などの表記ゆれ（全角は toHalfWidth で半角になっている）
        s = s.trimEnd('.', '・')
        s = DOTTED.replace(s) { m -> m.value.replace(".", "").replace(" ", "") }
        s = DOTTED_IC_INNER.replace(s, "IC")

        var type: FacilityType? = null
        var entryOnly = false
        val first = matchSuffix(s)
        if (first == null) {
            // 「大和スマートIC北」: 種別のあとに向きだけが付いた名前
            QUALIFIED.find(s)?.let { m ->
                type = when (m.groupValues[2].uppercase()) {
                    "スマートIC", "SIC" -> FacilityType.SIC
                    "JCT" -> FacilityType.JCT
                    else -> FacilityType.IC
                }
                if (direction == null) direction = m.groupValues[3]
                s = m.groupValues[1]
            }
        }
        if (first != null) {
            type = first.second
            s = first.third
            // 「豊川IC入口」「○○IC出口」: 出入口などの手前に IC・JCT 等があれば、そちらを種別にする
            if (first.first in ENTRY_WORDS) {
                entryOnly = true
                val second = matchSuffix(s)
                if (second != null && second.first !in ENTRY_WORDS) {
                    type = second.second
                    s = second.third
                    entryOnly = false
                } else {
                    // 「真岡IC北ランプ」: IC 名 + 向き → 基本名は「真岡」、向きは注記に
                    QUALIFIED.find(s)?.let { m ->
                        val t = m.groupValues[2].uppercase()
                        type = when (t) {
                            "スマートIC", "SIC" -> FacilityType.SIC
                            "JCT" -> FacilityType.JCT
                            else -> FacilityType.IC
                        }
                        if (direction == null) direction = m.groupValues[3]
                        s = m.groupValues[1]
                        entryOnly = false
                    }
                }
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
        return Parsed(s, type, etc, direction, oasis, entryOnly)
    }
}
