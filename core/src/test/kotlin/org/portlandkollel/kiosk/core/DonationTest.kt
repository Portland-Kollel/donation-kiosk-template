package org.portlandkollel.kiosk.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DonationTest {
    private val policy = DonationPolicy()
    @Test fun parsesExactCents() {
        assertEquals(1800L, policy.parse("18"))
        assertEquals(1801L, policy.parse("18.01"))
        assertEquals(1810L, policy.parse("18.1"))
        assertEquals(100L, policy.parse("1.00"))
        assertEquals(1000000L, policy.parse("10000"))
    }
    @Test fun rejectsInvalidAndOutOfRangeAmounts() {
        listOf("", "0", "0.99", "-18", "1e3", "NaN", "18.001", "10000.01", "99999999", " 18", ".50").forEach {
            assertThrows(IllegalArgumentException::class.java) { policy.parse(it) }
        }
    }
    @Test fun replayReturnsSameResultAndChangedPayloadIsRejected() {
        val provider = SimulatedPaymentProvider()
        val id = UUID.randomUUID().toString()
        val donation = Donation(1800, Purpose.GENERAL)
        val first = provider.submit(id, donation)
        assertEquals(first, provider.submit(id, donation))
        assertEquals(first, provider.lookup(id))
        assertThrows(IllegalArgumentException::class.java) { provider.submit(id, donation.copy(cents = 3600)) }
        assertThrows(IllegalArgumentException::class.java) { provider.submit(id, donation.copy(purpose = Purpose.SEED)) }
    }
    @Test fun rejectsInvalidAttemptsAndAmounts() {
        val provider = SimulatedPaymentProvider()
        assertThrows(IllegalArgumentException::class.java) { provider.submit("", Donation(1800, Purpose.GENERAL)) }
        assertThrows(IllegalArgumentException::class.java) { provider.submit(UUID.randomUUID().toString(), Donation(0, Purpose.GENERAL)) }
        assertNull(provider.lookup(UUID.randomUUID().toString()))
    }
}
