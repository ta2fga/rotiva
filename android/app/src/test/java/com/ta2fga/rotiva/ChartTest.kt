package com.ta2fga.rotiva

import com.ta2fga.rotiva.core.*
import org.junit.Assert.*
import org.junit.Test

class ChartTest {
    @Test fun ticksUseRoundSteps() {
        val t = niceTicks(803.0, 1247.0, 5)
        assertEquals(100.0, t.step, 1e-9)
        assertEquals(listOf(900.0, 1000.0, 1100.0, 1200.0), t.values)
        assertEquals(800.0, t.niceMin, 1e-9); assertEquals(1300.0, t.niceMax, 1e-9)
        assertEquals(0.5, niceTicks(0.0, 2.3, 5).step, 1e-9)
        assertEquals(20.0, niceTicks(0.0, 100.0, 5).step, 1e-9)
        assertEquals(2.0, niceTicks(0.0, 8.7, 5).step, 1e-9)
        val flat = niceTicks(100.0, 100.0, 4)       // düz profil: sıfır aralık çökmez
        assertTrue(flat.values.isNotEmpty() && flat.niceMax > flat.niceMin)
    }

    @Test fun ticksStayInsideRangeAndCoverIt() {
        for ((lo, hi) in listOf(0.0 to 3.4, 12.0 to 987.0, -40.0 to 130.0, 0.0 to 0.04)) {
            val t = niceTicks(lo, hi, 5)
            assertTrue(t.values.all { it >= lo - 1e-9 && it <= hi + 1e-9 })
            assertTrue(t.niceMin <= lo + 1e-9 && t.niceMax >= hi - 1e-9)
            assertTrue(t.values.size in 2..9)
        }
    }

    @Test fun nearestIndexLookup() {
        val xs = listOf(0.0, 1.0, 2.5, 4.0, 10.0)
        assertEquals(0, nearestIndex(xs, -5.0))
        assertEquals(4, nearestIndex(xs, 99.0))
        assertEquals(1, nearestIndex(xs, 1.4))
        assertEquals(2, nearestIndex(xs, 1.8))
        assertEquals(3, nearestIndex(xs, 6.9))
        assertEquals(4, nearestIndex(xs, 7.1))
        assertEquals(2, nearestIndex(xs, 2.5))
        assertEquals(-1, nearestIndex(emptyList(), 1.0))
    }

    @Test fun tooltipFormat() {
        val p = TrackPoint(0, 0.0, 3.4, 812.0, 4.2, 41.0, 29.0, 50.0, 2000.0, 3)
        assertEquals("Yükseklik: 812 m · Mesafe: 3,4 km · Eğim: +4,2%", altTooltip(p))
    }

    @Test fun demoRouteIsClosedHillyLoop() {
        val loop = DemoWorld.LOOP_M
        assertTrue(loop in 5000.0..10000.0)
        val a = DemoWorld.pointAt(0.0); val b = DemoWorld.pointAt(loop - 0.5)
        assertTrue(Math.hypot(a.first - b.first, a.second - b.second) < 5.0)
        val alts = (0..600).map { DemoWorld.altitudeAt(it * loop / 600) }
        assertTrue(alts.max() - alts.min() > 60)
        val maxGrade = (1..600).maxOf { Math.abs(alts[it] - alts[it - 1]) / (loop / 600) * 100 }
        assertTrue("eğim $maxGrade", maxGrade in 3.0..14.0)
        val w = DemoWorld()
        for (i in 0..200) w.step(i * 0.5, 60.0)
        assertTrue(w.lat in 41.0..41.1 && w.lon in 29.0..29.1)
    }

    private fun pt(i: Int, speed: Double?, rpm: Double?, gear: Int?) =
        TrackPoint(i, i * 10.0, 0.0, null, null, null, null, speed, rpm, gear)
    private val S = SpeedSeries.SPEED; private val R = SpeedSeries.RPM; private val G = SpeedSeries.GEAR

