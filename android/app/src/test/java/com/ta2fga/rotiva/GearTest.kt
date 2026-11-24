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

    /** v3: öğrenme varsayılana bağlı değil (eski ±%18 sınırı kalktı); tek bir tuhaf tepe bile akla yatkın şanzıman verir. */
    @Test fun singleOddPeakStaysPlausibleAndOldModelDiscarded() {
        val g = GearEstimator()
        var t = 0.0
        repeat(400) { g.update(t, 2000.0, 65.6); t += 0.3 }  // 32,8: varsayılan 4. ile 5. arası
        for (i in 0 until 4) { val st = g.ratios[i + 1] / g.ratios[i]; assertTrue("adım $st", st >= 1.15 - 1e-9 && st <= 2.1 + 1e-9) }
        assertTrue(g.scale in 0.6..1.6)
        assertNotNull(g.classify(2000.0, 65.6))              // öğrenilen tepe artık bir vites
        val old = JSONObject().put("ratios", JSONArray(listOf(9.0, 16.0, 24.0, 31.0, 40.0))).put("counts", JSONArray(listOf(1, 1, 1, 1, 1)))
        assertArrayEquals(DEFAULT_RATIOS, GearEstimator.fromJson(old).ratios, 1e-9)
        assertArrayEquals(DEFAULT_RATIOS, GearEstimator.fromJson(JSONObject(old.toString()).put("v", 2)).ratios, 1e-9)
    }

    @Test fun noDeadGapBetweenCenters() {
        val g = GearEstimator()
        assertEquals(2, g.classify(2000.0, 32.2))           // 16,1 = 2. merkezin %15 üstü: eski algoritmada boşluk
        assertEquals(3, g.classify(2000.0, 37.0))           // 18,5
        assertNull(g.classify(2000.0, 34.7))                // 17,35 = 2-3 tam ortası: tutarsız (debriyaj)
    }

    @Test fun persistenceRoundTrip() {
        val g = GearEstimator(doubleArrayOf(7.9, 14.8, 21.9, 29.5, 38.1), intArrayOf(5, 6, 7, 8, 9))
        val g2 = GearEstimator.fromJson(g.toJson())
        assertArrayEquals(g.ratios, g2.ratios, 1e-6)
        assertArrayEquals(g.counts, g2.counts)
        // histogramla: kayıttan dönen model aynı oranları yeniden kestirir
        val (g3, _) = drive(scaled(1.25))
        val g4 = GearEstimator.fromJson(JSONObject(g3.toJson().toString()))
        assertArrayEquals(g3.ratios, g4.ratios, 1e-3)
        assertArrayEquals(g3.counts, g4.counts)
        var diff = 0.0
        for (i in g3.hist.indices) diff += Math.abs(g3.hist[i] - g4.hist[i])
        assertTrue(diff < 0.05 * g3.hist.size)
        g4.reestimate()
        for (i in 0 until 5) assertEquals(g3.ratios[i], g4.ratios[i], g3.ratios[i] * 0.005)
    }

    // --- gerçek oranları varsayılandan çok farklı araçlar: tepe bulma + şanzıman modeli doğru numaralamalı

    private fun scaled(k: Double) = DoubleArray(5) { DEFAULT_RATIOS[it] * k }

    /** Ayrı okunan devir/hız (0,15 sn kayma, gürültü, tamsayı hız) ile sürüş; son turda vites başına doğruluk. */
    private fun drive(ratios: DoubleArray, segs: List<DriveCycle.Seg> = DriveCycle.SEGS, loops: Int = 4, seed: Long = 5):
        Pair<GearEstimator, Array<Double?>> {
        val cyc = DriveCycle(ratios, segs)
        val g = GearEstimator()
        val rnd = java.util.Random(seed)
        val ok = IntArray(5); val tot = IntArray(5)
        var t = 0.0
        while (t < cyc.length * loops) {
            val p = cyc.at(t)
            g.pushRpm(t, Math.round((p.rpm + rnd.nextGaussian() * 12) * 4) / 4.0)
            val p2 = cyc.at(t + 0.15)
            g.pushSpeed(t + 0.15, Math.round(p2.speed).toDouble())
            val tg = p2.gear
            if (t >= cyc.length * (loops - 1) && tg != null && p2.speed >= 8) {
                tot[tg - 1]++
                if (g.gear == tg) ok[tg - 1]++
            }
            t += 0.3 + (rnd.nextDouble() - 0.5) * 0.1
        }
        return g to Array(5) { if (tot[it] > 0) ok[it].toDouble() / tot[it] else null }
    }

    private fun check(truth: DoubleArray, segs: List<DriveCycle.Seg> = DriveCycle.SEGS, fromData: Set<Int> = setOf(1, 2, 3, 4, 5)):
        Pair<GearEstimator, Array<Double?>> {
        val (g, acc) = drive(truth, segs)
        val msg = "oranlar=${g.ratios.joinToString()} gerçek=${truth.joinToString()} sayaç=${g.counts.joinToString()} doğruluk=${acc.joinToString()}"
        for (i in 0 until 5) {
            val gear = i + 1
            assertEquals("vites $gear $msg", truth[i], g.ratios[i], truth[i] * (if (gear in fromData) 0.03 else 0.06))
            assertEquals("vites $gear $msg", gear, g.classify(2000.0, truth[i] * 2))      // her vites doğru sınıflanır
            assertEquals("vites $gear veriden mi $msg", gear in fromData, g.counts[i] > 0)
            val a = acc[i]
            if (a != null && gear in fromData) assertTrue("vites $gear doğruluk $msg", a >= 0.6)
        }
        assertTrue(msg, g.confident)
        return g to acc
    }

    private val CITY = listOf(                                 // şehir: 5. vites hiç yok
        DriveCycle.Seg(4.0, 0.0, 0), DriveCycle.Seg(7.0, 14.0, 1), DriveCycle.Seg(12.0, 28.0, 2), DriveCycle.Seg(18.0, 42.0, 3),
        DriveCycle.Seg(30.0, 45.0, 3), DriveCycle.Seg(36.0, 55.0, 4), DriveCycle.Seg(56.0, 55.0, 4), DriveCycle.Seg(62.0, 40.0, 4),
        DriveCycle.Seg(66.0, 38.0, 3), DriveCycle.Seg(76.0, 35.0, 3), DriveCycle.Seg(80.0, 25.0, 2), DriveCycle.Seg(95.0, 25.0, 2),
        DriveCycle.Seg(100.0, 30.0, 2), DriveCycle.Seg(104.0, 40.0, 3), DriveCycle.Seg(110.0, 50.0, 4), DriveCycle.Seg(125.0, 52.0, 4),
        DriveCycle.Seg(128.0, 20.0, 4), DriveCycle.Seg(132.0, 0.0, 0), DriveCycle.Seg(136.0, 0.0, 0),
    )
    private val NO_FIRST = listOf(                             // 1. vites yalnızca 0->9 km/s (kararlı 1. yok)
        DriveCycle.Seg(4.0, 0.0, 0), DriveCycle.Seg(6.0, 9.0, 1), DriveCycle.Seg(10.0, 26.0, 2), DriveCycle.Seg(15.0, 34.0, 2),
        DriveCycle.Seg(21.0, 46.0, 3), DriveCycle.Seg(27.0, 52.0, 4), DriveCycle.Seg(45.0, 52.0, 4), DriveCycle.Seg(49.0, 25.0, 4),
        DriveCycle.Seg(53.0, 0.0, 0), DriveCycle.Seg(58.0, 0.0, 0), DriveCycle.Seg(60.0, 9.0, 1), DriveCycle.Seg(63.0, 40.0, 2),
        DriveCycle.Seg(66.0, 58.0, 3), DriveCycle.Seg(71.0, 75.0, 4), DriveCycle.Seg(81.0, 106.0, 4), DriveCycle.Seg(85.0, 106.0, 5),
        DriveCycle.Seg(93.0, 112.0, 5, null, 9.0), DriveCycle.Seg(101.0, 112.0, 5, null, 9.0), DriveCycle.Seg(105.0, 60.0, 5),
        DriveCycle.Seg(111.0, 46.0, 5, 82.0), DriveCycle.Seg(117.0, 58.0, 5, 82.0), DriveCycle.Seg(127.0, 0.0, 0), DriveCycle.Seg(132.0, 0.0, 0),
    )

    /** Bildirilen C-Elysée durumu (gerçek ≈ varsayılan × 1,25): eskiden 2. hiç görünmüyor, 3 -> 4, 4 -> 5 idi. */
    @Test fun discoversGearboxScaledUp125() {
        val (_, acc) = check(scaled(1.25))
        assertTrue(acc[1]!! >= 0.6)                          // 2. vites artık görünüyor
    }

    @Test fun discoversGearboxScaledDown08() { check(scaled(0.8)) }

    @Test fun discoversNonUniformGearbox() { check(doubleArrayOf(8.2, 16.8, 24.0, 33.0, 40.0)) }

    @Test fun cityDriveWithoutFifthInfersFifthFromShape() {
        val (g, _) = check(scaled(1.25), CITY, setOf(2, 3, 4))
        assertEquals(0, g.counts[4])
        assertFalse(g.learned(5))
    }

    @Test fun driveWithoutSteadyFirstInfersFirstFromShape() {
        val (g, _) = check(scaled(1.25), NO_FIRST, setOf(2, 3, 4, 5))
        assertEquals(0, g.counts[0])
    }
}
