package com.example.highwaymode.core.hwdb

import com.example.highwaymode.core.osm.TileId
import kotlin.math.floor

/**
 * 都道府県（ISO 3166-2:JP）と地方の一覧。
 * bbox は本土＋主な島のおおよその範囲（余裕を持たせてある）。データの「完全なタイル」判定に使う。
 * 東京都・鹿児島県・沖縄県の遠い離島（高速道路が無い）は範囲に含めていない。
 */
object Prefectures {

    class Pref(val code: String, val name: String, val region: String, val south: Double, val west: Double, val north: Double, val east: Double) {
        fun intersects(t: TileId): Boolean =
            t.south < north + MARGIN && t.north > south - MARGIN && t.west < east + MARGIN && t.east > west - MARGIN
    }

    class Region(val id: String, val name: String, val prefectures: List<Pref>)

    private const val MARGIN = 0.05

    val ALL: List<Pref> = listOf(
        Pref("JP-01", "北海道", "hokkaido", 41.35, 139.30, 45.60, 145.90),
        Pref("JP-02", "青森県", "tohoku", 40.20, 139.45, 41.60, 141.70),
        Pref("JP-03", "岩手県", "tohoku", 38.70, 140.60, 40.50, 142.10),
        Pref("JP-04", "宮城県", "tohoku", 37.75, 140.25, 39.00, 141.70),
        Pref("JP-05", "秋田県", "tohoku", 38.85, 139.65, 40.55, 141.00),
        Pref("JP-06", "山形県", "tohoku", 37.70, 139.50, 39.25, 140.70),
        Pref("JP-07", "福島県", "tohoku", 36.75, 139.15, 38.00, 141.10),
        Pref("JP-08", "茨城県", "kanto", 35.70, 139.65, 36.95, 140.90),
        Pref("JP-09", "栃木県", "kanto", 36.20, 139.30, 37.20, 140.30),
        Pref("JP-10", "群馬県", "kanto", 35.95, 138.40, 37.10, 139.70),
        Pref("JP-11", "埼玉県", "kanto", 35.75, 138.70, 36.30, 139.95),
        Pref("JP-12", "千葉県", "kanto", 34.85, 139.70, 36.10, 140.90),
        Pref("JP-13", "東京都", "kanto", 35.50, 138.90, 35.90, 139.95),
        Pref("JP-14", "神奈川県", "kanto", 35.10, 138.90, 35.70, 139.80),
        Pref("JP-15", "新潟県", "chubu", 36.70, 137.60, 38.60, 139.95),
        Pref("JP-16", "富山県", "chubu", 36.25, 136.75, 37.00, 137.80),
        Pref("JP-17", "石川県", "chubu", 36.05, 136.20, 37.90, 137.40),
        Pref("JP-18", "福井県", "chubu", 35.30, 135.40, 36.30, 136.85),
        Pref("JP-19", "山梨県", "chubu", 35.15, 138.15, 36.00, 139.15),
        Pref("JP-20", "長野県", "chubu", 35.20, 137.30, 37.05, 138.75),
        Pref("JP-21", "岐阜県", "chubu", 35.10, 136.25, 36.50, 137.70),
        Pref("JP-22", "静岡県", "chubu", 34.55, 137.45, 35.65, 139.20),
        Pref("JP-23", "愛知県", "chubu", 34.55, 136.65, 35.45, 137.85),
        Pref("JP-24", "三重県", "kinki", 33.70, 135.85, 35.30, 137.00),
        Pref("JP-25", "滋賀県", "kinki", 34.75, 135.75, 35.70, 136.50),
        Pref("JP-26", "京都府", "kinki", 34.70, 134.85, 35.80, 136.10),
        Pref("JP-27", "大阪府", "kinki", 34.25, 135.05, 35.05, 135.75),
        Pref("JP-28", "兵庫県", "kinki", 34.15, 134.25, 35.70, 135.50),
        Pref("JP-29", "奈良県", "kinki", 33.85, 135.50, 34.80, 136.25),
        Pref("JP-30", "和歌山県", "kinki", 33.40, 135.00, 34.40, 136.05),
        Pref("JP-31", "鳥取県", "chugoku", 35.05, 133.10, 35.65, 134.55),
        Pref("JP-32", "島根県", "chugoku", 34.30, 131.65, 36.40, 133.40),
        Pref("JP-33", "岡山県", "chugoku", 34.30, 133.25, 35.40, 134.45),
        Pref("JP-34", "広島県", "chugoku", 34.00, 132.00, 35.15, 133.50),
        Pref("JP-35", "山口県", "chugoku", 33.70, 130.75, 34.80, 132.50),
        Pref("JP-36", "徳島県", "shikoku", 33.50, 133.60, 34.30, 134.85),
        Pref("JP-37", "香川県", "shikoku", 34.00, 133.40, 34.60, 134.50),
        Pref("JP-38", "愛媛県", "shikoku", 32.85, 132.00, 34.35, 133.70),
        Pref("JP-39", "高知県", "shikoku", 32.70, 132.45, 33.90, 134.35),
        Pref("JP-40", "福岡県", "kyushu", 33.00, 130.00, 34.00, 131.20),
        Pref("JP-41", "佐賀県", "kyushu", 32.95, 129.70, 33.65, 130.55),
        Pref("JP-42", "長崎県", "kyushu", 32.55, 128.60, 34.75, 130.40),
        Pref("JP-43", "熊本県", "kyushu", 32.05, 129.95, 33.20, 131.35),
        Pref("JP-44", "大分県", "kyushu", 32.70, 130.80, 33.75, 132.10),
        Pref("JP-45", "宮崎県", "kyushu", 31.35, 130.70, 32.85, 131.90),
        Pref("JP-46", "鹿児島県", "kyushu", 30.95, 129.40, 32.25, 131.20),
        Pref("JP-47", "沖縄県", "kyushu", 26.00, 126.60, 26.95, 128.40)
    )

