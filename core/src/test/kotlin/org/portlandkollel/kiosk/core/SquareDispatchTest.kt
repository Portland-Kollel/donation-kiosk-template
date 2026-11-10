package org.portlandkollel.kiosk.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SquareDispatchTest {
    private fun dispatch() = SquareDispatch(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1800)
    private class Journal : SquareDispatchJournal {
        var value: SquareDispatch? = null
        override fun pending() = value
        override fun reserve(dispatch: SquareDispatch) { value = dispatch }
    }
    @Test fun refusesReplayAndNewAttemptAfterRestart() {
        val journal = Journal()
        val intent = dispatch()
        SquareDispatchGate(journal).beforeSdkCall(intent)
        assertEquals(intent, journal.pending())
        assertThrows(IllegalStateException::class.java) { SquareDispatchGate(journal).beforeSdkCall(intent) }
        assertThrows(IllegalStateException::class.java) { SquareDispatchGate(journal).beforeSdkCall(dispatch()) }
    }
    @Test fun persistenceFailurePreventsSdkDispatch() {
        val journal = object : SquareDispatchJournal {
            override fun pending(): SquareDispatch? = null
            override fun reserve(dispatch: SquareDispatch) { throw java.io.IOException("synthetic disk failure") }
        }
        assertThrows(java.io.IOException::class.java) { SquareDispatchGate(journal).beforeSdkCall(dispatch()) }
    }
    @Test fun idsAndAmountMustBeValidAndDistinct() {
        val id = UUID.randomUUID().toString()
        assertThrows(IllegalArgumentException::class.java) { SquareDispatch(id, id, 1800) }
        assertThrows(IllegalArgumentException::class.java) { SquareDispatch("invalid", id, 1800) }
        assertThrows(IllegalArgumentException::class.java) { SquareDispatch(id, UUID.randomUUID().toString(), 0) }
    }
}
