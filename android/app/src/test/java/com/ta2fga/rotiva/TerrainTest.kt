package com.ta2fga.rotiva

import com.ta2fga.rotiva.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.sin

class TerrainTest {
    @Test fun flatNoiseDoesNotAccumulate() {
        val r = Random(1)
        val alts = List(2000) { 100 + r.nextGaussian() * 0.4 }
        val (up, down) = ascentDescent(alts, 2.5)
        assertTrue("çıkış $up", up < 8)
        assertTrue("iniş $down", down < 8)
    }

    @Test fun climbWithNoiseIsCounted() {
        val r = Random(2)
        val alts = List(1001) { it * 0.1 + r.nextGaussian() * 0.4 }      // 0 -> 100 m
        val (up, down) = ascentDescent(alts, 2.5)
        assertEquals(100.0, up, 10.0)
        assertTrue("iniş $down", down < 10)
    }

    @Test fun climbThenDescent() {
        val alts = (0..100).map { it * 1.0 } + (99 downTo 0).map { it * 1.0 }
        val (up, down) = ascentDescent(alts, 2.5)
        assertEquals(100.0, up, 3.0)
        assertEquals(100.0, down, 3.0)
    }

    @Test fun smallWigglesBelowHysteresisIgnored() {
        val alts = List(500) { 50 + 1.0 * sin(it / 3.0) }
        val (up, down) = ascentDescent(alts, 2.5)
        assertTrue(up < 3 && down < 3)
    }

    private fun profile(gradePct: Double, meters: Double = 600.0, kmh: Double = 36.0): List<Sample> {
        val v = kmh / 3.6
        return (0..(meters / v * 2).toInt()).map {
            val t = it * 0.5
            Sample(t = 1000 + t, speed = kmh, alt = 200 + gradePct / 100 * v * t)
        }
    }

    @Test fun gradeFromWindow() {
        val S = profile(5.0)
        annotateTerrain(S)
        val g = S.mapNotNull { it.grade }
        assertTrue(g.size > S.size * 0.9)
        assertEquals(5.0, g[g.size / 2], 0.2)
        val D = profile(-7.0); annotateTerrain(D)
        assertEquals(-7.0, D.mapNotNull { it.grade }.average(), 0.3)
    }

    @Test fun gradeNeedsMinimumDistance() {
        val S = (0..10).map { Sample(t = it * 0.5, speed = 10.0, alt = 100.0 + it) }   // ~14 m
        annotateTerrain(S)
        assertTrue(S.all { it.grade == null })
    }

    @Test fun fuserTracksClimbWithNoisyGps() {
        val r = Random(3)
        val f = AltitudeFuser()
        var truth = 100.0
        var maxErr = 0.0
        for (s in 0 until 300) {
            truth = 100 + s * 0.2
            val hpa = 1013.25 * Math.pow(1 - truth / 44330.0, 5.255) + r.nextGaussian() * 0.01
            f.onBaro(s.toDouble(), hpa)
            f.onGps(s.toDouble(), truth + 20 /*mutlak ofset yok: GPS gerçeği*/ - 20 + r.nextGaussian() * 4, 5.0)
            if (s > 30) maxErr = maxOf(maxErr, Math.abs(f.alt!! - truth))
        }
        assertTrue("hata $maxErr", maxErr < 4.0)
    }

    @Test fun pressureAltitude() {
        assertEquals(0.0, pressureToAltitude(1013.25), 0.01)
        assertEquals(110.0, pressureToAltitude(1000.0), 3.0)
    }

    @Test fun vehicleFrameIsOrientationIndependent() {
        // Telefon eğik: aşağı = (0, .5, .866), araç ileri ekseni cihazda (1,0,0), sağ = aşağı x ileri
        val d = doubleArrayOf(0.0, 0.5, 0.8660254)
        val f = doubleArrayOf(1.0, 0.0, 0.0)
        val right = doubleArrayOf(d[1] * f[2] - d[2] * f[1], d[2] * f[0] - d[0] * f[2], d[0] * f[1] - d[1] * f[0])
        val g = 9.80665
        fun feed(vf: VehicleFrame, af: Double, ar: Double, n: Int) {
            repeat(n) {
                vf.onAccel(af * f[0] + ar * right[0], af * f[1] + ar * right[1], af * f[2] + ar * right[2],
                    d[0] * g, d[1] * g, d[2] * g)
            }
        }
        val vf = VehicleFrame()
        var v = 6.0
        var t = 0.0
        for (k in 0 until 4) { feed(vf, 1.5, 0.0, 50); v += 1.5; t += 1; vf.onSpeed(t, v, 90.0) }
        for (k in 0 until 3) { feed(vf, -1.5, 0.0, 50); v -= 1.5; t += 1; vf.onSpeed(t, v, 90.0) }
        for (k in 0 until 2) { feed(vf, 1.5, 0.0, 50); v += 1.5; t += 1; vf.onSpeed(t, v, 90.0) }
        assertTrue(vf.calibrated)
        vf.takeTick()
        feed(vf, 3.0, 4.0, 25)
        val (l, la) = vf.takeTick()
        assertEquals(3.0 / g, l!!, 0.01)
        assertEquals(4.0 / g, la!!, 0.01)
        feed(vf, -5.0, -2.0, 25)
        val (l2, la2) = vf.takeTick()
        assertEquals(-5.0 / g, l2!!, 0.01)
        assertEquals(-2.0 / g, la2!!, 0.01)
    }