    private val REGION_NAMES = linkedMapOf(
        "hokkaido" to "北海道",
        "tohoku" to "東北",
        "kanto" to "関東",
        "chubu" to "中部",
        "kinki" to "近畿",
        "chugoku" to "中国",
        "shikoku" to "四国",
        "kyushu" to "九州・沖縄"
    )

    val REGIONS: List<Region> = REGION_NAMES.map { (id, name) -> Region(id, name, ALL.filter { it.region == id }) }

    fun byCode(code: String): Pref? = ALL.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /** "all" / 地方 ID / 都道府県コード（カンマ区切り）を都道府県の一覧にする */
    fun resolve(spec: String): List<Pref> {
        val out = LinkedHashSet<Pref>()
        for (raw in spec.split(',', ' ', ';')) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            when {
                s.equals("all", true) || s.equals("japan", true) -> out.addAll(ALL)
                REGION_NAMES.containsKey(s.lowercase()) -> out.addAll(ALL.filter { it.region == s.lowercase() })
                else -> {
                    val code = if (s.matches(Regex("\\d{1,2}"))) "JP-" + s.padStart(2, '0') else s.uppercase()
                    out.add(byCode(code) ?: throw IllegalArgumentException("不明な地域: $s"))
                }
            }
        }
        return out.toList()
    }

    /** 都道府県の範囲にかかるタイル */
    fun tilesOf(p: Pref): List<TileId> {
        val y0 = floor((p.south - MARGIN - TileId.LAT0) / TileId.LAT_STEP).toInt()
        val y1 = floor((p.north + MARGIN - TileId.LAT0) / TileId.LAT_STEP).toInt()
        val x0 = floor((p.west - MARGIN - TileId.LON0) / TileId.LON_STEP).toInt()
        val x1 = floor((p.east + MARGIN - TileId.LON0) / TileId.LON_STEP).toInt()
        val out = ArrayList<TileId>()
        for (y in y0..y1) for (x in x0..x1) out.add(TileId(y, x))
        return out
    }

    /**
     * 指定の都道府県を全部取得したときに「内容が欠けない」タイル。
     * タイルにかかる都道府県がすべて取得済みのものだけを返す（境界のタイルで隣県の道路が欠けるのを防ぐ）。
     */
    fun completeTiles(included: Collection<Pref>): Set<TileId> {
        val inc = included.map { it.code }.toHashSet()
        val out = HashSet<TileId>()
        for (p in included) for (t in tilesOf(p)) {
            if (t in out) continue
            if (ALL.all { q -> q.code in inc || !q.intersects(t) }) out.add(t)
        }
        return out
    }
}
