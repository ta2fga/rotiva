package com.ta2fga.rotiva

import com.ta2fga.rotiva.core.*
import org.junit.Assert.*
import org.junit.Test

class ElmTest {
    @Test fun cleanLinesDropsEchoPromptAndBlank() {
        val l = cleanLines("010C\r41 0C 1A F8 \r\r>", "010C")
        assertEquals(listOf("41 0C 1A F8"), l)
    }

    @Test fun parseSingleFrameCan() {
        val f = parseFrames(listOf("7E8 04 41 0C 1A F8"), 3)
        assertEquals(1, f.size)
        assertEquals("7E8", f[0].first)
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), f[0].second)
    }

    @Test fun parseMultiFrameIsoTpVin() {
        val lines = listOf(
            "7E8 10 14 49 02 01 56 46 37",
            "7E8 21 4E 43 39 48 4A 43 31",
            "7E8 22 32 33 34 35 36 37 38",
        )
        val f = parseFrames(lines, 3)
        assertEquals(1, f.size)
        val d = f[0].second
        assertEquals(0x14, d.size)
        val vin = String(ByteArray(17) { d[3 + it].toByte() }, Charsets.US_ASCII)
        assertEquals("VF7NC9HJC12345678", vin)
    }

    @Test fun decodeRpmAndRailPressure() {
        val rpm = BY_PID.getValue(0x0C).decode(intArrayOf(0x1A, 0xF8))
        assertEquals(1726.0, rpm, 0.01)
        // 0x23: ham 2000 (=200 bar) -> bar olarak
        val frp = BY_PID.getValue(0x23).decode(intArrayOf(0x07, 0xD0))
        assertEquals(200.0, frp, 0.01)
        assertEquals(60.0, BY_PID.getValue(0x05).decode(intArrayOf(100)), 0.01)
    }

    @Test fun dtcDecoding() {
        assertEquals("P0420", decodeDtc(0x04, 0x20))
        assertEquals("C0123", decodeDtc(0x41, 0x23))
        assertEquals("U0100", decodeDtc(0xC1, 0x00))
        assertEquals(listOf("P0087", "P2002"), decodeDtcList(intArrayOf(2, 0x00, 0x87, 0x20, 0x02), true))
    }

    @Test fun errorsAreRaised() {
        try { raiseIfError(listOf("NO DATA")); fail() } catch (_: NoData) {}
        try { raiseIfError(listOf("UNABLE TO CONNECT")); fail() } catch (e: ElmError) { assertTrue(e !is NoData) }
        try { extractService(listOf("7E8 03 7F 01 12"), 1, 0x0C, 3); fail() } catch (e: NegativeResponse) { assertEquals(0x12, e.nrc) }
    }

    @Test fun supportedMasks() {
        val s = supportedFromMasks(mapOf(0 to intArrayOf(0x98, 0x3B, 0xA0, 0x11)))
        assertTrue(0x04 in s && 0x05 in s && 0x0C in s && 0x0D in s)
        assertFalse(0x06 in s)
        assertTrue(0x20 in s)
    }

    @Test fun headerLengths() {
        assertEquals(3, headerLenFor("6")); assertEquals(8, headerLenFor("A7")); assertEquals(6, headerLenFor("3"))
    }

    @Test fun fullClientAgainstSimulator() {
        val elm = Elm327(SimTransport(latencyMs = 0))
        val info = elm.connect()
        assertTrue(info.ecuOk)
        assertTrue(0x0C in elm.supported)
        val rpm = elm.readPid(BY_PID.getValue(0x0C))!!
        assertTrue(rpm in 700.0..900.0)
        assertEquals(listOf("P0087", "P2002"), elm.readDtcs(0x03))
        assertEquals(listOf("P0299"), elm.readDtcs(0x07))
        assertTrue(elm.clearDtcs())
        assertTrue(elm.readDtcs(0x03).isEmpty())
        elm.disconnect()
    }
}
