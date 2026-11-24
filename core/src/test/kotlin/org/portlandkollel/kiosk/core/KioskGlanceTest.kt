package org.portlandkollel.kiosk.core

import org.junit.Assert.*
import org.junit.Test

class KioskGlanceTest {
    private val min = 60_000L

    @Test fun readyReaderIsNeverAFault() {
        val watch = ReaderWatch()
        assertEquals(ReaderWatch.Step(ReaderHealth.READY, false), watch.observe(0, true))
        assertEquals(ReaderWatch.Step(ReaderHealth.READY, false), watch.observe(60 * min, true))
    }

    @Test fun briefProblemIsOnlySettlingAndNotRetried() {
        val watch = ReaderWatch()
        assertEquals(ReaderWatch.Step(ReaderHealth.SETTLING, false), watch.observe(0, false))
        assertEquals(ReaderWatch.Step(ReaderHealth.SETTLING, false), watch.observe(2 * min, false))
        assertEquals(ReaderWatch.Step(ReaderHealth.READY, false), watch.observe(2 * min + 1, true))
    }

    @Test fun reconnectsOnceBeforeReportingAFault() {
        val watch = ReaderWatch()
        watch.observe(0, false)
        assertEquals(ReaderWatch.Step(ReaderHealth.SETTLING, true), watch.observe(3 * min, false))
        // Not reported yet: the reconnect gets a minute to work.
        assertEquals(ReaderWatch.Step(ReaderHealth.SETTLING, false), watch.observe(3 * min + 30_000, false))
        assertEquals(ReaderWatch.Step(ReaderHealth.FAULT, false), watch.observe(4 * min, false))
        // Keeps trying every 10 minutes while the fault lasts.
        assertEquals(ReaderWatch.Step(ReaderHealth.FAULT, false), watch.observe(12 * min, false))
        assertEquals(ReaderWatch.Step(ReaderHealth.FAULT, true), watch.observe(13 * min, false))
        assertEquals(ReaderWatch.Step(ReaderHealth.FAULT, false), watch.observe(14 * min, false))
    }

    @Test fun recoveryResetsTheClock() {
        val watch = ReaderWatch()
        watch.observe(0, false); watch.observe(3 * min, false); watch.observe(4 * min, false)
        watch.observe(5 * min, true)
        assertNull(watch.notReadySince); assertNull(watch.lastReconnect)
        assertEquals(ReaderWatch.Step(ReaderHealth.SETTLING, false), watch.observe(6 * min, false))
    }

    @Test fun glanceLevels() {
        assertEquals(KioskGlance.Level.OK, KioskGlance.of(ReaderHealth.READY, "", emptyList()).level)
        assertEquals(KioskGlance.Level.OK, KioskGlance.of(null, "", emptyList()).level)
        val down = KioskGlance.of(ReaderHealth.FAULT, "Card reader not connected", listOf("Email problem"))
        assertEquals(KioskGlance(KioskGlance.Level.READER_DOWN, "Card reader not connected"), down)
        assertEquals(KioskGlance(KioskGlance.Level.NOTE, "Card reader connecting…"), KioskGlance.of(ReaderHealth.SETTLING, "x", emptyList()))
        assertEquals(KioskGlance(KioskGlance.Level.NOTE, "Sheet/email: Google sign-in needed (+1 more)"),
            KioskGlance.of(ReaderHealth.READY, "", listOf("Sheet/email: Google sign-in needed", "Tablet not plugged in")))
    }
}
