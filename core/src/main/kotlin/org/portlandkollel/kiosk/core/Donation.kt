package org.portlandkollel.kiosk.core

import java.util.UUID

enum class Purpose(val label: String) {
    GENERAL("General Donation"), LEARNING("Kollel Learning"), SEED("Camp SEED"), PARNAS("Parnas HaYom")
}

data class Donation(val cents: Long, val purpose: Purpose)

/** Integer cents only; never parse money through floating point. Server must repeat validation. */
class DonationPolicy(val minimum: Long = 100, val maximum: Long = 1_000_000) {
    init { require(minimum > 0 && maximum >= minimum) }
    fun validate(cents: Long) { require(cents in minimum..maximum) { "Amount must be within the configured limits." } }
    fun parse(text: String): Long {
        require(Regex("[0-9]{1,7}(\\.[0-9]{1,2})?").matches(text)) { "Enter dollars with at most two decimal places." }
        val parts = text.split('.')
        val cents = parts[0].toLong() * 100 + parts.getOrElse(1) { "" }.padEnd(2, '0').toLong()
        validate(cents)
        return cents
    }
}

enum class PaymentStatus { SIMULATED_APPROVED, DECLINED, CANCELED, UNCERTAIN }
data class PaymentResult(val attemptId: String, val status: PaymentStatus)

/** An adapter must never treat a timeout as permission to start another payment. */
interface PaymentProvider {
    fun submit(attemptId: String, donation: Donation): PaymentResult
    fun lookup(attemptId: String): PaymentResult?
}

/** Offline fake only. No Square SDK, credentials, network, or real money. */
class SimulatedPaymentProvider(private val policy: DonationPolicy = DonationPolicy()) : PaymentProvider {
    private val attempts = mutableMapOf<String, Pair<Donation, PaymentResult>>()
    @Synchronized override fun submit(attemptId: String, donation: Donation): PaymentResult {
        require(runCatching { UUID.fromString(attemptId).toString() == attemptId }.getOrDefault(false))
        policy.validate(donation.cents)
        attempts[attemptId]?.let {
            require(it.first == donation) { "Attempt cannot be reused for another donation." }
            return it.second
        }
        return PaymentResult(attemptId, PaymentStatus.SIMULATED_APPROVED).also {
            attempts[attemptId] = donation to it
        }
    }
    @Synchronized override fun lookup(attemptId: String): PaymentResult? = attempts[attemptId]?.second
}

/** Future authenticated HTTPS backend boundary; no network implementation in Batch 1. */
interface DonationBackend {
    suspend fun createAttempt(donation: Donation): String
    suspend fun verifyAttempt(attemptId: String): PaymentResult
}
