package org.portlandkollel.kiosk

import android.accounts.Account
import android.content.Context
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only these two narrow permissions are ever requested. */
internal val SCOPES=listOf("https://www.googleapis.com/auth/drive.file","https://www.googleapis.com/auth/gmail.send")

/** Google Play services keeps Google's refresh credentials; app stores no password/client secret. */
internal class GoogleDonations(private val context:Context,private val vault:StandaloneVault,private val http:TabletHttp=::defaultTabletHttp) {
    private fun request(url:String,method:String="GET",token:String?=null,body:JSONObject?=null)=http(url,method,token,body)
    fun request()=AuthorizationRequest.builder().setAccount(Account(vault.read().getString("google_account"),"com.google"))
        .setRequestedScopes(SCOPES.map {Scope(it)}).build()
    /** Google's consent screen has a checkbox per permission; both must be ticked. */
    fun hasAllScopes(result:AuthorizationResult)=result.grantedScopes.containsAll(SCOPES)
    suspend fun authorization():AuthorizationResult=suspendCancellableCoroutine { continuation ->
        Identity.getAuthorizationClient(context).authorize(request()).addOnSuccessListener {if(continuation.isActive)continuation.resume(it)}
            .addOnFailureListener {if(continuation.isActive)continuation.resumeWithException(KioskError("GOOGLE_AUTHORIZATION_"+(if(it is com.google.android.gms.common.api.ApiException)it.statusCode.toString() else "UNAVAILABLE")))}
    }
    /** A fresh Google access token, or a staff-readable error if Google needs to be reconnected. */
    suspend fun token():String {
        val auth=authorization();if(auth.hasResolution())throw KioskError("GOOGLE_STAFF_CONSENT_REQUIRED")
        if(!hasAllScopes(auth))throw KioskError("GOOGLE_PERMISSIONS_INCOMPLETE")
        return auth.accessToken?:throw KioskError("GOOGLE_AUTHORIZATION_REQUIRED")
    }
    /** Self-test: read-only check that the donations Sheet is still private and has the expected columns. */
    fun sheetStatus(token:String,environment:String):String {
        val key=if(environment=="sandbox")"sandbox_test_sheet_id" else "sheet_id"
        val sheet=vault.read().optString(key)
        if(sheet.isEmpty())return "Not created yet — the first gift creates it."
        ensurePrivate(token,sheet)
        check(read(token,sheet,tabRange(ACTIVITY_TAB,"A1:${col(HEADERS.size)}1"))?.toString()==headers().toString()) {"EXISTING_SHEET_COLUMNS_REQUIRE_REVIEW"}
        return "Private, columns OK."
    }
    /**
     * Staff-only status message (self-test, weekly status) to the gift-notice recipients. Not tied to any
     * donation, so it never touches gift records; a duplicate status email is harmless.
     */
    fun sendStatus(token:String,subject:String,text:String) {
        val sender=vault.read().getString("google_account");val to=noticeRecipients()
        check(sender.matches(EMAIL) && to.isNotEmpty() && to.size<=5 && to.all {it.matches(EMAIL)})
        post(token,raw(sender,to,subject,"status-${java.util.UUID.randomUUID()}",text))
    }
    private fun raw(sender:String,to:List<String>,subject:String,messageId:String,text:String)=
        "From: $FROM_NAME <$sender>\r\nTo: ${to.joinToString(", ")}\r\nSubject: ${header(subject)}\r\nMessage-ID: <$messageId@${sender.substringAfter('@')}>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"+Base64.encodeToString(text.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
    /** Non-ASCII subjects (·, —, accents) must be RFC 2047 encoded or mail apps show them garbled (e.g. "Â·"). */
    private fun header(text:String)=if(text.all {it.code in 32..126})text else "=?UTF-8?B?"+Base64.encodeToString(text.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)+"?="
    private fun post(token:String,raw:String)=request("https://gmail.googleapis.com/gmail/v1/users/me/messages/send","POST",token,JSONObject().put("raw",Base64.encodeToString(raw.toByteArray(Charsets.UTF_8),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
    suspend fun sync() {
        if(TabletLedger(vault).records().none(::needsSync))return
        val auth=authorization();if(auth.hasResolution())throw KioskError("GOOGLE_STAFF_CONSENT_REQUIRED")
        syncWithToken(auth.accessToken?:throw KioskError("GOOGLE_AUTHORIZATION_REQUIRED"))
    }
    private val inFlight=setOf("PENDING","SENDING")
    private fun needsSync(it:JSONObject)=it.getString("status") in TabletLedger.TERMINAL && it.optBoolean("square_verified") && it.has("sheet_row") &&
        (!it.optBoolean("sheet_done") || it.optString("email_status") in inFlight || it.optString("staff_notice") in inFlight)
    internal fun syncWithToken(token:String) {
        val pending=TabletLedger(vault).records().filter(::needsSync)
        if(pending.isEmpty())return
        val failures=mutableListOf<Exception>()
        // Sandbox must never write to the pre-existing organization Sheet.
        for((environment, records) in pending.groupBy {it.getString("environment")}) {
            val key=if(environment=="sandbox")"sandbox_test_sheet_id" else "sheet_id"
            var sheet=vault.read().optString(key)
            if(sheet.isEmpty())try {
                val title=if(environment=="sandbox")"Portland Kollel Kiosk — SANDBOX TEST" else "Portland Kollel Donations"
                val tabs=JSONArray().put(JSONObject().put("properties",JSONObject().put("title",QB_TAB))).put(JSONObject().put("properties",JSONObject().put("title",ACTIVITY_TAB)))
                val created=request("https://sheets.googleapis.com/v4/spreadsheets","POST",token,JSONObject().put("properties",JSONObject().put("title",title)).put("sheets",tabs))
                sheet=created.getString("spreadsheetId");check(sheet.matches(Regex("[A-Za-z0-9_-]{10,200}")))
                vault.update {it.put(key,sheet).put("new_sheet_headers_$sheet",true)}
            } catch(e:Exception){failures+=e}
            var privateSheet=false
            if(sheet.isNotEmpty())try {
                ensurePrivate(token,sheet)
                for((tab,expected) in listOf(QB_TAB to JSONArray(QB_HEADERS),ACTIVITY_TAB to headers())) {
                    val range=tabRange(tab,"A1:${col(expected.length())}1")
                    if(vault.read().optBoolean("new_sheet_headers_$sheet")) {
                        val existing=read(token,sheet,range)
                        if(existing==null || (0 until existing.length()).all {existing.optString(it).isEmpty()})putSheet(token,sheet,range,expected)
                        else check(existing.toString()==expected.toString()) {"EXISTING_SHEET_COLUMNS_REQUIRE_REVIEW"}
                    }
                    check(read(token,sheet,range)?.toString()==expected.toString()) {"EXISTING_SHEET_COLUMNS_REQUIRE_REVIEW"}
                }
                vault.update {it.remove("new_sheet_headers_$sheet")}
                privateSheet=true
            } catch(e:Exception){failures+=e}
            for(record in records) {
                if(privateSheet&&!record.optBoolean("sheet_done"))try {sheet(token,sheet,record)} catch(e:Exception){failures+=e}
                if(record.optString("email_status") in inFlight)try {email(token,record)} catch(e:Exception){failures+=e}
                if(record.optString("staff_notice") in inFlight)try {staffNotice(token,TabletLedger(vault).records().single {it.getString("id")==record.getString("id")})} catch(e:Exception){failures+=e}
                val latest=TabletLedger(vault).records().single {it.getString("id")==record.getString("id")}
                if(privateSheet&&!latest.optBoolean("sheet_done")&&latest.optString("email_status")!=record.optString("email_status"))try {sheet(token,sheet,latest)} catch(e:Exception){failures+=e}
            }
        }
        if(failures.isNotEmpty())throw KioskError("GOOGLE_UPDATES_PENDING")
    }
    private fun ensurePrivate(token:String,id:String) {
        check(id.matches(Regex("[A-Za-z0-9_-]{10,200}")))
        val base="https://www.googleapis.com/drive/v3/files/$id/permissions"
        fun permissions():List<JSONObject> {
            val list=request(base+"?fields=permissions(id,type,role)",token=token).getJSONArray("permissions")
            return (0 until list.length()).map {list.getJSONObject(it)}
        }
        val initial=permissions()
        check(initial.any {it.optString("role")=="owner"} && initial.none {it.optString("type") in setOf("anyone","domain")}) {"SHEET_ACCESS_REQUIRES_STAFF_REVIEW"}
        // Read only. Never grant, delete or change existing sharing permissions.
    }
    /** One column per field so the Sheet imports cleanly into QuickBooks. EMAIL_STATUS is the only column a later sync may change. */
    private fun headers()=JSONArray(HEADERS)
    private companion object {
        /** Every attempt (completed, canceled, failed), with details QuickBooks has no column for. */
        /** Name shown as the sender of every kiosk email. */
        const val FROM_NAME="Portland Kollel"
        const val ACTIVITY_TAB="All kiosk activity"
        val HEADERS=listOf("Date","Status","Gift USD","Card fee covered USD","Total charged USD","First name","Last name","Email","Cell phone",
            "Street","City","State","ZIP","Anonymous","In honor / memory of","Square payment ID","Donation reference","Thank-you email","Environment","Kiosk receipt no.")
        const val EMAIL_STATUS=17;const val MONEY_FROM=2;const val MONEY_TO=4;const val REFERENCE=16
        /** Completed gifts only, in Portland Kollel's QuickBooks Online sales-receipt import layout. */
        const val QB_TAB="QuickBooks import"
        val QB_HEADERS=listOf("Reference no.","Customer ","Sales Receipt Date","Deposit To","Payment Method","Reference No","Billing Address Line 1",
            "Billing Address City","Billing Address State","Billing Address Postal Code","Memo","Email","Product/service","PRODUCT SERVICE CLASS",
            "Description","Product/Service Amount","Tag")
        const val QB_PRODUCT="Local Donation:Tablet Donation";const val QB_CLASS="Local Donation";const val QB_AMOUNT=15
        fun col(count:Int)=('A'+count-1).toString()
        fun tabRange(tab:String,a1:String)="'$tab'!$a1"
        fun receiptNo(qbRow:Int)="K-${999+qbRow}"
    }
    private fun read(token:String,id:String,range:String)=request("https://sheets.googleapis.com/v4/spreadsheets/$id/values/"+StandaloneHttp.encode(range)+"?valueRenderOption=UNFORMATTED_VALUE",token=token).optJSONArray("values")?.optJSONArray(0)
    /** Writes a row only if empty or identical apart from [mutable] columns; never overwrites someone else's row. */
    private fun putRow(token:String,id:String,range:String,values:JSONArray,key:Int,mutable:Set<Int> = emptySet(),moneyColumns:Set<Int> = emptySet()) {
        val existing=read(token,id,range)
        if(existing!=null && (0 until existing.length()).any {existing.optString(it).isNotEmpty()}) {
            check(existing.optString(key)==values.getString(key)) {"EXISTING_SHEET_ROW_CONFLICT"}
            check((0 until values.length()).filter {it !in mutable}.all {index ->
                if(index in moneyColumns)existing.optString(index).toBigDecimalOrNull()?.compareTo(values.getString(index).toBigDecimal())==0
                else existing.optString(index)==values.getString(index)
            }) {"EXISTING_SHEET_ROW_CHANGED"}
        }
        putSheet(token,id,range,values)
    }
    private fun money(cents:Long)=java.math.BigDecimal(cents).movePointLeft(2)
    private fun putSheet(token:String,id:String,range:String,values:JSONArray)=request("https://sheets.googleapis.com/v4/spreadsheets/$id/values/"+StandaloneHttp.encode(range)+"?valueInputOption=RAW","PUT",token,JSONObject().put("values",JSONArray().put(values)))
    private fun sheet(token:String,id:String,record:JSONObject) {
        val details=record.getJSONObject("details");val row=record.getInt("sheet_row")
        val total=record.getLong("cents");val fee=record.optLong("fee_cents",0)
        fun d(key:String)=details.optString(key)
        val receipt=if(record.has("qb_row"))receiptNo(record.getInt("qb_row")) else ""
        val activity=JSONArray(listOf<Any>(record.getString("confirmed_date"),
            if(record.optBoolean("no_charge"))"CANCELED (no charge)" else record.getString("status"),
            money(record.optLong("gift_cents",total-fee)),money(fee),money(total),
            d("first_name").ifEmpty {if(d("last_name").isEmpty())d("name") else ""},d("last_name"),d("email"),d("phone"),
            d("street"),d("city"),d("state"),d("zip"),if(details.optBoolean("anonymous"))"Yes" else "No",d("honor"),
            record.optString("payment_id"),record.getString("id"),record.getString("email_status"),record.getString("environment"),receipt))
        // Only the kiosk's email status may change on a previously written activity row.
        putRow(token,id,tabRange(ACTIVITY_TAB,"A$row:${col(HEADERS.size)}$row"),activity,REFERENCE,setOf(EMAIL_STATUS),(MONEY_FROM..MONEY_TO).toSet())
        if(record.getString("status")=="COMPLETED" && record.has("qb_row")) {
            val qbRow=record.getInt("qb_row")
            val customer=if(details.optBoolean("anonymous"))"Anonymous Donor" else d("name").ifBlank {"Kiosk Donor"}
            val date=java.time.OffsetDateTime.parse(record.getString("confirmed_date")).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy",java.util.Locale.US))
            val quickBooks=JSONArray(listOf<Any>(receipt,customer,date,vault.read().optString("qb_deposit_to").ifBlank {"Undeposited Funds"},"Credit Card",
                record.optString("payment_id"),d("street"),d("city"),d("state"),d("zip"),d("honor"),d("email"),QB_PRODUCT,QB_CLASS,
                "Tablet kiosk donation"+d("honor").takeIf {it.isNotBlank()}?.let {" — $it"}.orEmpty(),money(total),"Kiosk"))
            putRow(token,id,tabRange(QB_TAB,"A$qbRow:${col(QB_HEADERS.size)}$qbRow"),quickBooks,0,moneyColumns=setOf(QB_AMOUNT))
        }
        TabletLedger(vault).change(record.getString("id")){it.put("sheet_done",true)}
    }
    /**
     * Narrow scope: gmail.send can only send; it cannot read, list or draft, so it cannot check whether an
     * interrupted send went out. SENDING is saved before the request. A record still SENDING at a later sync,
     * or a send whose outcome is unknown (timeout, dropped connection, 5xx), is never resent automatically:
     * it becomes NEEDS_STAFF_REVIEW. Only failures that prove nothing was sent (no connection, 429) retry.
     */
    private fun sendOnce(token:String,id:String,statusKey:String,to:List<String>,subject:String,text:String,onSent:(JSONObject)->Unit={}) {
        val ledger=TabletLedger(vault)
        if(ledger.records().single {it.getString("id")==id}.optString(statusKey)=="SENDING") {
            ledger.change(id){it.put(statusKey,"NEEDS_STAFF_REVIEW");onSent(it)};return
        }
        val sender=vault.read().getString("google_account")
        check(sender.matches(EMAIL) && to.isNotEmpty() && to.size<=5 && to.all {it.matches(EMAIL)})
        val message=raw(sender,to,subject,"$id.$statusKey",text)
        ledger.change(id){it.put(statusKey,"SENDING")}
        val outcome=try {
            post(token,message)
            "SENT"
        } catch(e:RemoteFailure) {
            if(e.status==429) "PENDING" else "NEEDS_STAFF_REVIEW" // 4xx: rejected; 5xx: may have been sent
        } catch(e:java.io.IOException) {
            if(e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException) "PENDING" else "NEEDS_STAFF_REVIEW"
        }
        ledger.change(id){it.put(statusKey,outcome);onSent(it)}
        if(outcome=="PENDING")throw KioskError("GMAIL_RETRY_LATER")
    }
    /** Every outcome is emailed to the organization account itself (completed, failed, canceled). */
    private fun staffNotice(token:String,record:JSONObject) {
        val details=record.getJSONObject("details");val sandbox=record.getString("environment")=="sandbox"
        val outcome=if(record.optBoolean("no_charge"))"CANCELED (no charge)" else record.getString("status")
        val amount=org.portlandkollel.kiosk.core.GivingSafety.formatCents(record.getLong("cents"))
        val subject=(if(sandbox)"[Sandbox] " else "")+"Kiosk gift $outcome: $amount"+details.getString("name").takeIf {it.isNotBlank()}?.let {" from $it"}.orEmpty()
        // amount above is the full charge; the body itemizes any donor-covered card fee.
        val text=(if(sandbox)"SANDBOX TEST — no real money.\r\n\r\n" else "")+
            "Outcome: $outcome\r\nAmount: $amount\r\nDate: ${record.optString("confirmed_date")}\r\n"+
            (if(record.optLong("fee_cents",0)>0)"Gift: ${org.portlandkollel.kiosk.core.GivingSafety.formatCents(record.optLong("gift_cents"))} + card fee covered by donor ${org.portlandkollel.kiosk.core.GivingSafety.formatCents(record.optLong("fee_cents"))}\r\n" else "")+
            "Anonymous: ${if(details.optBoolean("anonymous"))"yes (no tax receipt)" else "no"}\r\n"+
            "First name: ${details.optString("first_name").ifBlank {details.getString("name")}.ifBlank {"(none)"}}\r\nLast name: ${details.optString("last_name").ifBlank {"(none)"}}\r\n"+
            "Email: ${details.getString("email").ifBlank {"(none)"}}\r\nCell: ${details.optString("phone").ifBlank {"(none)"}}\r\n"+
            "Address: ${listOf(details.optString("street"),details.optString("city"),listOf(details.optString("state"),details.optString("zip")).filter {it.isNotBlank()}.joinToString(" ")).filter {it.isNotBlank()}.joinToString(", ").ifBlank {"(none)"}}\r\n"+
            "In honor / memory of: ${details.getString("honor").ifBlank {"(none)"}}\r\nThank-you email to donor: ${if(details.optBoolean("thank_you"))"yes" else "no"}\r\n"+
            "Square payment ID: ${record.optString("payment_id").ifBlank {"(none)"}}\r\nDonation reference: ${record.getString("id")}\r\n"
        sendOnce(token,record.getString("id"),"staff_notice",noticeRecipients(),subject,text)
    }
    /** Staff-configured gift-notice recipients; defaults to the organization account itself. */
    private fun noticeRecipients():List<String> {
        val root=vault.read()
        return root.optString("notify_emails").split(',').map {it.trim()}.filter {it.isNotEmpty()}.ifEmpty {listOf(root.getString("google_account"))}
    }
    private val EMAIL=Regex("[A-Za-z0-9.!#%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+")
    private fun email(token:String,record:JSONObject) {
        val id=record.getString("id");val details=record.getJSONObject("details")
        check(details.getBoolean("thank_you") && details.getString("email").isNotBlank())
        val subject=if(record.getString("environment")=="sandbox")"[Sandbox test] Thank you for your gift to Portland Kollel" else "Thank you for your gift to Portland Kollel"
        val greeting=details.optString("first_name").ifBlank {details.optString("name")}.let {if(it.isBlank())"Dear friend," else "Dear $it,"}
        val date=java.time.OffsetDateTime.parse(record.getString("confirmed_date")).format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy",java.util.Locale.US))
        val text=(if(record.getString("environment")=="sandbox")"SANDBOX TEST — No real money was charged.\r\n\r\n" else "")+"$greeting\r\n\r\n"+
            "Thank you for your generous gift of ${org.portlandkollel.kiosk.core.GivingSafety.formatCents(record.getLong("cents"))} to Portland Kollel on $date.\r\n"+
            (if(details.getString("honor").isNotBlank())"In honor / memory of: ${details.getString("honor")}\r\n" else "")+
            "\r\nYour support helps Torah learning and Jewish connection in our community.\r\n\r\n"+
            "Your tax-deductible receipt will be sent to you at the end of the year.\r\n\r\nWith gratitude,\r\nPortland Kollel\r\n"
        // The Sheet's email-status column is refreshed after every change.
        sendOnce(token,id,"email_status",listOf(details.getString("email")),subject,text) {it.put("sheet_done",false)}
    }
}