    @Test fun vehicleFrameVerticalJoltAndRobustLateral() {
        val d = doubleArrayOf(0.0, 0.5, 0.8660254)
        val f = doubleArrayOf(1.0, 0.0, 0.0)
        val right = doubleArrayOf(d[1] * f[2] - d[2] * f[1], d[2] * f[0] - d[0] * f[2], d[0] * f[1] - d[1] * f[0])
        val g = 9.80665
        fun acc(vf: VehicleFrame, af: Double, ar: Double, av: Double) = vf.onAccel(
            af * f[0] + ar * right[0] + av * d[0], af * f[1] + ar * right[1] + av * d[1], af * f[2] + ar * right[2] + av * d[2],
            d[0] * g, d[1] * g, d[2] * g)
        val vf = VehicleFrame()
        // kalibrasyon öncesi: dikey değer yine ölçülür, yatay değerler null
        repeat(10) { acc(vf, 0.0, 0.0, 0.5) }
        acc(vf, 0.0, 0.0, -7.0)
        val pre = vf.takeTick()
        assertNull(pre.long); assertNull(pre.lat)
        assertEquals(7.0 / g, pre.vert!!, 1e-6)
        assertTrue(pre.vertRms!! > 0.5 / g)
        assertTrue(pre.vertJerk!! > 7.0 / g)
        assertNull(VehicleFrame().takeTick().vert)      // hiç ölçüm yok

        var v = 6.0; var t = 0.0
        fun feed(af: Double, n: Int) = repeat(n) { acc(vf, af, 0.0, 0.0) }
        for (k in 0 until 4) { feed(1.5, 50); v += 1.5; t += 1; vf.onSpeed(t, v, 90.0) }
        for (k in 0 until 3) { feed(-1.5, 50); v -= 1.5; t += 1; vf.onSpeed(t, v, 90.0) }
        for (k in 0 until 2) { feed(1.5, 50); v += 1.5; t += 1; vf.onSpeed(t, v, 90.0) }
        assertTrue(vf.calibrated)
        vf.takeTick()
        // çukur: 25 ölçümlük aralıkta 2 ölçümlük büyük dikey darbe, yanal eksene sızıntı
        repeat(12) { acc(vf, 0.0, 0.2, 0.3) }
        acc(vf, 0.0, 6.0, 12.0); acc(vf, 0.0, -4.0, -9.0)
        repeat(11) { acc(vf, 0.0, 0.2, 0.3) }
        val tk = vf.takeTick()
        assertEquals(0.2 / g, tk.lat!!, 1e-3)              // medyan: sızıntı bastırıldı
        assertEquals(6.0 / g, tk.latPeak!!, 1e-3)          // ham tepe ayrıca saklanır
        assertEquals(12.0 / g, tk.vert!!, 1e-3)
        // sürekli yanal ivme (viraj) medyanda korunur
        repeat(25) { acc(vf, 0.0, 4.0, 0.2) }
        val (_, la) = vf.takeTick()
        assertEquals(4.0 / g, la!!, 1e-3)
    }

    @Test fun vehicleFrameNotCalibratedInCorners() {
        val vf = VehicleFrame()
        var t = 0.0; var b = 0.0
        repeat(10) {
            repeat(50) { vf.onAccel(1.0, 0.0, 0.0, 0.0, 0.0, 9.8) }
            t += 1; b += 20
            vf.onSpeed(t, 10.0 + it * 2, b)     // saniyede 20 derece dönüş
        }
        assertFalse(vf.calibrated)
    }
}
