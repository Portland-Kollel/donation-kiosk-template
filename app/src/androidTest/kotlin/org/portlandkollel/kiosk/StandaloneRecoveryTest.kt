package org.portlandkollel.kiosk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.portlandkollel.kiosk.core.GivingDetails

@RunWith(AndroidJUnit4::class)
class StandaloneRecoveryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun vault()=StandaloneVault(context,"fixture-${java.util.UUID.randomUUID()}.enc")
    private fun completed(vault:StandaloneVault,mail:Boolean=true):JSONObject {
        val ledger=TabletLedger(vault)
        val row=ledger.reserve(100,GivingDetails("Synthetic donor","fixture@example.invalid","Synthetic dedication",thankYou=mail),"synthetic-location","sandbox")
        ledger.verified(row.getString("id"),JSONObject().put("id","synthetic-payment").put("status","COMPLETED").put("created_at","2026-11-04T10:00:00Z"))
        ledger.change(row.getString("id")){it.put("staff_notice","SENT")} // donor-mail tests isolate the staff notice
        return ledger.active()!!
    }
    private fun headerResponse(url:String)=JSONObject().put("values",org.json.JSONArray().put(org.json.JSONArray(if(url.contains("QuickBooks"))
        listOf("Reference no.","Customer ","Sales Receipt Date","Deposit To","Payment Method","Reference No","Billing Address Line 1","Billing Address City",
            "Billing Address State","Billing Address Postal Code","Memo","Email","Product/service","PRODUCT SERVICE CLASS","Description","Product/Service Amount","Tag")
        else listOf("Date","Status","Gift USD","Card fee covered USD","Total charged USD","First name","Last name","Email","Cell phone","Street","City","State","ZIP",
            "Anonymous","In honor / memory of","Square payment ID","Donation reference","Thank-you email","Environment","Kiosk receipt no."))))
    @Test fun encryptedLedgerSurvivesRestartAndNeverReservesSecondActivePayment() {
        val store=vault();val ledger=TabletLedger(store)
        val first=ledger.reserve(100,GivingDetails("Synthetic donor"),"synthetic-location","sandbox")
        assertThrows(IllegalStateException::class.java){ledger.reserve(100,GivingDetails("Other"),"synthetic-location","sandbox")}
        assertEquals(first.getString("id"),TabletLedger(store).active()!!.getString("id"))
        assertThrows(IllegalStateException::class.java){ledger.close(first.getString("id"))}
        val record=completedAfterClearForFixture(store,first)
        assertEquals(2,record.getInt("sheet_row"))
        completedAfterClearForFixture(store,first)
        assertEquals(3,store.read().getInt("next_row"))
        assertThrows(IllegalStateException::class.java){ledger.change(first.getString("id")){it.put("cents",1800)}}
        ledger.close(first.getString("id"))
        val next=ledger.reserve(1800,GivingDetails("Other"),"synthetic-location","sandbox")
        assertNotEquals(first.getString("sdk_attempt_id"),next.getString("sdk_attempt_id"))
        assertEquals(2,ledger.records().size)
    }
    private fun completedAfterClearForFixture(store:StandaloneVault,row:JSONObject):JSONObject {
        TabletLedger(store).verified(row.getString("id"),JSONObject().put("id","synthetic-payment").put("status","COMPLETED"))
        return TabletLedger(store).active()!!
    }
    private fun signedInSandbox(store:StandaloneVault) {
        val app="sandbox-sq0idb-synthetic"
        store.update {it.put("config",JSONObject().put("environment","sandbox").put("application_id",app).put("callback_origin","https://callback.example.invalid"))
            .put("square_tokens_sandbox",JSONObject().put("access","synthetic-access").put("refresh","synthetic-refresh").put("expires",java.time.Instant.now().epochSecond+80000).put("application_id",app).put("validated",true))}
    }
    @Test fun canceledSdkPaymentClosesOnlyAfterSettleDelayAndEmptySquareListing() {
        val store=vault();signedInSandbox(store);val ledger=TabletLedger(store)
        val row=ledger.reserve(100,GivingDetails("Synthetic donor"),"synthetic-location","sandbox");val id=row.getString("id")
        var payments=org.json.JSONArray()
        val square=TabletSquare(context,store) {url,_,_,_ ->
            check(url.contains("/v2/payments?location_id=synthetic-location"));JSONObject().put("payments",payments)
        }
        // No explicit SDK no-payment result: never closes as not charged.
        assertFalse(square.confirmNoCharge(ledger.active()!!))
        ledger.change(id){it.put("sdk_no_payment",true).put("sdk_no_payment_at",System.currentTimeMillis())}
        assertFalse(square.confirmNoCharge(ledger.active()!!)) // settle delay not elapsed
        ledger.change(id){it.put("sdk_no_payment_at",System.currentTimeMillis()-TabletSquare.NO_CHARGE_SETTLE_MS-1)}
        payments=org.json.JSONArray().put(JSONObject().put("id","synthetic-payment").put("reference_id",id))
        assertFalse(square.confirmNoCharge(ledger.active()!!)) // a payment exists: must reconcile, not close
        payments=org.json.JSONArray()
        assertTrue(square.confirmNoCharge(ledger.active()!!))
        ledger.closeNoCharge(id)
        assertNull(ledger.active())
        val closed=ledger.records().single()
        assertEquals("CANCELED",closed.getString("status"));assertTrue(closed.getBoolean("no_charge"))
        assertNotNull(ledger.reserve(100,GivingDetails("Next donor"),"synthetic-location","sandbox"))
    }
    @Test fun canceledGiftGetsSheetRowAndStaffNoticeButNoDonorEmail() {
        val store=vault();store.update{it.put("google_account","operator@example.invalid").put("sandbox_test_sheet_id","synthetic-sheet")}
        val ledger=TabletLedger(store)
        val id=ledger.reserve(1800,GivingDetails("Synthetic donor","fixture@example.invalid",thankYou=true),"synthetic-location","sandbox").getString("id")
        ledger.change(id){it.put("sdk_no_payment",true).put("sdk_no_payment_at",1L)}
        ledger.closeNoCharge(id)
        val closed=ledger.records().single()
        assertEquals(2,closed.getInt("sheet_row"));assertEquals("NONE",closed.getString("email_status"))
        val rows=mutableListOf<String>();val recipients=mutableListOf<String>()
        val remote:TabletHttp={url,_,_,body ->
            when {
                url.contains("/drive/v3/") -> JSONObject().put("permissions",org.json.JSONArray().put(JSONObject().put("id","owner").put("type","user").put("role","owner")))
                url.contains("A1%3A") && !url.contains("valueInputOption") -> headerResponse(url)
                url.contains("/values/") && !url.contains("valueInputOption") -> JSONObject()
                url.contains("/values/") -> {rows+=body!!.toString();JSONObject()}
                url.endsWith("/messages/send") -> {
                    val raw=String(android.util.Base64.decode(body!!.getString("raw"),android.util.Base64.URL_SAFE))
                    recipients+=raw.lines().single {it.startsWith("To: ")};JSONObject().put("id","synthetic-message")
                }
                else -> throw AssertionError("Unexpected test operation")
            }
        }
        GoogleDonations(context,store,remote).syncWithToken("synthetic-token")
        assertEquals(listOf("To: operator@example.invalid"),recipients)
        assertTrue(rows.single().contains("CANCELED (no charge)"))
        val done=TabletLedger(store).records().single()
        assertTrue(done.getBoolean("sheet_done"));assertEquals("SENT",done.getString("staff_notice"))
    }
    @Test fun sheetLostResponseRetriesSameRowAndDoesNotResendThankYou() {
        val store=vault();completed(store);store.update{it.put("google_account","operator@example.invalid").put("sandbox_test_sheet_id","synthetic-sheet")}
        val writes=mutableListOf<String>();var lost=true;var sends=0
        val remote:TabletHttp={url,_,_,_ ->
            when {
                url.contains("/drive/v3/") -> JSONObject().put("permissions",org.json.JSONArray().put(JSONObject().put("id","owner").put("type","user").put("role","owner")))
                url.contains("A1%3A") && !url.contains("valueInputOption") -> headerResponse(url)
                url.contains("/values/") && !url.contains("valueInputOption") -> JSONObject()
                url.contains("/values/") -> {writes+=url;if(url.contains("A2")&&lost){lost=false;throw java.io.IOException("synthetic lost response")};JSONObject()}
                url.endsWith("/messages/send") -> {sends++;JSONObject().put("id","synthetic-message")}
                else -> throw AssertionError("Unexpected test operation")
            }
        }
        val sync=GoogleDonations(context,store,remote)
        runCatching{sync.syncWithToken("synthetic-token")}
        sync.syncWithToken("synthetic-token")
        assertEquals(1,sends);assertTrue(writes.count {it.contains("A2")}>1)
        assertFalse(writes.any {it.contains("A3")});assertTrue(TabletLedger(store).active()!!.getBoolean("sheet_done"))
        assertEquals("COMPLETED",TabletLedger(store).active()!!.getString("status"))
    }
    @Test fun sheetOutageDoesNotBlockMailAndUncertainSendIsNeverRepeated() {
        val store=vault();completed(store);store.update {it.put("google_account","operator@example.invalid")}
        var attempts=0
        val remote:TabletHttp={url,_,_,_ ->
            when {
                url.contains("sheets.googleapis.com") -> throw RemoteFailure(503)
                url.endsWith("/messages/send") -> {attempts++;throw java.net.SocketTimeoutException("synthetic: request may have reached Gmail")}
                else -> throw AssertionError("Unexpected test operation")
            }
        }
        val sync=GoogleDonations(context,store,remote)
        runCatching {sync.syncWithToken("synthetic-token")}
        runCatching {sync.syncWithToken("synthetic-token")}
        assertEquals(1,attempts) // gmail.send cannot check the Sent folder, so an uncertain send is never retried
        assertEquals("NEEDS_STAFF_REVIEW",TabletLedger(store).active()!!.getString("email_status"))
        assertEquals("COMPLETED",TabletLedger(store).active()!!.getString("status"))
        assertFalse(TabletLedger(store).active()!!.getBoolean("sheet_done"))
    }
    @Test fun noConnectionRetriesTheThankYouAndOnlyNarrowScopesAreRequested() {
        val store=vault();completed(store);store.update {it.put("google_account","operator@example.invalid")}
        var offline=true;var sent=0
        val remote:TabletHttp={url,_,_,_ ->
            when {
                url.contains("sheets.googleapis.com") || url.contains("/drive/v3/") -> throw RemoteFailure(503)
                url.endsWith("/messages/send") -> {if(offline)throw java.net.ConnectException("synthetic: offline");sent++;JSONObject().put("id","synthetic-message")}
                else -> throw AssertionError("Unexpected test operation")
            }
        }
        val sync=GoogleDonations(context,store,remote)
        runCatching {sync.syncWithToken("synthetic-token")}
        assertEquals("PENDING",TabletLedger(store).active()!!.getString("email_status"))
        offline=false;runCatching {sync.syncWithToken("synthetic-token")}
        assertEquals(1,sent);assertEquals("SENT",TabletLedger(store).active()!!.getString("email_status"))
        store.update {it.put("google_account","operator@example.invalid")}
        val scopes=sync.request().requestedScopes.map {it.scopeUri}.toSet()
        assertEquals(setOf("https://www.googleapis.com/auth/drive.file","https://www.googleapis.com/auth/gmail.send"),scopes)
    }
    @Test fun ambiguousSingleUseRefreshIsNotReplayedAfterProcessRestart() {
        val store=vault()
        store.update {it.put("config",JSONObject().put("environment","sandbox").put("application_id","sandbox-sq0idb-synthetic").put("callback_origin","https://kiosk.fixture.workers.dev"))
            .put("square_tokens",JSONObject().put("access","synthetic-token").put("refresh","synthetic-refresh").put("expires",1))}
        var calls=0
        val remote:TabletHttp={_,_,_,_ ->calls++;throw java.io.IOException("synthetic lost refresh")}
        runCatching {TabletSquare(context,store,remote).token()}
        runCatching {TabletSquare(context,store,remote).token()}
        assertEquals(1,calls);assertTrue(store.read().getJSONObject("square_tokens_sandbox").getBoolean("refresh_inflight"))
    }
    @Test fun mobilePkceEnvelopeUsesPersistentNonexportableKeyAndOriginalChallenge() {
        val store=vault();store.update {it.put("config",JSONObject().put("environment","sandbox").put("application_id","sandbox-sq0idb-synthetic").put("callback_origin","https://kiosk.fixture.workers.dev"))}
        var session=JSONObject();var envelope=JSONObject();var challenge=""
        val remote:TabletHttp={url,_,_,body ->
            when {
                url.endsWith("/reader/sessions") -> {session=JSONObject(body!!.toString());JSONObject().put("ready",true)}
                url.endsWith("/reader/poll") -> envelope
                url.endsWith("/oauth2/token") -> {
                    val actual=android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(body!!.getString("code_verifier").toByteArray()),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    assertEquals(challenge,actual)
                    JSONObject().put("token_type","bearer").put("access_token","synthetic-token").put("refresh_token","synthetic-rotated-refresh").put("expires_at",java.time.Instant.now().plusSeconds(86400).toString())
                }
                else -> throw AssertionError("Unexpected test operation")
            }
        }
        val begin=TabletSquare(context,store,remote).begin()
        challenge=begin.data!!.getQueryParameter("code_challenge")!!
        val flow=store.read().getJSONObject("oauth")
        val keyStore=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        assertNull(keyStore.getKey(flow.getString("alias"),null).encoded)
        val encoded=session.getString("public_key").replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replace("\n","")
        val publicKey=java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)))
        val aesKey=ByteArray(32).also {java.security.SecureRandom().nextBytes(it)};val iv=ByteArray(12).also {java.security.SecureRandom().nextBytes(it)}
        val aes=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");aes.init(javax.crypto.Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(aesKey,"AES"),javax.crypto.spec.GCMParameterSpec(128,iv))
        aes.updateAAD(("kollel-reader-relay-v1:"+session.getString("state")).toByteArray())
        val encrypted=aes.doFinal(("state="+session.getString("state")+"&code=synthetic-code").toByteArray())
        val rsa=javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(javax.crypto.Cipher.ENCRYPT_MODE,publicKey,javax.crypto.spec.OAEPParameterSpec("SHA-1","MGF1",java.security.spec.MGF1ParameterSpec.SHA1,javax.crypto.spec.PSource.PSpecified.DEFAULT))
        fun b64(bytes:ByteArray)=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        envelope=JSONObject().put("sealed",JSONObject().put("key",b64(rsa.doFinal(aesKey))).put("iv",b64(iv)).put("ciphertext",b64(encrypted.copyOfRange(0,encrypted.size-16))).put("tag",b64(encrypted.copyOfRange(encrypted.size-16,encrypted.size))))
        assertTrue(TabletSquare(context,store,remote).complete())
        assertFalse(store.read().has("oauth"));assertEquals("synthetic-rotated-refresh",store.read().getJSONObject("square_tokens_sandbox").getString("refresh"))
        assertFalse(keyStore.containsAlias(flow.getString("alias")))
    }

    @Test fun tabletVerifierRejectsDifferentTotalRefundAndWrongProviderReference() {
        val store=vault();store.update {it.put("config",JSONObject().put("environment","sandbox").put("application_id","sandbox-sq0idb-synthetic").put("callback_origin","https://kiosk.fixture.workers.dev"))
            .put("square_tokens",JSONObject().put("access","synthetic-token").put("refresh","synthetic-refresh").put("expires",java.time.Instant.now().epochSecond+80000).put("validated",true))}
        val row=TabletLedger(store).reserve(100,GivingDetails("Fixture"),"synthetic-location","sandbox")
        val base=JSONObject().put("id","synthetic-payment").put("reference_id",row.getString("id")).put("location_id","synthetic-location").put("source_type","CARD").put("status","COMPLETED")
            .put("amount_money",JSONObject().put("amount",100).put("currency","USD")).put("total_money",JSONObject().put("amount",100).put("currency","USD"))
        var candidate=JSONObject(base.toString())
        val api:TabletHttp={_,_,_,_->JSONObject().put("payment",candidate)}
        val client=TabletSquare(context,store,api)
        assertEquals("COMPLETED",client.verify(row,"synthetic-payment").getString("status"))
        for(bad in listOf(JSONObject(base.toString()).put("reference_id","other"),
            JSONObject(base.toString()).put("total_money",JSONObject().put("amount",101).put("currency","USD")),
            JSONObject(base.toString()).put("refunded_money",JSONObject().put("amount",100).put("currency","USD")))) {
            candidate=bad;assertThrows(IllegalStateException::class.java){client.verify(row,"synthetic-payment")}
        }
        assertEquals("RESERVED",TabletLedger(store).active()!!.getString("status"))
    }

    @Test fun sandboxSheetLeavesExistingSheetAndSharingUntouched() {
        val store=vault();completed(store,false)
        store.update {it.put("google_account","operator@example.invalid").put("sheet_id","existing-organization-sheet")}
        val calls=mutableListOf<String>()
        val remote:TabletHttp={url,method,_,_ ->
            calls+="$method $url"
            assertFalse(url.contains("existing-organization-sheet"));assertNotEquals("DELETE",method)
            when {
                url.endsWith("/spreadsheets") -> JSONObject().put("spreadsheetId","separate-sandbox-sheet")
                url.contains("/drive/v3/") -> JSONObject().put("permissions",org.json.JSONArray().put(JSONObject().put("id","owner").put("type","user").put("role","owner")))
                url.contains("A1%3A") && method=="GET" -> headerResponse(url)
                else -> JSONObject()
            }
        }
        GoogleDonations(context,store,remote).syncWithToken("synthetic-token")
        assertEquals("existing-organization-sheet",store.read().getString("sheet_id"))
        assertEquals("separate-sandbox-sheet",store.read().getString("sandbox_test_sheet_id"))
        assertTrue(TabletLedger(store).active()!!.getBoolean("sheet_done"))
    }
    @Test fun occupiedRowsAndBroadSharingArePreservedAndUpdatesRemainQueued() {
        for(broadSharing in listOf(false,true)) {
            val store=vault();completed(store,false)
            store.update {it.put("google_account","operator@example.invalid").put("sandbox_test_sheet_id","separate-sandbox-sheet")}
            var writes=0
            val remote:TabletHttp={url,method,_,_ ->
                if(method!="GET")writes++
                when {
                    url.contains("/drive/v3/") -> JSONObject().put("permissions",org.json.JSONArray().put(JSONObject().put("id","owner").put("type","user").put("role","owner")).also {if(broadSharing)it.put(JSONObject().put("id","existing-domain-access").put("type","domain").put("role","reader"))})
                    url.contains("A1%3A") -> headerResponse(url)
                    else -> JSONObject().put("values",org.json.JSONArray().put(org.json.JSONArray(listOf("Existing row must survive"))))
                }
            }
            runCatching {GoogleDonations(context,store,remote).syncWithToken("synthetic-token")}
            assertEquals(0,writes);assertFalse(TabletLedger(store).active()!!.optBoolean("sheet_done"))
            assertEquals("COMPLETED",TabletLedger(store).active()!!.getString("status"))
        }
    }
    @Test fun squareCredentialsRemainBoundToEnvironmentAndApplication() {
        val store=vault()
        val sandbox=JSONObject().put("environment","sandbox").put("application_id","sandbox-sq0idb-synthetic").put("callback_origin","https://kiosk.fixture.workers.dev")
        store.update {it.put("config",sandbox).put("square_tokens",JSONObject().put("access","synthetic-token").put("refresh","synthetic-refresh").put("expires",java.time.Instant.now().epochSecond+80000).put("validated",true))}
        val session=TabletSquare(context,store)
        assertTrue(session.hasCredentials());assertFalse(store.read().has("square_tokens"))
        session.configure(JSONObject().put("environment","production").put("application_id","sq0idp-synthetic").put("callback_origin","https://kiosk.fixture.workers.dev"))
        assertFalse(session.hasCredentials());assertTrue(store.read().has("square_tokens_sandbox"))
        session.configure(sandbox);assertEquals("synthetic-token",session.token())
        session.configure(JSONObject(sandbox.toString()).put("application_id","sandbox-sq0idb-other"));assertFalse(session.hasCredentials())
        assertEquals("https://kiosk.fixture.workers.dev",StandaloneHttp.callbackOrigin("https://kiosk.fixture.workers.dev/"))
        for(url in listOf("http://kiosk.fixture.workers.dev","https://untrusted.example.invalid","https://kiosk.fixture.workers.dev/path","https://kiosk.fixture.workers.dev?code=synthetic")) {
            assertTrue(runCatching {StandaloneHttp.callbackOrigin(url)}.isFailure)
        }
    }

}
