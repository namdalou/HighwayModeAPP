package com.example.highwaymode.core

import com.example.highwaymode.core.facility.Amenity
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.route.RouteAhead
import com.example.highwaymode.core.route.RouteAheadBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class GraphFacilityTest {

    companion object {
        lateinit var net: TestNetwork
        lateinit var graph: RoadGraph

        @BeforeClass
        @JvmStatic
        fun setup() {
            net = TestNetwork().build()
            graph = net.graph()
        }

        fun routeFrom(lat: Double, lon: Double, wayIds: Set<Long>): RouteAhead {
            val p = graph.nearby(lat, lon, 20.0) { it.wayId in wayIds }.first()
            return RouteAheadBuilder().build(graph, p.edge, p.offset)
        }
    }

    @Test
    fun graphIsDirectedAndOneway() {
        assertFalse(graph.isEmpty)
        // 本線は一方通行（oneway=yes）なので twin を持たない
        val sb = graph.edges.filter { it.wayId in net.sbWayIds }
        assertTrue(sb.isNotEmpty())
        assertTrue(sb.all { it.twin == -1 && it.forwardOnWay })
        // 別線（対面通行）は往復のエッジを持つ
        val other = graph.edges.filter { it.wayId in net.otherRoadWayIds }
        assertTrue(other.any { !it.forwardOnWay })
        assertTrue(other.all { it.twin >= 0 })
    }

    @Test
    fun southboundFacilitiesInOrderWithCorrectSide() {
        val start = net.sbPoint(1000.0)
        val r = routeFrom(start.lat, start.lon, net.sbWayIds)
        val list = r.facilities.map { it.facility.type to it.facility.baseName }
        println("下り前方施設: " + r.facilities.joinToString { "${it.facility.displayName}@${"%.1f".format(it.distance / 1000)}km" })
        assertEquals(
            listOf(
                FacilityType.IC to "テスト北",
                FacilityType.SA to "テスト",
                FacilityType.JCT to "中央",
                FacilityType.SIC to "テスト",
                FacilityType.IC to "テスト南",
                FacilityType.TB to "テスト"
            ),
            list
        )
        val ic = r.facilities[0]
        assertEquals(9000.0, ic.distance, 30.0)
        assertEquals("3", ic.facility.exitRef)
        assertEquals("テスト市・北町", ic.facility.destination)

        val sa = r.facilities[1].facility
        assertTrue("下りSAにはガソリンスタンド", Amenity.GAS in sa.amenities)
        assertTrue(Amenity.RESTAURANT in sa.amenities)
        assertTrue(Amenity.TOILET in sa.amenities)
        assertFalse("上り線SAのコンビニが混ざらない", Amenity.CONVENIENCE in sa.amenities)
        assertTrue(sa.pois.any { it.brand == "ENEOS" && it.openingHours == "24/7" })

        val jct = r.facilities[2].facility
        assertTrue("サフィックス無しでも形状から JCT と推定", jct.typeInferred)
        assertEquals("別線自動車道", jct.destination)

        val sic = r.facilities[3].facility
        assertTrue(sic.etcOnly)
        assertFalse(r.facilities[0].facility.etcOnly)
        val south = r.facilities[4].facility
        assertTrue("出口料金所が現金不可 → ETC専用", south.etcOnly)
        assertNotNull(south.etcReason)

        // 区間内の路線変更は無い（同じ E99 を継続）
        assertTrue(r.roadChanges.isEmpty())
    }

    @Test
    fun northboundSeesOnlyItsOwnFacilities() {
        val start = net.nbPoint(59_000.0)
        val r = routeFrom(start.lat, start.lon, net.nbWayIds)
        println("上り前方施設: " + r.facilities.joinToString { "${it.facility.displayName}@${"%.1f".format(it.distance / 1000)}km" })
        val types = r.facilities.map { it.facility.type to it.facility.baseName }
        assertEquals(
            listOf(FacilityType.TB to "テスト", FacilityType.SA to "テスト", FacilityType.IC to "テスト北"),
            types
        )
        val sa = r.facilities[1].facility
        assertTrue("上りSAはコンビニ", Amenity.CONVENIENCE in sa.amenities)
        assertFalse("下りSAのガソリンスタンドは見えない", Amenity.GAS in sa.amenities)
        assertEquals(39_000.0, r.facilities[1].distance, 400.0)
    }

    @Test
    fun linkLeadsToOtherRoad() {
        // JCT ランプ上から辿ると別線へ乗り継ぐ
        val p = graph.edges.first { it.wayId == net.jctLinkWayId }
        val r = RouteAheadBuilder().build(graph, p.id, 10.0)
        assertTrue(r.roadChanges.isNotEmpty())
        assertEquals("別線自動車道", r.roadChanges[0].toName)
    }
}
