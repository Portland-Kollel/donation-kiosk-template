package org.portlandkollel.kiosk

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject
import org.portlandkollel.kiosk.core.GivingSafety
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

/** One line of the staff status panel or self-test. */
internal data class StaffCheck(val label:String,val level:Level,val detail:String) {
    enum class Level {OK,WARN,FAIL}
}

/** Read-only health checks shared by the staff menu, the self-test and the weekly status email. Never charges. */
internal object KioskHealth {
    val ZONE:ZoneId=ZoneId.of("America/Los_Angeles")
    /** Weekly status email goes out on the first maintenance run on or after Monday 9:00 Pacific. */
    private const val SEND_HOUR=9

    data class Power(val percent:Int,val charging:Boolean)
    fun power(context:Context):Power? {
        val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?:return null
        val level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);val scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1)
        if(level<0||scale<=0)return null
        return Power(level*100/scale,battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0)
    }
    fun readerReady(status:String)=status.substringAfter("Reader:").trim().equals("Ready",ignoreCase=true)
    /** Normal for a minute after the app starts or the reader wakes up; not a fault on its own. */
    fun readerBusy(status:String)=status.substringAfter("Reader:").trim().let {it.startsWith("Connecting",true)||it.startsWith("Updating",true)}

    data class Pending(val sheetRows:Int,val emails:Int,val review:Int) {val total get()=sheetRows+emails}
    fun pending(records:List<JSONObject>):Pending {
        // Same rule as the Google sync: only records that have a Sheet row are ever synced. Older records without
        // one (from early testing) are not waiting for anything, so they are not counted.
        val done=records.filter {it.getString("status") in TabletLedger.TERMINAL && it.optBoolean("square_verified") && it.has("sheet_row")}
        return Pending(done.count {!it.optBoolean("sheet_done")},
            done.count {it.optString("email_status") in setOf("PENDING","SENDING") || it.optString("staff_notice") in setOf("PENDING","SENDING")},
            done.count {it.optString("email_status")=="NEEDS_STAFF_REVIEW" || it.optString("staff_notice")=="NEEDS_STAFF_REVIEW"})
    }

    /** Status checks that need no network: lock-down, Square, reader, Google, sync backlog, power, weekly email. */
    fun local(context:Context,app:KollelApplication,authorized:Boolean,readers:List<String>):List<StaffCheck> {
        val root=app.vault.read();val env=app.config.optString("environment","sandbox")
        val list=mutableListOf<StaffCheck>()
        list+=if(KioskLockdown.managed(context))StaffCheck("Kiosk lock",StaffCheck.Level.OK,"On — the tablet is locked to the kiosk.")
            else StaffCheck("Kiosk lock",StaffCheck.Level.FAIL,"Off — see the handoff How-to: Restore kiosk lock-down.")
        list+=when {
            env=="sandbox" -> StaffCheck("Mode",StaffCheck.Level.WARN,"Sandbox test mode — no real money.")
            BuildConfig.REAL_CHARGES_ENABLED -> StaffCheck("Mode",StaffCheck.Level.OK,"Live — real card payments.")
            else -> StaffCheck("Mode",StaffCheck.Level.WARN,"Reader setup — real donations are off in this build.")
        }
        val squareError=root.optString("square_sync_error")
        list+=when {
            !app.session.hasCredentials() -> StaffCheck("Square",StaffCheck.Level.FAIL,"Not signed in. Tap Sign in to Square.")
            squareError.isNotEmpty() -> StaffCheck("Square",StaffCheck.Level.FAIL,"Sign-in problem ($squareError). Tap Sign in to Square.")
            !authorized -> StaffCheck("Square",StaffCheck.Level.WARN,"Signed in, payments not connected yet. Tap Reconnect Square.")
            else -> StaffCheck("Square",StaffCheck.Level.OK,"Signed in and ready for payments.")
        }
        val ready=readers.any(::readerReady)
        list+=when {
            !authorized -> StaffCheck("Card reader",StaffCheck.Level.WARN,"Unknown until Square is connected.")
            readers.isEmpty() -> StaffCheck("Card reader",if(env=="sandbox")StaffCheck.Level.WARN else StaffCheck.Level.FAIL,"No reader found. Charge it, then Pair a reader.")
            ready -> StaffCheck("Card reader",StaffCheck.Level.OK,"Ready.")
            readers.all(::readerBusy) -> StaffCheck("Card reader",StaffCheck.Level.WARN,readers.joinToString(", ")+". Usually ready within a minute.")
            else -> StaffCheck("Card reader",StaffCheck.Level.FAIL,readers.joinToString(", ")+". Try Reconnect reader.")
        }
        val googleError=root.optString("google_sync_error")
        list+=when {
            !root.has("google_account") -> StaffCheck("Google",StaffCheck.Level.FAIL,"No Google account set. Open Settings.")
            !root.optBoolean("google_enabled") -> StaffCheck("Google",StaffCheck.Level.WARN,"Not connected. Tap Connect Google.")
            googleError.isNotEmpty() -> StaffCheck("Google",StaffCheck.Level.FAIL,"Problem: $googleError. Tap Connect Google.")
            else -> StaffCheck("Google",StaffCheck.Level.OK,"Emails and Sheet ready (${root.getString("google_account")}).")
        }
        val pending=pending(app.ledger.records())
        list+=when {
            pending.review>0 -> StaffCheck("Waiting to sync",StaffCheck.Level.WARN,"${pending.review} email(s) need a manual check (marked in the Sheet).")
            pending.total>0 -> StaffCheck("Waiting to sync",StaffCheck.Level.WARN,"${pending.sheetRows} Sheet row(s), ${pending.emails} email(s). Sends automatically.")
            else -> StaffCheck("Waiting to sync",StaffCheck.Level.OK,"Nothing waiting.")
        }
        power(context)?.let {list+=StaffCheck("Tablet power",if(it.charging)StaffCheck.Level.OK else StaffCheck.Level.WARN,"${it.percent}% · "+if(it.charging)"plugged in" else "NOT plugged in")}
        list+=if(!root.optBoolean("weekly_status",true))StaffCheck("Weekly status email",StaffCheck.Level.WARN,"Turned off in Settings.")
            else StaffCheck("Weekly status email",StaffCheck.Level.OK,root.optString("weekly_status_sent").ifEmpty {"Not sent yet"}.let {if(it.startsWith("Not"))"$it. Sent Mondays after 9 am." else "Last sent $it. Next: Monday after 9 am."})
        return list
    }

    /**
     * Called from the hourly maintenance job. Sends at most one status email per calendar week, on the first
     * run on or after Monday 9:00 Pacific. Failures leave the week unmarked, so the next hourly run retries.
     */
    suspend fun weeklyStatusIfDue(context:Context,app:KollelApplication,now:ZonedDateTime=ZonedDateTime.now(ZONE)) {
        val root=app.vault.read()
        if(!root.optBoolean("weekly_status",true) || !root.has("google_account") || !root.optBoolean("google_enabled"))return
        if(now.dayOfWeek==DayOfWeek.MONDAY && now.hour<SEND_HOUR)return
        val week="${now.get(IsoFields.WEEK_BASED_YEAR)}-W${now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        if(root.optString("weekly_status_week")==week)return
        val google=GoogleDonations(context,app.vault)
        val token=google.token()
        val (subject,text)=weeklyReport(context,app,now)
        google.sendStatus(token,subject,text)
        app.vault.update {it.put("weekly_status_week",week).put("weekly_status_sent",now.format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a",Locale.US)))}
    }

    private fun weeklyReport(context:Context,app:KollelApplication,now:ZonedDateTime):Pair<String,String> {
        val env=app.config.optString("environment","sandbox")
        val since=now.minusDays(7)
        val week=app.ledger.records().filter {it.getString("environment")==env && it.has("confirmed_date") &&
            runCatching {java.time.OffsetDateTime.parse(it.getString("confirmed_date")).toZonedDateTime().isAfter(since)}.getOrDefault(false)}
        val completed=week.filter {it.getString("status")=="COMPLETED"}
        val total=completed.sumOf {it.getLong("cents")}
        val canceled=week.count {it.getString("status")!="COMPLETED"}
        val root=app.vault.read()
        val readers=root.optString("reader_status")
        val checks=mutableListOf<StaffCheck>()
        // The reader is only visible to the app while it is open; report the last status it saw.
        checks+=local(context,app,authorized=true,readers=emptyList()).filter {it.label !in setOf("Card reader","Weekly status email")}
        // A reader that was Ready within the last 2 hours, or is only reconnecting, is not an alarm.
        val lastReady=root.optLong("reader_ready_ms",0L);val recentlyReady=System.currentTimeMillis()-lastReady<2*60*60*1000L
        checks+=when {
            readers.isEmpty() -> StaffCheck("Card reader",StaffCheck.Level.WARN,"No status seen yet. Run the self-test in the staff menu.")
            readers.split(", ").any(::readerReady) -> StaffCheck("Card reader",StaffCheck.Level.OK,"Ready (last checked ${root.optString("reader_status_at")}).")
            recentlyReady || readers.split(", ").all(::readerBusy) -> StaffCheck("Card reader",StaffCheck.Level.WARN,"$readers (last checked ${root.optString("reader_status_at")}). Usually reconnects by itself; run the self-test to confirm.")
            else -> StaffCheck("Card reader",StaffCheck.Level.FAIL,"$readers (last checked ${root.optString("reader_status_at")}). Check the reader is charged and near the tablet.")
        }
        val problems=checks.filter {it.level==StaffCheck.Level.FAIL}
        val money=GivingSafety.formatCents(total)
        val subject=(if(env=="sandbox")"[Sandbox] " else "")+"Kiosk weekly status: "+(if(problems.isEmpty())"OK" else "NEEDS ATTENTION")+
            " · ${completed.size} gift${if(completed.size==1)"" else "s"} ($money)"
        val text=buildString {
            append(if(problems.isEmpty())"The donation kiosk is working.\r\n\r\n" else "The donation kiosk needs attention:\r\n"+problems.joinToString("") {"  • ${it.label}: ${it.detail}\r\n"}+"\r\n")
            append("Last 7 days: ${completed.size} completed gift(s), $money total")
            if(canceled>0)append("; $canceled canceled or declined (no charge)")
            append(".\r\n")
            app.ledger.records().filter {it.getString("environment")==env && it.getString("status")=="COMPLETED"}.maxByOrNull {it.optString("confirmed_date")}?.let {
                append("Most recent gift: ${GivingSafety.formatCents(it.getLong("cents"))} on ${java.time.OffsetDateTime.parse(it.getString("confirmed_date")).format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a",Locale.US))}.\r\n")
            }
            append("\r\nStatus\r\n")
            checks.forEach {append("  ${if(it.level==StaffCheck.Level.OK)"OK  " else if(it.level==StaffCheck.Level.WARN)"NOTE" else "FIX "}  ${it.label}: ${it.detail}\r\n")}
            append("\r\nApp version ${BuildConfig.VERSION_NAME}. Sent automatically every Monday. If this email stops arriving, check the kiosk.\r\n")
            append("To test everything without charging a card: hold the logo 3 seconds, enter the staff PIN, tap Run self-test.\r\n")
        }
        return subject to text
    }
}
