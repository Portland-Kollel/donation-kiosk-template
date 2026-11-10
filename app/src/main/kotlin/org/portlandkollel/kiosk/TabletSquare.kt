package org.portlandkollel.kiosk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.portlandkollel.kiosk.core.*
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.time.Instant

/** Supported public-client PKCE: no app secret, no Mac, refresh tokens encrypted on tablet. */
internal class TabletSquare(private val context:Context,private val vault:StandaloneVault,private val http:TabletHttp?=null) {
    private fun request(url:String,method:String="GET",token:String?=null,body:JSONObject?=null)=http?.invoke(url,method,token,body)
        ?:StandaloneHttp.request(url,method,token,body,if(url.contains("/reader/"))callbackOrigin() else null)
    private fun environment()=config().getString("environment").also {check(it in setOf("sandbox","production"))}
    private fun tokenKey()="square_tokens_${environment()}"
    @Synchronized private fun credentials():JSONObject? {
        val snapshot=vault.read()
        if(!snapshot.has("config"))return null
        if(snapshot.has("square_tokens"))vault.update {root ->
            root.put(tokenKey(),root.getJSONObject("square_tokens").put("application_id",config().getString("application_id")))
            root.remove("square_tokens")
        }
        return vault.read().optJSONObject(tokenKey())?.takeIf {it.optString("application_id")==config().getString("application_id")}
    }
    @Synchronized fun configure(value:JSONObject) {
        credentials()
        val pending=vault.read().optJSONObject("oauth")
        if(pending!=null)KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(pending.getString("alias"))}
        vault.update {root ->root.put("config",value).put("config_${value.getString("environment")}",JSONObject(value.toString()));root.remove("oauth")}
    }
    fun hasCredentials()=credentials()!=null
    private fun callbackOrigin()=StandaloneHttp.callbackOrigin(config().optString("callback_origin"))
    private val scopes="MERCHANT_PROFILE_READ PAYMENTS_READ PAYMENTS_WRITE PAYMENTS_WRITE_IN_PERSON"
    fun config()=vault.read().getJSONObject("config")
    fun origin()=if(config().getString("environment")=="sandbox")"https://connect.squareupsandbox.com" else "https://connect.squareup.com"
    private fun b64(raw:ByteArray)=Base64.encodeToString(raw,Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    private fun random(n:Int)=ByteArray(n).also {SecureRandom().nextBytes(it)}
    private val callbackBase get()=callbackOrigin()+"/"+environment()
    private val redirect get()="$callbackBase/square/callback"
    @Synchronized fun begin():Intent {
        callbackOrigin() // Fail before generating a key if staff have not configured the Worker.
        val state=b64(random(32));val verifier=b64(random(64));val proof=b64(random(32));val alias="square-pkce-$state"
        val keys=KeyPairGenerator.getInstance("RSA","AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_DECRYPT).setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).setDigests(KeyProperties.DIGEST_SHA1).build())
        }.generateKeyPair()
        val pem="-----BEGIN PUBLIC KEY-----\n"+Base64.encodeToString(keys.public.encoded,Base64.NO_WRAP).chunked(64).joinToString("\n")+"\n-----END PUBLIC KEY-----\n"
        val flow=JSONObject().put("state",state).put("verifier",verifier).put("proof",proof).put("alias",alias).put("started",System.currentTimeMillis()).put("environment",environment()).put("application_id",config().getString("application_id")).put("callback_origin",callbackOrigin())
        val oldFlow=vault.read().optJSONObject("oauth")
        if(oldFlow!=null)KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(oldFlow.getString("alias"))}
        vault.update {old ->old.put("oauth",flow)}
        request("$callbackBase/reader/sessions","POST",proof,JSONObject().put("state",state).put("public_key",pem))
        val challenge=b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val url=origin()+"/oauth2/authorize?client_id="+StandaloneHttp.encode(config().getString("application_id"))+"&scope="+StandaloneHttp.encode(scopes)+"&state=$state&code_challenge=$challenge&code_challenge_method=S256&response_type=code&redirect_uri="+StandaloneHttp.encode(redirect)
        return Intent(Intent.ACTION_VIEW,Uri.parse(url))
    }
    @Synchronized fun complete():Boolean {
        val flow=vault.read().optJSONObject("oauth")?:return false
        check(flow.getString("environment")==environment() && flow.getString("application_id")==config().getString("application_id") && flow.getString("callback_origin")==callbackOrigin()) {"SQUARE_SIGN_IN_CONFIGURATION_CHANGED"}
        if(System.currentTimeMillis()-flow.getLong("started") !in 0..600000)throw KioskError("SQUARE_SIGN_IN_EXPIRED")
        check(!flow.optBoolean("exchanging")) {"SQUARE_SIGN_IN_RESTART_REQUIRED"}
        val response=request("$callbackBase/reader/poll","POST",flow.getString("proof"),JSONObject().put("state",flow.getString("state")))
        if(response.isNull("sealed"))return false
        val sealed=response.getJSONObject("sealed");fun bytes(k:String)=Base64.decode(sealed.getString(k),Base64.DEFAULT)
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val unwrap=Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");unwrap.init(Cipher.DECRYPT_MODE,store.getKey(flow.getString("alias"),null))
        val aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,SecretKeySpec(unwrap.doFinal(bytes("key")),"AES"),GCMParameterSpec(128,bytes("iv")))
        aes.updateAAD(("kollel-reader-relay-v1:"+flow.getString("state")).toByteArray())
        val query=String(aes.doFinal(bytes("ciphertext")+bytes("tag")),Charsets.US_ASCII)
        val parts=mutableMapOf<String,String>()
        query.split('&').forEach {part ->val pair=part.split('=',limit=2);check(pair.size==2);val key=java.net.URLDecoder.decode(pair[0],"UTF-8");check(!parts.containsKey(key));parts[key]=java.net.URLDecoder.decode(pair[1],"UTF-8")}
        check(parts["state"]==flow.getString("state") && !parts.containsKey("error"))
        val code=parts["code"]?:throw KioskError("SQUARE_AUTHORIZATION_DENIED")
        vault.update {it.getJSONObject("oauth").put("exchanging",true)}
        val token=request(origin()+"/oauth2/token","POST",body=JSONObject().put("client_id",config().getString("application_id")).put("grant_type","authorization_code").put("code",code).put("code_verifier",flow.getString("verifier")).put("redirect_uri",redirect).put("short_lived",true))
        saveTokens(token)
        vault.update {it.remove("oauth")};store.deleteEntry(flow.getString("alias"))
        return true
    }
    private fun saveTokens(token:JSONObject) {
        check(token.getString("token_type").equals("bearer",true))
        val expires=Instant.parse(token.getString("expires_at")).epochSecond
        check(expires>Instant.now().epochSecond+60 && expires<=Instant.now().epochSecond+86500)
        check(token.getString("refresh_token").isNotBlank())
        val value=JSONObject().put("access",token.getString("access_token")).put("refresh",token.getString("refresh_token")).put("expires",expires).put("application_id",config().getString("application_id"))
        vault.update {it.put(tokenKey(),value)}
    }
    @Synchronized fun token():String {
        val tokens=credentials()?:throw KioskError("SQUARE_SIGN_IN_REQUIRED")
        if(tokens.getLong("expires")>Instant.now().epochSecond+3600) {
            if(!tokens.optBoolean("validated")) {
                val status=request(origin()+"/oauth2/token/status","POST",tokens.getString("access"))
                check(status.getString("client_id")==config().getString("application_id"))
                val granted=status.getJSONArray("scopes");val set=(0 until granted.length()).map {granted.getString(it)}.toSet()
                check(scopes.split(' ').all {it in set})
                vault.update {it.getJSONObject(tokenKey()).put("validated",true)}
            }
            return tokens.getString("access")
        }
        check(!tokens.optBoolean("refresh_inflight")) {"SQUARE_REAUTH_REQUIRED_AFTER_INTERRUPTED_RENEWAL"}
        // PKCE refresh tokens are single-use: never blindly replay after an ambiguous refresh.
        vault.update {it.getJSONObject(tokenKey()).put("refresh_inflight",true)}
        val result=request(origin()+"/oauth2/token","POST",body=JSONObject().put("client_id",config().getString("application_id")).put("grant_type","refresh_token").put("refresh_token",tokens.getString("refresh")).put("redirect_uri",redirect).put("short_lived",true))
        saveTokens(result)
        return result.getString("access_token")
    }
    fun location():String {
        val list=request(origin()+"/v2/locations",token=token()).getJSONArray("locations")
        val requested=config().optString("location_id")
        val candidates=(0 until list.length()).map {list.getJSONObject(it)}.filter {it.optString("status")=="ACTIVE" && it.optString("currency")=="USD" && it.optJSONArray("capabilities")?.toString()?.contains("CREDIT_CARD_PROCESSING")==true && (requested.isEmpty()||it.getString("id")==requested)}
        check(candidates.size==1) {"SQUARE_LOCATION_SELECTION_REQUIRED"}
        return candidates.single().getString("id")
    }
    /** Every Square payment at the record's location carrying its reference, or null if the listing was incomplete. */
    private fun paymentsForReference(record:JSONObject):List<JSONObject>? {
        var cursor:String?=null;val found=mutableListOf<JSONObject>();var complete=false
        repeat(20) {
            if(!complete) {
                val path="/v2/payments?location_id="+StandaloneHttp.encode(record.getString("location"))+"&begin_time="+StandaloneHttp.encode(Instant.ofEpochMilli(record.getLong("created")-300000).toString())+"&sort_order=ASC&limit=100"+(cursor?.let {"&cursor="+StandaloneHttp.encode(it)}?:"")
                val page=request(origin()+path,token=token());val payments=page.optJSONArray("payments")
                if(payments!=null)for(i in 0 until payments.length()) {val p=payments.getJSONObject(i);if(p.optString("reference_id")==record.getString("id"))found+=p}
                cursor=page.optString("cursor").takeIf {it.isNotEmpty()};complete=cursor==null
            }
        }
        return if(complete)found else null
    }
    /** Payments carrying this gift's reference, or null if Square's listing could not be read completely. */
    fun paymentCount(record:JSONObject):Int? {
        check(record.getString("environment")==environment()) {"SQUARE_PAYMENT_ENVIRONMENT_MISMATCH"}
        return paymentsForReference(record)?.size
    }
    /**
     * True only when the SDK explicitly returned no payment, the settle delay has passed, and a complete
     * Square listing shows no payment with this donation's reference. Otherwise normal reconciliation applies.
     */
    fun confirmNoCharge(record:JSONObject):Boolean {
        check(record.getString("environment")==environment()) {"SQUARE_PAYMENT_ENVIRONMENT_MISMATCH"}
        if(!record.optBoolean("sdk_no_payment") || record.optString("payment_id").isNotEmpty())return false
        if(System.currentTimeMillis()-record.getLong("sdk_no_payment_at") < NO_CHARGE_SETTLE_MS)return false
        return paymentsForReference(record)?.isEmpty()==true
    }
    /** SDK already reported no payment; a short settle covers listing lag before Square confirms none exists. */
    companion object { const val NO_CHARGE_SETTLE_MS=10_000L }
    fun verify(record:JSONObject,hint:String?=null):JSONObject {
        check(record.getString("environment")==environment()) {"SQUARE_PAYMENT_ENVIRONMENT_MISMATCH"}
        val paymentId=hint?:record.optString("payment_id").takeIf {it.isNotEmpty()}
        val payment=if(paymentId!=null) {
            check(paymentId.matches(Regex("[A-Za-z0-9_-]{1,192}")))
            request(origin()+"/v2/payments/$paymentId",token=token()).getJSONObject("payment")
        } else {
            val found=paymentsForReference(record)
            check(found!=null && found.size==1) {"SQUARE_ORIGINAL_PAYMENT_NOT_CONFIRMED"}
            request(origin()+"/v2/payments/"+found.single().getString("id"),token=token()).getJSONObject("payment")
        }
        if(paymentId!=null)check(payment.getString("id")==paymentId) {"SQUARE_PAYMENT_ID_MISMATCH"}
        val money=payment.getJSONObject("amount_money");check(money.get("amount") is Int || money.get("amount") is Long)
        val total=payment.getJSONObject("total_money")
        check((total.get("amount") is Int || total.get("amount") is Long) && total.getLong("amount")==record.getLong("cents") && total.getString("currency")=="USD") {"SQUARE_TOTAL_MISMATCH"}
        if(payment.has("refunded_money")&&!payment.isNull("refunded_money")) {
            val refunded=payment.getJSONObject("refunded_money");val amount=refunded.get("amount")
            check((amount is Int || amount is Long) && refunded.getLong("amount")==0L) {"SQUARE_REFUND_RECONCILIATION_REQUIRED"}
        }
        val proof=SquarePaymentProof(payment.getString("id"),payment.optString("reference_id"),payment.getString("location_id"),money.getLong("amount"),money.getString("currency"),payment.getString("status"),payment.getString("source_type"))
        proof.confirm(SquareDispatch(record.getString("id"),record.getString("sdk_attempt_id"),record.getLong("cents")),record.getString("location"))
        return payment
    }
}
