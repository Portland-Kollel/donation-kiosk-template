package org.portlandkollel.kiosk.core

import java.util.UUID

/** Donation reference and SDK single-use attempt ID are intentionally different. */
data class SquareDispatch(val referenceId: String, val sdkAttemptId: String, val cents: Long) {
    init {
        require(UUID.fromString(referenceId).toString() == referenceId)
        require(UUID.fromString(sdkAttemptId).toString() == sdkAttemptId)
        require(referenceId != sdkAttemptId)
        DonationPolicy().validate(cents)
    }
}

interface SquareDispatchJournal {
    fun pending(): SquareDispatch?
    /** Must atomically reject an existing reservation, durably commit or throw BEFORE the SDK is invoked. */
    fun reserve(dispatch: SquareDispatch)
}

/** Backend reservation recovery is never permission to call the SDK again. */
class SquareDispatchGate(private val journal: SquareDispatchJournal) {
    @Synchronized fun beforeSdkCall(dispatch: SquareDispatch) {
        check(journal.pending() == null) { "Reconcile the pending attempt before another payment." }
        journal.reserve(dispatch)
    }
    fun pending(): SquareDispatch? = journal.pending()
}
