package org.portlandkollel.kiosk.core

/** How the card reader looks over time: fine, briefly not ready (being given a chance), or a real fault. */
enum class ReaderHealth { READY, SETTLING, FAULT }

/**
 * Decides when a card reader that is not Ready gets an automatic reconnect, and when it is reported as a fault.
 * A reader that is not Ready (including one stuck on "Connecting…") is left alone for [retryAfterMs]; then the app
 * reconnects it once by itself. Only if it is still not Ready [faultAfterRetryMs] later is it a fault. While the
 * fault lasts, the app tries again every [retryEveryMs]. Never touches a payment in progress (the caller checks).
 */
class ReaderWatch(
    private val retryAfterMs: Long = 3 * 60_000L,
    private val faultAfterRetryMs: Long = 60_000L,
    private val retryEveryMs: Long = 10 * 60_000L,
) {
    data class Step(val health: ReaderHealth, val reconnectNow: Boolean)
    var notReadySince: Long? = null; private set
    var lastReconnect: Long? = null; private set

    fun observe(now: Long, ready: Boolean): Step {
        if (ready) { notReadySince = null; lastReconnect = null; return Step(ReaderHealth.READY, false) }
        val since = notReadySince ?: now.also { notReadySince = it }
        if (now - since < retryAfterMs) return Step(ReaderHealth.SETTLING, false)
        val last = lastReconnect ?: run { lastReconnect = now; return Step(ReaderHealth.SETTLING, true) }
        if (now - last < faultAfterRetryMs) return Step(ReaderHealth.SETTLING, false)
        if (now - last >= retryEveryMs) { lastReconnect = now; return Step(ReaderHealth.FAULT, true) }
        return Step(ReaderHealth.FAULT, false)
    }
}

/**
 * What the always-visible indicator on the donor screen shows.
 * OK: a small green dot. NOTE: small, quiet text donors can ignore (Sheet/email/power).
 * READER_DOWN: a clear banner, because nobody can give by card.
 */
data class KioskGlance(val level: Level, val text: String) {
    enum class Level { OK, NOTE, READER_DOWN }

    companion object {
        /**
         * [readerHealth] null means the reader is not checked (Sandbox test mode uses a simulated reader).
         * [readerReason] is the plain reason when the reader is not Ready, e.g. "Card reader not connected".
         * [notes] are the smaller problems (Sheet, email, power) in plain words, most important first.
         */
        fun of(readerHealth: ReaderHealth?, readerReason: String, notes: List<String>): KioskGlance = when {
            readerHealth == ReaderHealth.FAULT -> KioskGlance(Level.READER_DOWN, readerReason)
            readerHealth == ReaderHealth.SETTLING -> KioskGlance(Level.NOTE, "Card reader connecting…")
            notes.isNotEmpty() -> KioskGlance(Level.NOTE, notes.first() + if (notes.size > 1) " (+${notes.size - 1} more)" else "")
            else -> KioskGlance(Level.OK, "All systems working")
        }
    }
}
