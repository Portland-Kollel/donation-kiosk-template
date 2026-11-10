package org.portlandkollel.kiosk.core

/**
 * Donor fields are private application data, never payment credentials or log text. Nothing is required:
 * a donor may give without details; a year-end tax receipt needs name, email, cell and address.
 * Anonymous gifts carry no identity or contact fields at all.
 */
data class GivingDetails(val name: String, val email: String = "", val honor: String = "", val anonymous: Boolean = false, val thankYou: Boolean = false,
    val firstName: String = "", val lastName: String = "", val phone: String = "", val street: String = "", val city: String = "", val state: String = "", val zip: String = "") {
    fun normalized():GivingDetails {
        if (anonymous) return GivingDetails("", honor = honor.trim(), anonymous = true)
        val first = firstName.trim(); val last = lastName.trim()
        val full = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { name.trim() }
        return copy(name = full, firstName = first, lastName = last, email = email.trim(), honor = honor.trim(), phone = phone.trim(),
            street = street.trim(), city = city.trim(), state = state.trim(), zip = zip.trim(), thankYou = thankYou && email.trim().isNotEmpty())
    }
    fun validate() {
        require(!thankYou || anonymous || email.trim().isNotEmpty()) { "Enter an email address for a thank-you email." }
        val value = normalized()
        fun plain(text: String, max: Int) = text.length <= max && text.none { it.isISOControl() }
        require(plain(value.name, 100) && plain(value.firstName, 60) && plain(value.lastName, 60)) { "Please check your name." }
        require(value.phone.isEmpty() || Regex("[0-9+()\\-. ]{7,25}").matches(value.phone)) { "Please check your cell phone number." }
        require(plain(value.street, 200) && plain(value.city, 80) && plain(value.state, 40) && plain(value.zip, 12)) { "Please check your address." }
        require(value.email.length <= 200 && (value.email.isEmpty() ||
            Regex("[A-Za-z0-9.!#\u0024%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+").matches(value.email))) { "Please check your email address." }
        require(value.honor.length <= 500 && value.honor.none { it.isISOControl() && it != '\n' }) { "Please shorten the dedication to 500 characters." }
    }
    override fun toString() = "GivingDetails(redacted)"
}

enum class KioskMode { SANDBOX, READER_SETUP }

/**
 * Compiled gate: production (READER_SETUP) can authorize/pair, but can invoke a live
 * payment only in an organization-signed live release built with REAL_CHARGES_ENABLED.
 */
/**
 * Optional donor-covered card fee, grossed up so the Kollel receives the full gift after the card
 * processor's percentage-plus-fixed fee. Integer cents and basis points only.
 */
data class FeePolicy(val basisPoints: Int = 260, val fixedCents: Long = 15) {
    init { require(basisPoints in 0..1000 && fixedCents in 0..100) }
    fun coverFor(giftCents: Long): Long {
        require(giftCents > 0)
        val denominator = 10_000L - basisPoints
        val total = ((giftCents + fixedCents) * 10_000L + denominator - 1) / denominator
        return total - giftCents
    }
}

object GivingSafety {
    fun requirePayment(mode: KioskMode, realChargesEnabled: Boolean) {
        check(mode == KioskMode.SANDBOX || realChargesEnabled) { "Real donations are disabled in this build." }
    }
    fun formatCents(cents: Long) = "\u0024${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
