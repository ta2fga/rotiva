package com.ta2fga.rotiva

import com.ta2fga.rotiva.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun smp(t: Double, v: Double) = Sample(t, rpm = 2000.0, speed = v, load = 30.0, coolant = 85.0, alt = 100 + t, lat = 41.0, lon = 29.0)

    @Test fun autoStartIncrementalSaveResumeAndStop() {
        val store = TripStore(tmp.newFolder("trips"))
        val rec = TripRecorder(store, TripCfg(autosaveS = 5.0, minSamples = 5))
        // duruşta başlamaz
        repeat(5) { assertNull(rec.feed(smp(1000.0 + it, 0.0))) }
        assertFalse(rec.active)
        assertEquals("started", rec.feed(smp(1006.0, 20.0)))
        assertTrue(rec.active)
        for (i in 7..40) rec.feed(smp(1000.0 + i, 30.0))
        // artımlı kayıt yapıldı
        val unf = rec.findUnfinished()
        assertNotNull(unf)
        assertTrue(unf!!.samples.size >= 6)
        assertEquals(41.0, unf.samples.first().lat!!, 1e-6)

        // yeni oturum (çökme sonrası): devam
        val rec2 = TripRecorder(store, TripCfg(minSamples = 5))
        rec2.resumeUnfinished(rec2.findUnfinished()!!)
        assertTrue(rec2.paused)
        assertEquals("resumed", rec2.feed(smp(1100.0, 40.0)))
        assertEquals(1, rec2.pauses.size)
        for (i in 1..20) rec2.feed(smp(1100.0 + i, 40.0))
        val sum = rec2.stop()
        assertNotNull(sum)
        assertNull(rec2.findUnfinished())
        assertEquals(1, store.list().size)
        val id = sum!!.getString("id")
        assertTrue(store.loadSamples(id).size > 30)
    }

    @Test fun pauseWhenStalledAndNoAutoStartWhenDisabled() {
        val store = TripStore(tmp.newFolder("t2"))
        val rec = TripRecorder(store, TripCfg(autoStart = false))
        rec.feed(smp(0.0, 50.0))
        assertFalse(rec.active)
        rec.start(1.0)
        rec.feed(smp(2.0, 50.0))
        assertTrue(rec.stalled(30.0))
        rec.pause(30.0)
        assertTrue(rec.paused && rec.active)       // sürüş bitmez, yalnız duraklar
    }

    @Test fun csvRoundTrip() {
        val s = smp(1234.5, 42.0).apply { gear = 3; gLong = 0.12; gLat = -0.2; bearing = 90.0; baroAlt = 101.5; frp = 450.0 }
        val cols = sampleToCsv(s).split(",")
        val back = sampleFromCsv(cols, FIELDS.withIndex().associate { it.value to it.index })!!
        assertEquals(3, back.gear); assertEquals(0.12, back.gLong!!, 1e-4); assertEquals(450.0, back.frp!!, 0.1)
        assertEquals(1234.5, back.t, 0.01)
    }

    @Test fun csvRoundTripWithVerticalAndOldCsvWithout() {
        val s = smp(1234.5, 42.0).apply { gLat = 0.05; gVert = 0.873 }
        val back = sampleFromCsv(sampleToCsv(s).split(","), FIELDS.withIndex().associate { it.value to it.index })!!
        assertEquals(0.873, back.gVert!!, 1e-3)
        assertEquals(0.05, back.gLat!!, 1e-4)
        assertEquals(FIELDS.size, sampleToCsv(s).split(",").size)

        // eski kayıt: gVert sütunu yok
        val old = tmp.newFile("old.csv")
        old.writeText("t,rpm,speed,load,coolant,map,maf,frp,gear,lat,lon,gpsSpeed,bearing,alt,acc,baroAlt,gLong,gLat\n" +
            "1000.00,2000.0,40.00,30.0,85.0,,,,3,41.0000000,29.0000000,40.00,90.0,100.00,4.0,,0.0100,0.4000\n" +
            "1000.50,2000.0,40.00,30.0,85.0,,,,3,41.0000100,29.0000000,40.00,90.0,100.00,4.0,,,\n")
        val rows = readSamples(old)
        assertEquals(2, rows.size)
        assertNull(rows[0].gVert)
        assertEquals(0.4, rows[0].gLat!!, 1e-9)
        assertNull(rows[1].gLat)

        // yeni kayıt dosyası: başlıkta gVert var ve geri okunur
        val store = TripStore(tmp.newFolder("t2"))
        store.csv("x").writeText(FIELDS.joinToString(",") + "\n" + sampleToCsv(s) + "\n")
        assertEquals(0.873, store.loadSamples("x")[0].gVert!!, 1e-3)
    }

    @Test fun reportBuildsAndHtmlContainsSections() {
        val S = (0..400).map { Sample(1000.0 + it * 0.5, speed = 50.0, rpm = 2000.0, gear = 4, alt = 100 + it * 0.1, lat = 41.0 + it * 1e-4, lon = 29.0) }
        val r = TripReport.build(S)
        val html = reportHtml(r)
        assertTrue(html.contains("Toplam çıkış"))
        assertTrue(html.contains("<svg"))
        assertTrue(r.altProfile.size > 10)
    }
}
