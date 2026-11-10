package org.portlandkollel.kiosk.core

import org.junit.Assert.*
import org.junit.Test

class GivingDetailsTest {
    @Test fun detailsAndHonorAreValidatedWithoutLoggingTheirValues() {
        val details = GivingDetails(" Example Donor ", "donor@example.org", " In honor of a teacher ").normalized()
        details.validate()
        assertEquals("Example Donor", details.name)
        assertEquals("In honor of a teacher", details.honor)
        assertFalse(details.toString().contains("donor@example.org"))
        assertThrows(IllegalArgumentException::class.java) { GivingDetails("", "bad email").validate() }
        assertThrows(IllegalArgumentException::class.java) { GivingDetails("Example", honor = "x".repeat(501)).validate() }
        GivingDetails("", anonymous = true).validate()
    }
    @Test fun productionChargesOnlyInLiveEnabledBuild() {
        assertThrows(IllegalStateException::class.java) { GivingSafety.requirePayment(KioskMode.READER_SETUP, false) }
        GivingSafety.requirePayment(KioskMode.READER_SETUP, true)
        GivingSafety.requirePayment(KioskMode.SANDBOX, false)
        assertEquals("\u00241.05", GivingSafety.formatCents(105))
    }
    @Test fun nothingIsRequiredAndAnonymousDropsIdentity() {
        GivingDetails("").validate()
        val full = GivingDetails("", "donor@example.org", firstName = " Sarah ", lastName = "Cohen", phone = "(503) 555-0142",
            street = "1 Main St", city = "Portland", state = "OR", zip = "97214").normalized()
        assertEquals("Sarah Cohen", full.name);assertEquals("Sarah", full.firstName);full.validate()
        val anon = GivingDetails("", "donor@example.org", "In memory", anonymous = true, firstName = "Sarah", phone = "5035550142").normalized()
        assertEquals("", anon.firstName);assertEquals("", anon.email);assertEquals("", anon.phone);assertEquals("In memory", anon.honor)
        assertThrows(IllegalArgumentException::class.java) { GivingDetails("", phone = "call me").validate() }
    }
    @Test fun coveredFeeLeavesTheFullGiftAfterSquaresFee() {
        val fee = FeePolicy(260, 15)
        // total = (gift + $0.15) / 0.974, rounded up to the cent; the Kollel nets exactly the gift.
        assertEquals(19L, fee.coverFor(100));assertEquals(160L, fee.coverFor(5400));assertEquals(496L, fee.coverFor(18000))
        for (gift in listOf(100L, 1800L, 3600L, 18000L, 1_000_000L)) {
            val total = gift + fee.coverFor(gift)
            val squareTakes = (total * 260 + 9_999) / 10_000 + 15 // worst case: Square rounds its fee up
            assertTrue(total - squareTakes >= gift - 1)
        }
        assertEquals(0L, FeePolicy(0, 0).coverFor(500))
    }
    @Test fun fractionalAmountsUseIntegerCents() {
        assertEquals(1850L, DonationPolicy().parse("18.50"))
        assertEquals(105L, DonationPolicy().parse("1.05"))
        assertThrows(IllegalArgumentException::class.java) { DonationPolicy().parse("18.501") }
    }
}
