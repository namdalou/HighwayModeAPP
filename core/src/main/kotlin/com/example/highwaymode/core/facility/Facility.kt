package com.example.highwaymode.core.facility

/** 施設種別（表示バッジ） */
enum class FacilityType(val badge: String, val label: String) {
    IC("IC", "インターチェンジ"),
    SIC("SIC", "スマートIC"),
    JCT("JCT", "ジャンクション"),
    SA("SA", "サービスエリア"),
    PA("PA", "パーキングエリア"),
    TB("TB", "本線料金所"),
    CAMERA("オービス", "速度取締機（オービス）");

    val isRestArea: Boolean get() = this == SA || this == PA
    val isExit: Boolean get() = this == IC || this == SIC
}

/** SA/PA 等の設備アイコン */
enum class Amenity(val label: String, val order: Int) {
    GAS("ガソリンスタンド", 0),
    EV("EV充電", 1),
    RESTAURANT("レストラン", 2),
    SNACK("軽食・フードコート", 3),
    CONVENIENCE("コンビニ", 4),
    SHOP("ショッピング", 5),
    TOILET("トイレ", 6),
    INFO("インフォメーション", 7),
    SHOWER("シャワー", 8),
    HOTEL("宿泊施設", 9),
    DOG_RUN("ドッグラン", 10),
    ATM("ATM", 11);
}

/** 施設内の個別 POI */
class PoiInfo(
    val amenity: Amenity,
    val name: String?,
    val brand: String?,
    val openingHours: String?
) {
    fun describe(): String {
        val sb = StringBuilder(amenity.label)
        val n = listOfNotNull(brand, name).distinct().joinToString(" ")
        if (n.isNotEmpty()) sb.append("：").append(n)
        if (!openingHours.isNullOrBlank()) sb.append("（").append(openingHours).append("）")
        return sb.toString()
    }
}

/**
 * IC / JCT / SA / PA / スマートIC / 本線料金所。
 * id はデータ上で一意（同名でも上下線で別オブジェクト）。
 */
class Facility(
    val id: String,
    val type: FacilityType,
    /** 「辰野」「岡谷」など種別サフィックスを除いた名称 */
    val baseName: String,
    /** OSM 上の元の名称 */
    val rawName: String,
    val lat: Double,
    val lon: Double,
    /** ETC 専用（スマート IC、現金不可の料金所） */
    var etcOnly: Boolean,
    /** ETC 専用と判定した根拠 */
    var etcReason: String?,
    /** 出口番号（ref タグ） */
    val exitRef: String?,
    /** 案内方面（destination タグ） */
    var destination: String?
) {
    val amenities: MutableSet<Amenity> = java.util.EnumSet.noneOf(Amenity::class.java)
    val pois: MutableList<PoiInfo> = ArrayList()
    /** 上下線の注記（OSM 名称に含まれる「上り」「下り」等） */
    var directionNote: String? = null
    /** 種別を名称でなくネットワーク形状から推定した場合 true */
    var typeInferred: Boolean = false
    /** オービス: 取締速度・種類（OSM の maxspeed / camera:type 等） */
    var maxspeedKmh: Int? = null
    var cameraType: String? = null
    /** 利用者が登録したデータ */
    var userDefined: Boolean = false

    val displayName: String
        get() = when (type) {
            FacilityType.TB -> if (baseName.isEmpty()) "本線料金所" else "${baseName}料金所"
            FacilityType.SIC -> "${baseName}スマートIC"
            FacilityType.CAMERA -> baseName
            else -> baseName + type.badge
        }

    fun sortedAmenities(): List<Amenity> = amenities.sortedBy { it.order }

    fun mergeFrom(other: Facility) {
        amenities.addAll(other.amenities)
        for (p in other.pois) if (pois.none { it.amenity == p.amenity && it.name == p.name && it.brand == p.brand }) pois.add(p)
        if (other.etcOnly && !etcOnly) {
            etcOnly = true
            etcReason = other.etcReason
        }
        if (destination == null) destination = other.destination
        if (directionNote == null) directionNote = other.directionNote
    }
}

/** 有向エッジ上の施設位置 */
class FacilityAnchor(val facility: Facility, val edgeId: Int, val offset: Double)
