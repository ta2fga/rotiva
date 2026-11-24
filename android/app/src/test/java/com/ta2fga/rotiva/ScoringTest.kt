package com.ta2fga.rotiva

import com.ta2fga.rotiva.core.*
import org.junit.Assert.*
import org.junit.Test

class ScoringTest {
    /** 2 Hz örnekler; hız fonksiyonu km/s, eğim % sabit. */
    private fun build(seconds: Int, gradePct: Double, speedAt: (Double) -> Double,
                      mod: (Sample, Double) -> Unit = { _, _ -> }): List<Sample> {
        val out = ArrayList<Sample>()
        var dist = 0.0
        var t = 0.0
        while (t <= seconds) {
            val v = speedAt(t)
            val s = Sample(t = 1000 + t, speed = v, rpm = 2000.0, load = 40.0, coolant = 85.0, gear = 3)
            s.alt = 300 + gradePct / 100 * dist
            mod(s, t)
            out.add(s)
            dist += v / 3.6 * 0.5
            t += 0.5
        }
        return out
    }

    // 3 sn'de 10 -> 40 km/s (2,8 m/s²) bir hızlanma, ardından sabit
    private val harshRamp: (Double) -> Double = { t ->
        when { t < 20 -> 10.0; t < 23 -> 10 + (t - 20) / 3 * 30; else -> 40.0 }
    }

    @Test fun shortInputGivesNoScore() {
        assertNull(analyze(listOf(Sample(0.0, speed = 10.0))).total)
    }

    @Test fun smoothDriveScoresHigh() {
        val r = analyze(build(240, 0.0, { 50.0 }))
        assertTrue("puan ${r.total}", r.total!! >= 90)
        assertTrue(r.events.isEmpty())
    }

    @Test fun harshAccelPenalisedLessOnSteepUphill() {
        val flat = analyze(build(120, 0.0, harshRamp))
        val hill = analyze(build(120, 8.0, harshRamp))
        assertEquals(1, flat.events.count { it.type == "harsh_accel" })
        assertEquals(1, hill.events.count { it.type == "harsh_accel" })
        assertTrue("düz ${flat.subs["Hızlanma"]} yokuş ${hill.subs["Hızlanma"]}",
            hill.subs.getValue("Hızlanma") > flat.subs.getValue("Hızlanma"))
        assertEquals(0.5, hill.events.first { it.type == "harsh_accel" }.weight, 1e-9)
    }

    @Test fun accelerometerMismatchHalvesWeight() {
        val noAcc = analyze(build(120, 0.0, harshRamp))
        val conf = analyze(build(120, 0.0, harshRamp) { s, t -> s.gLong = if (t in 20.0..23.0) 0.29 else 0.0 })
        val unconf = analyze(build(120, 0.0, harshRamp) { s, _ -> s.gLong = 0.0 })
        assertEquals(1.0, noAcc.events.first { it.type == "harsh_accel" }.weight, 1e-9)
        assertEquals(1.0, conf.events.first { it.type == "harsh_accel" }.weight, 1e-9)
        assertEquals(0.5, unconf.events.first { it.type == "harsh_accel" }.weight, 1e-9)
        assertTrue(conf.events.first { it.type == "harsh_accel" }.text.contains("doğrulandı"))
    }

    @Test fun harshBrakeDetected() {
        val brake: (Double) -> Double = { t -> if (t < 20) 80.0 else if (t < 23) 80 - (t - 20) / 3 * 50 else 30.0 }
        val r = analyze(build(120, 0.0, brake))
        assertEquals(1, r.events.count { it.type == "harsh_brake" })
        assertTrue(r.subs.getValue("Frenleme") < 100)
    }

    @Test fun luggingOnSteepUphillIsFlagged() {
        val steady: (Double) -> Double = { 45.0 }
        val mod: (Sample, Double) -> Unit = { s, _ -> s.gear = 4; s.rpm = 1250.0; s.load = 85.0 }
        val up = analyze(build(120, 6.0, steady, mod))
        val flat = analyze(build(120, 0.0, steady, mod))
        assertTrue(up.events.any { it.type == "lug_uphill" })
        assertFalse(flat.events.any { it.type == "lug_uphill" })
        assertTrue(flat.events.any { it.type == "lugging" })
        assertFalse(up.events.any { it.type == "lugging" })       // çift sayım yok
        assertTrue(up.subs.getValue("Eğim yönetimi") < 100)
        assertTrue(coachTips(up).any { it.text.contains("2. vitese") && it.text.contains("tırmanış") })
    }

