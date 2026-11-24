package com.ta2fga.rotiva

import com.ta2fga.rotiva.core.LABELS
import com.ta2fga.rotiva.ui.*
import org.junit.Assert.*
import org.junit.Test

class RouteMapTest {
    @Test fun legendMatchesRouteColors() {
        val speeds = listOf(10.0, 45.0, 75.0, 100.0, 150.0)
        assertEquals(speeds.map { routeColorOf(RouteColor.SPEED, it, null) }, legendFor(RouteColor.SPEED).map { it.second })
        val grades = listOf(-10.0, -4.0, 0.0, 4.0, 10.0)
        assertEquals(grades.map { routeColorOf(RouteColor.GRADE, null, it) }, legendFor(RouteColor.GRADE).map { it.second })
        assertTrue(legendFor(RouteColor.PLAIN).isEmpty())
    }

    @Test fun paletteBucketsAreDistinct() {
        assertEquals(5, legendFor(RouteColor.SPEED).map { it.second }.toSet().size)
        assertEquals(5, legendFor(RouteColor.GRADE).map { it.second }.toSet().size)
    }

    @Test fun everyScoringEventHasAStyle() {
        (LABELS.keys + "pothole").forEach { assertTrue("stil yok: $it", EVENT_STYLE.containsKey(it)) }
        val pot = eventStyleFor("pothole", null)
        assertEquals("Çukur", pot.label)
        assertEquals(MarkerShape.SQUARE, pot.shape)
    }

    @Test fun unknownEventFallsBackInsteadOfDropping() {
        val st = eventStyleFor("yeni_olay", "Yeni olay")
        assertEquals("Yeni olay", st.label)
        assertEquals(FALLBACK_EVENT_COLOR, st.color)
        assertEquals("yeni_olay", eventStyleFor("yeni_olay", "").label)
    }
}