    @Test fun seriesToggleKeepsAtLeastOne() {
        assertEquals(setOf(S, G), DEFAULT_SPEED_SERIES)
        assertEquals(setOf(S, G, R), toggleSeries(setOf(S, G), R))
        assertEquals(setOf(G), toggleSeries(setOf(S, G), S))
        assertEquals(setOf(G), toggleSeries(setOf(G), G))          // son seri kapatılamaz
    }

    @Test fun seriesEncodingRoundTrip() {
        for (v in listOf(setOf(S), setOf(R, G), setOf(S, R, G), setOf(G, S)))
            assertEquals(v, decodeSeries(encodeSeries(v)))
        assertEquals("SPEED,GEAR", encodeSeries(setOf(G, S)))      // sabit sıra
        assertEquals(DEFAULT_SPEED_SERIES, decodeSeries(null))
        assertEquals(DEFAULT_SPEED_SERIES, decodeSeries(""))
        assertEquals(DEFAULT_SPEED_SERIES, decodeSeries("FOO,,BAR"))
        assertEquals(setOf(R), decodeSeries("FOO, RPM"))
    }

    @Test fun axisFollowsFirstVisibleSeries() {
        val pts = listOf(pt(0, 0.0, 800.0, 1), pt(1, 92.0, 5200.0, 5), pt(2, 50.0, null, null))
        val sp = speedAxis(setOf(S, R, G), pts)
        assertEquals(S, sp.series); assertEquals("dk  /  km/s", sp.unitLabel)
        assertEquals(0.0, sp.ticks.niceMin, 1e-9); assertTrue(sp.ticks.niceMax >= 92.0)

        val rp = speedAxis(setOf(R, G), pts)
        assertEquals(R, rp.series); assertEquals("dk  /  dev/dk", rp.unitLabel)
        assertEquals(2000.0, rp.ticks.step, 1e-9); assertEquals(6000.0, rp.ticks.niceMax, 1e-9)

        val gr = speedAxis(setOf(G), pts)
        assertEquals(G, gr.series); assertEquals("dk  /  vites", gr.unitLabel)
        assertEquals(1.0, gr.ticks.step, 1e-9)                      // vites ekseni tam sayı
        assertEquals((0..6).map { it.toDouble() }, gr.ticks.values)
    }

    @Test fun seriesTopsHaveFloors() {
        val empty = listOf(pt(0, null, null, null), pt(1, 10.0, 900.0, 2))
        assertEquals(60.0, seriesTop(S, empty), 1e-9)
        assertEquals(4000.0, seriesTop(R, empty), 1e-9)
        assertEquals(6.0, seriesTop(G, empty), 1e-9)
        assertEquals(8.0, seriesTop(G, listOf(pt(0, 1.0, 1.0, 7))), 1e-9)   // 7 vites + pay
        assertEquals(4000.0, seriesTicks(R, empty).niceMax, 1e-9)
        assertEquals(5.0, seriesValue(G, pt(0, 1.0, 2.0, 5))!!, 1e-9)
        assertNull(seriesValue(R, pt(0, 1.0, null, 5)))
    }

    @Test fun speedTooltipListsOnlyVisible() {
        val p = TrackPoint(0, 125.0, 1.0, null, null, null, null, 63.4, 2150.6, 4)
        val t = fmtDuration(125.0)
        assertEquals("Hız: 63 km/s · Devir: 2151 · Vites: 4 · $t", speedTooltip(p))
        assertEquals("Hız: 63 km/s · Vites: 4 · $t", speedTooltip(p, setOf(S, G)))
        assertEquals("Devir: 2151 · $t", speedTooltip(p, setOf(R)))
        assertEquals("Vites: — · $t", speedTooltip(TrackPoint(0, 125.0, 1.0, null, null, null, null, 63.4, 2150.6, null), setOf(G)))
    }
}
