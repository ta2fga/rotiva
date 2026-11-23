package com.ta2fga.rotiva

import com.ta2fga.rotiva.service.Conn
import com.ta2fga.rotiva.service.LinkState
import com.ta2fga.rotiva.service.Mode
import com.ta2fga.rotiva.service.TripUi
import com.ta2fga.rotiva.service.notificationText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    @Test fun recordingTextOnlyWhileTripActive() {
        val c = Conn(Mode.OBD, LinkState.CONNECTED, "ELM327 v2.1")
        assertTrue(notificationText(TripUi(active = true, distanceKm = 1.5), c).contains("kaydediliyor"))
        val after = notificationText(TripUi(), c)
        assertFalse(after.contains("kayd", ignoreCase = true))
        assertTrue(after.startsWith(Mode.OBD.label))
    }
}
