package org.portlandkollel.kiosk.core
import org.junit.Assert.*
import org.junit.Test
class SquarePaymentProofTest {
    private val attempt=SquareDispatch("00000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000002",100)
    private val proof=SquarePaymentProof("synthetic-payment",attempt.referenceId,"synthetic-location",100,"USD","COMPLETED","CARD")
    @Test fun onlyOriginalSquarePaymentCanConfirmDonation() {
        assertEquals("COMPLETED",proof.confirm(attempt,"synthetic-location"))
        for(value in listOf(proof.copy(cents=101),proof.copy(currency="CAD"),proof.copy(reference="other"),proof.copy(location="other"),proof.copy(source="CASH"))) {
            assertThrows(IllegalStateException::class.java){value.confirm(attempt,"synthetic-location")}
        }
        assertEquals("PENDING",proof.copy(status="PENDING").confirm(attempt,"synthetic-location"))
    }
    @Test fun thankYouRequiresValidEmailAndNeverLogsDetails() {
        assertThrows(IllegalArgumentException::class.java){GivingDetails("Fixture",thankYou=true).validate()}
        GivingDetails("Fixture","fixture@example.invalid",thankYou=true).validate()
        assertFalse(GivingDetails("Fixture","fixture@example.invalid",thankYou=true).toString().contains("fixture@"))
    }
}
