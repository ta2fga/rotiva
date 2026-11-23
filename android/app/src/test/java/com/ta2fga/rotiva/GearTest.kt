package com.ta2fga.rotiva

import com.ta2fga.rotiva.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GearTest {
    @Test fun classifyUsesNearestRatio() {
        val g = GearEstimator()
        assertEquals(3, g.classify(2000.0, 43.0))           // 21,5
        assertEquals(1, g.classify(2000.0, 15.0))           // 7,5
        assertEquals(5, g.classify(2000.0, 74.0))           // 37
        assertEquals(NEUTRAL, g.classify(850.0, 40.0))      // hareket hâlinde rölanti: boşta/debriyaj
        assertEquals(NEUTRAL, g.classify(900.0, 1.0))       // duruyor
        assertNull(g.classify(2000.0, 50.0))                // 25: hiçbir merkeze uymuyor -> tutarsız, boşta DEĞİL
        assertNull(g.classify(null, 50.0))
        assertNull(g.classify(0.0, 0.0))                    // motor kapalı
    }

    @Test fun learnsTrueRatiosFromDriveCycle() {
        val cyc = DriveCycle()
        val g = GearEstimator()
        var t = 0.0
        while (t < 131.0 * 3) { val p = cyc.at(t); g.update(t, p.rpm, p.speed); t += 0.2 }
        for (i in 0 until 5) {
            val truth = DriveCycle.TRUE_RATIOS[i]
            assertEquals("vites ${i + 1}", truth, g.ratios[i], truth * 0.05)
            assertTrue("vites ${i + 1} öğrenildi", g.counts[i] > 0)
        }
        assertTrue(g.shifts.isNotEmpty())
        assertTrue(g.shifts.any { it.up })
    }

    @Test fun debounceAndShiftDetection() {
        val g = GearEstimator()
        var t = 0.0
        repeat(10) { g.update(t, 3000.0, 42.0); t += 0.2 }   // 2. vites (14)
        assertEquals(2, g.gear)
        repeat(10) { g.update(t, 2000.0, 43.0); t += 0.2 }   // 3. vites (21,5)
        assertEquals(3, g.gear)
        val sh = g.shifts.last()
        assertEquals(2, sh.fromGear); assertEquals(3, sh.toGear)
        assertEquals(3000.0, sh.rpm, 1.0)
    }

    @Test fun hysteresisHoldsGearThroughClutchAndGoesUnknownWhenStale() {
        val g = GearEstimator()
        var t = 0.0
        repeat(10) { g.update(t, 2000.0, 43.0); t += 0.3 }
        assertEquals(3, g.gear)
        g.update(t, 2000.0, 58.0); t += 0.3                  // tek sapan örnek (4.) vitesi değiştirmez
        assertEquals(3, g.gear)
        repeat(3) { g.update(t, 2000.0, 50.0); t += 0.3 }    // kısa tutarsızlık (debriyaj) -> korunur
        assertEquals(3, g.gear)
        assertEquals("3", g.label(t))
        assertEquals("—", g.label(t + 10))                   // veri eski: bilinmiyor, "boş" değil
        repeat(10) { g.update(t, 2000.0, 50.0); t += 0.3 }   // uzun tutarsızlık -> bilinmiyor
        assertNull(g.stateAt(t))
        repeat(6) { g.update(t, 820.0, 45.0); t += 0.3 }     // rölantide 45 km/s: boşta (N)
        assertEquals("N", g.label(t))
        assertNull(g.gear)
    }

    @Test fun alignerInterpolatesSeparateRpmAndSpeedReads() {
        val g = GearEstimator()
        var t = 0.0
        // 2. viteste hızlanma; devir hızdan 0,4 sn önce okunuyor (gerçek BLE sırası)
        for (i in 0 until 40) {
            val sp = 20 + i * 0.5
            g.pushRpm(t, (sp - 0.25) / 14.0 * 1000)
            g.pushSpeed(t + 0.4, sp)
            t += 0.8
        }
        assertEquals(2, g.gear)
        // çok farklı anlarda okunmuş çift yok sayılır
        val g2 = GearEstimator()
        g2.update(0.0, 2000.0, 43.0, skew = 1.5)
        assertNull(g2.stateAt(0.0))
        // hizalayıcı: köşeli iki devir arasında doğrusal enterpolasyon
        val a = PairAligner()
        assertTrue(a.pushRpm(0.0, 1000.0).isEmpty())
        assertTrue(a.pushSpeed(0.5, 20.0).isEmpty())         // sonraki devir bekleniyor
        val p = a.pushRpm(1.0, 2000.0).single()
        assertEquals(1500.0, p.second, 1e-6)
    }

    @Test fun learningIsBoundedAndOldModelDiscarded() {
        val g = GearEstimator()
        var t = 0.0
        repeat(400) { g.update(t, 2000.0, 65.6); t += 0.3 }  // 4. ile 5. arası tuhaf oran
        for (i in 0 until 5) assertTrue(Math.abs(g.ratios[i] - DEFAULT_RATIOS[i]) / DEFAULT_RATIOS[i] <= 0.18 + 1e-9)
        val old = JSONObject().put("ratios", JSONArray(listOf(9.0, 16.0, 24.0, 31.0, 40.0))).put("counts", JSONArray(listOf(1, 1, 1, 1, 1)))
        assertArrayEquals(DEFAULT_RATIOS, GearEstimator.fromJson(old).ratios, 1e-9)
    }

    @Test fun persistenceRoundTrip() {
        val g = GearEstimator(doubleArrayOf(7.9, 14.8, 21.9, 29.5, 38.1), intArrayOf(5, 6, 7, 8, 9))
        val g2 = GearEstimator.fromJson(g.toJson())
        assertArrayEquals(g.ratios, g2.ratios, 1e-6)
        assertArrayEquals(g.counts, g2.counts)
    }
}