    @Test fun ridingBrakesOnLongDescentIsFlagged() {
        // -5 % eğim, ~2 km: 60 -> 40 km/s testere dişi (sürekli fren)
        val saw: (Double) -> Double = { t -> 60 - ((t % 8) / 8) * 20 }
        val r = analyze(build(220, -5.0, saw))
        val e = r.events.firstOrNull { it.type == "descent_brake" }
        assertNotNull(e)
        assertTrue(e!!.lenKm > 0.5)
        assertTrue(r.subs.getValue("Eğim yönetimi") <= 85)
        val tip = coachTips(r).first { it.text.contains("motor freni") }
        assertTrue(tip.text.contains("Uzun inişlerde"))
    }

    @Test fun steadyDescentWithEngineBrakingNotFlagged() {
        val r = analyze(build(220, -5.0, { 50.0 }))
        assertFalse(r.events.any { it.type == "descent_brake" })
        assertTrue(r.metrics.longestDescentKm > 0.5)
    }

    @Test fun harshCorneringDetected() {
        val r = analyze(build(120, 0.0, { 40.0 }) { s, t -> s.gLat = if (t in 50.0..52.0) 0.42 else 0.05 })
        assertEquals(1, r.events.count { it.type == "harsh_corner" })
        assertTrue(r.subs.getValue("Viraj") < 100)
        val mild = analyze(build(120, 0.0, { 40.0 }) { s, _ -> s.gLat = 0.30 })
        assertFalse(mild.events.any { it.type == "harsh_corner" })
    }

    @Test fun potholeSpikeIsPotholeNotCorner() {
        // tek örneklik büyük yanal sıçrama + büyük dikey darbe (çukur), düz yol (yön sabit)
        val r = analyze(build(120, 0.0, { 40.0 }) { s, t ->
            s.bearing = 90.0
            s.gVert = if (t == 50.0) 1.1 else 0.12
            s.gLat = if (t == 50.0) 0.62 else 0.04
        })
        assertFalse(r.events.any { it.type == "harsh_corner" })
        val p = r.events.filter { it.type == "pothole" }
        assertEquals(1, p.size)
        assertEquals("Çukur / bozuk yol", p[0].label)
        assertEquals(1.1, p[0].value, 1e-9)
        assertEquals(50.0, p[0].t, 1e-9)
        assertEquals(100, r.subs.getValue("Viraj"))
        assertEquals(analyze(build(120, 0.0, { 40.0 })).total, r.total)     // yol durumu puanı düşürmez
        assertEquals(1.1, r.metrics.maxVertG, 1e-9)
        assertTrue(coachTips(r).any { "çukur" in it.text })
    }

    @Test fun singleLateralSpikeWithoutJoltIsNotCorner() {
        val r = analyze(build(120, 0.0, { 40.0 }) { s, t -> s.gLat = if (t == 50.0) 0.5 else 0.05 })
        assertFalse(r.events.any { it.type == "harsh_corner" })
        assertFalse(r.events.any { it.type == "pothole" })   // gVert yok: çukur da yok
    }

    @Test fun joltsAlternatingWithLateralDoNotMergeIntoCorner() {
        // darbe sızıntısı tek tek örneklerde; aradaki maskeli örnekler birleştirmeyle viraj yapmamalı
        val r = analyze(build(120, 0.0, { 40.0 }) { s, t ->
            val hit = t == 50.0 || t == 51.0 || t == 52.0
            s.gVert = if (hit) 0.9 else 0.1
            s.gLat = if (hit) 0.5 else if (t == 50.5 || t == 51.5) 0.38 else 0.05
        })
        assertFalse(r.events.any { it.type == "harsh_corner" })
        assertEquals(1, r.events.count { it.type == "pothole" })      // 1 sn arayla darbeler birleşir
    }

    @Test fun sustainedLateralWithTurningIsCorner() {
        // 40 km/s'de 4 sn boyunca 0,42 g ve saniyede ~20° dönüş (merkezkaç ile tutarlı)
        val r = analyze(build(120, 0.0, { 40.0 }) { s, t ->
            s.bearing = when { t < 50 -> 0.0; t <= 54 -> (t - 50) * 20.0; else -> 80.0 }
            s.gLat = if (t in 50.0..54.0) 0.42 else 0.04
            s.gVert = 0.15
        })
        val c = r.events.filter { it.type == "harsh_corner" }
        assertEquals(1, c.size)
        assertTrue(c[0].text, "dönüş" in c[0].text)
        assertFalse(r.events.any { it.type == "pothole" })
        assertTrue(r.subs.getValue("Viraj") < 100)
    }

