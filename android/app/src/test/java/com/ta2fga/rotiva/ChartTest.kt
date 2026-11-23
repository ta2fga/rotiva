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
}
