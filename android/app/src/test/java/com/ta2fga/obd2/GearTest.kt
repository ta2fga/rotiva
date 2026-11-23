package com.ta2fga.obd2

import com.ta2fga.obd2.core.*
import org.junit.Assert.*
import org.junit.Test

class GearTest {
    @Test fun classifyUsesNearestRatio() {
        val g = GearEstimator()
        assertEquals(3, g.classify(2000.0, 45.0))     // 22,5
        assertEquals(1, g.classify(2000.0, 16.0))     // 8
        assertNull(g.classify(800.0, 20.0))           // rölanti/kavrama
        assertNull(g.classify(2000.0, 2.0))           // duruyor
    }

    @Test fun learnsTrueRatiosFromDriveCycle() {
        val cyc = DriveCycle()
        val g = GearEstimator()
        var t = 0.0
        while (t < 131.0 * 3) { val p = cyc.at(t); g.update(t, p.rpm, p.speed); t += 0.2 }
        for (i in 0 until 5) {
            val truth = DriveCycle.TRUE_RATIOS[i]
            assertEquals("vites ${i + 1}", truth, g.ratios[i], truth * 0.08)
        }
        assertTrue(g.shifts.isNotEmpty())
        assertTrue(g.shifts.any { it.up })
    }

    @Test fun debounceAndShiftDetection() {
        val g = GearEstimator(doubleArrayOf(8.0, 15.0, 22.5, 30.0, 38.0).also { })
        // 2. viteste sabit, sonra 3. viteste sabit
        var t = 0.0
        repeat(10) { g.update(t, 3000.0, 45.0); t += 0.2 }
        assertEquals(2, g.gear)
        repeat(10) { g.update(t, 2000.0, 45.0); t += 0.2 }
        assertEquals(3, g.gear)
        val sh = g.shifts.last()
        assertEquals(2, sh.fromGear); assertEquals(3, sh.toGear)
        assertEquals(3000.0, sh.rpm, 1.0)
    }

    @Test fun persistenceRoundTrip() {
        val g = GearEstimator(doubleArrayOf(7.9, 14.8, 21.9, 29.5, 38.1), intArrayOf(5, 6, 7, 8, 9))
        val g2 = GearEstimator.fromJson(g.toJson())
        assertArrayEquals(g.ratios, g2.ratios, 1e-6)
        assertArrayEquals(g.counts, g2.counts)
    }
}