    @Test fun sustainedLateralWithoutHeadingChangeIsNotCorner() {
        // GPS yönü sabitken yanal ivme (ör. yanlış yönelim/titreşim) viraj sayılmaz
        val r = analyze(build(120, 0.0, { 40.0 }) { s, t ->
            s.bearing = 90.0 + if (t.toInt() % 2 == 0) 1.0 else -1.0
            s.gLat = if (t in 50.0..54.0) 0.42 else 0.04
        })
        assertFalse(r.events.any { it.type == "harsh_corner" })
    }

    @Test fun cornerOnRoughRoadStillDetected() {
        // bozuk yolda viraj: dikey titreşim yüksek ama yanal ivmeden küçük -> maskelenmez
        val r = analyze(build(120, 0.0, { 40.0 }) { s, t ->
            s.bearing = if (t < 50) 0.0 else if (t <= 54) (t - 50) * 20.0 else 80.0
            s.gLat = if (t in 50.0..54.0) 0.45 else 0.04
            s.gVert = if (t in 50.0..54.0) 0.42 else 0.1
        })
        assertEquals(1, r.events.count { it.type == "harsh_corner" })
    }

    @Test fun cornerOnVeryRoughRoadNotMaskedByVerticalPeaks() {
        // parke/bozuk yol: her aralıkta dikey TEPE yanal medyandan büyük (0,6..1,0 g); yalıtılmış darbe değil,
        // gerçek (dönüşlü) viraj maskelenmemeli. Aynı yolda tek bir çukur ise hâlâ viraj sayılmamalı.
        val r = analyze(build(120, 0.0, { 40.0 }) { s, t ->
            s.bearing = if (t < 50) 0.0 else if (t <= 54) (t - 50) * 20.0 else 80.0
            s.gLat = if (t in 50.0..54.0) 0.45 else 0.04
            s.gVert = if ((t * 2).toInt() % 2 == 0) 0.6 else 0.95
        })
        assertEquals(1, r.events.count { it.type == "harsh_corner" })
        val p = analyze(build(120, 0.0, { 40.0 }) { s, t ->
            s.gVert = if (t == 50.0 || t == 50.5) 1.4 else 0.15
            s.gLat = if (t == 50.0 || t == 50.5) 0.5 else 0.04
        })
        assertFalse(p.events.any { it.type == "harsh_corner" })
    }

    @Test fun separatePotholesAndSlowSpeedIgnored() {
        val r = analyze(build(120, 0.0, { t -> if (t < 80) 40.0 else 6.0 }) { s, t ->
            s.gVert = if (t == 30.0 || t == 40.0 || t == 100.0) 0.9 else 0.1
        })
        val p = r.events.filter { it.type == "pothole" }
        assertEquals(2, p.size)                         // 100. sn'deki darbe 6 km/s'de: sayılmaz
        assertEquals(listOf(30.0, 40.0), p.map { it.t })
    }

    @Test fun potholeEventHasReportPosition() {
        val S = build(120, 0.0, { 40.0 }) { s, t -> s.lat = 41.0 + t * 1e-5; s.lon = 29.0; s.gVert = if (t == 60.0) 1.0 else 0.1 }
        val rep = TripReport.build(S)
        val pos = rep.eventPositions.filter { it.event.type == "pothole" }
        assertEquals(1, pos.size)
        assertEquals(41.0 + 60 * 1e-5, pos[0].lat, 1e-9)
        assertTrue(reportHtml(rep).contains("Çukur / bozuk yol"))
    }

    @Test fun terrainMetrics() {
        val r = analyze(build(240, 4.0, { 54.0 }))
        val m = r.metrics
        assertTrue(m.hasAltitude)
        assertTrue("çıkış ${m.ascent}", m.ascent in 100.0..160.0)       // 54 km/s*240 s = 3,6 km * 4 % = 144 m
        assertEquals(4.0, m.maxUpGrade!!, 0.5)
        assertTrue(m.pctUp > 90)
    }

    @Test fun overRevAndColdEngine() {
        val r = analyze(build(120, 0.0, { 50.0 }, { s, t -> if (t in 30.0..33.0) { s.rpm = 3800.0 }; if (t < 10) { s.coolant = 30.0; s.rpm = 3000.0 } }))
        assertTrue(r.events.any { it.type == "over_rev" })
        assertTrue(r.events.any { it.type == "cold_abuse" })
    }
}
