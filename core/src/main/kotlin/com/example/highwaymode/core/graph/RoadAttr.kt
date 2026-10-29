package com.example.highwaymode.core.graph

enum class RoadClass(val rank: Int, val isLink: Boolean) {
    MOTORWAY(5, false),
    MOTORWAY_LINK(5, true),
    TRUNK(4, false),
    TRUNK_LINK(4, true),
    PRIMARY(3, false),
    PRIMARY_LINK(3, true),
    SECONDARY(2, false),
    SECONDARY_LINK(2, true),
    OTHER(1, false);

    companion object {
        fun of(highway: String?): RoadClass = when (highway) {
            "motorway" -> MOTORWAY
            "motorway_link" -> MOTORWAY_LINK
            "trunk" -> TRUNK
            "trunk_link" -> TRUNK_LINK
            "primary" -> PRIMARY
            "primary_link" -> PRIMARY_LINK
            "secondary" -> SECONDARY
            "secondary_link" -> SECONDARY_LINK
            else -> OTHER
        }
    }
}

enum class OnewayMode { FORWARD, BACKWARD, BOTH, IMPLIED_FORWARD }

/**
 * 有向エッジ単位の道路属性（制限速度・車線数は進行方向別に解決済み）。
 */
class RoadAttr(
    val roadClass: RoadClass,
    val name: String?,
    val ref: String?,
    /** 標識の最高速度 [km/h]（タグ無しなら null） */
    val maxspeedKmh: Int?,
    /** この進行方向の車線数（不明なら null） */
    val lanes: Int?,
    val tunnel: Boolean,
    val bridge: Boolean,
    val toll: Boolean,
    val motorroad: Boolean,
    /** 首都高・阪神高速などの都市高速 */
    val urban: Boolean,
    /** 対面通行（暫定 2 車線など、上下線が 1 本のウェイ） */
    val bidirectional: Boolean,
    /** 案内方面（ランプ等の destination タグ） */
    val destination: String?,
    val operator: String?
) {
    val isLink: Boolean get() = roadClass.isLink

    /** 表示用の路線番号（"E19;E20" → "E19"、一般国道の "23" → "国道23号"） */
    val displayRef: String?
        get() {
            val r = ref?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if ((roadClass == RoadClass.TRUNK || roadClass == RoadClass.TRUNK_LINK) && r.all { it.isDigit() }) return "国道${r}号"
            return r
        }

    /** 一般道（有料でも自動車専用でもない国道バイパスなど）。信号・歩行者がありうる */
    val generalRoad: Boolean
        get() = roadClass != RoadClass.MOTORWAY && roadClass != RoadClass.MOTORWAY_LINK && !toll && !motorroad

    /** 同一路線判定用の正規化名 */
    val normName: String? = name?.let { NameNorm.normalizeRoadName(it) }

    val normRefs: Set<String> =
        ref?.split(';')?.map { NameNorm.toHalfWidth(it).trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** 表示用の路線名（名称が無ければ路線番号） */
    val displayName: String?
        get() = name ?: displayRef

    companion object {
        private val URBAN_KEYWORDS = listOf(
            "首都高", "阪神高速", "名古屋高速", "名古屋都市高速", "福岡高速", "北九州高速",
            "福岡北九州高速", "広島高速", "都市高速", "京都高速"
        )

        fun parseMaxspeed(v: String?): Int? {
            if (v == null) return null
            val s = v.trim().lowercase()
            if (s.isEmpty() || s == "none" || s == "signals" || s == "variable" || s == "walk") return null
            val first = s.split(';').first().trim()
            val mph = first.endsWith("mph")
            val num = first.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
            val kmh = if (mph) num * 1.609344 else num
            return if (kmh in 5.0..200.0) Math.round(kmh).toInt() else null
        }

        fun parseInt(v: String?): Int? = v?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

        fun onewayMode(tags: Map<String, String>, roadClass: RoadClass): OnewayMode {
            return when (tags["oneway"]?.trim()?.lowercase()) {
                "yes", "true", "1" -> OnewayMode.FORWARD
                "-1", "reverse" -> OnewayMode.BACKWARD
                "no", "false", "0" -> OnewayMode.BOTH
                "reversible", "alternating" -> OnewayMode.BOTH
                else -> {
                    if (tags["junction"] == "roundabout") OnewayMode.FORWARD
                    else if (roadClass == RoadClass.MOTORWAY || roadClass == RoadClass.MOTORWAY_LINK) OnewayMode.IMPLIED_FORWARD
                    else OnewayMode.BOTH
                }
            }
        }

        /**
         * タグから有向エッジの属性を作る。
         * @param forward ウェイの記述方向に進む場合 true
         */
        fun fromTags(tags: Map<String, String>, forward: Boolean, mode: OnewayMode): RoadAttr {
            val rc = RoadClass.of(tags["highway"])
            val name = (tags["name:ja"] ?: tags["name"])?.trim()?.takeIf { it.isNotEmpty() }
            val ref = tags["ref"]?.trim()?.takeIf { it.isNotEmpty() }
            val dirKey = if (forward) "forward" else "backward"
            val maxspeed = parseMaxspeed(tags["maxspeed:$dirKey"]) ?: parseMaxspeed(tags["maxspeed"])
            val bidir = mode == OnewayMode.BOTH
            val lanes = parseInt(tags["lanes:$dirKey"]) ?: parseInt(tags["lanes"])?.let { total ->
                if (bidir) maxOf(1, total / 2) else total
            }
            val tunnel = tags["tunnel"]?.let { it != "no" } ?: false
            val bridge = tags["bridge"]?.let { it != "no" } ?: false
            val operator = tags["operator"]
            val urbanByName = name != null && URBAN_KEYWORDS.any { name.contains(it) }
            val urbanByOp = operator != null && (operator.contains("首都高速") || operator.contains("阪神高速") ||
                operator.contains("名古屋高速") || operator.contains("福岡北九州高速") || operator.contains("広島高速"))
            val dest = tags["destination:$dirKey"] ?: tags["destination"] ?: tags["destination:ja"]
            return RoadAttr(
                roadClass = rc,
                name = name,
                ref = ref,
                maxspeedKmh = maxspeed,
                lanes = lanes,
                tunnel = tunnel,
                bridge = bridge,
                toll = tags["toll"] == "yes",
                motorroad = tags["motorroad"] == "yes",
                urban = urbanByName || urbanByOp,
                bidirectional = bidir,
                destination = dest?.replace(';', '・'),
                operator = operator
            )
        }
    }
}

object NameNorm {
    /** 全角英数・記号を半角に、空白を除去 */
    fun toHalfWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val code = c.code
            when {
                code in 0xFF01..0xFF5E -> sb.append((code - 0xFEE0).toChar())
                c == '　' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun normalizeRoadName(s: String): String =
        toHalfWidth(s).replace(" ", "").replace("・", "").uppercase()
}
