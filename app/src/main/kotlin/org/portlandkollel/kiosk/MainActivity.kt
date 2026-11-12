package org.portlandkollel.kiosk

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.lifecycle.*
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.portlandkollel.kiosk.core.*
import org.portlandkollel.kiosk.square.SandboxSdkOutcome

class GivingState:ViewModel() {
    var amount by mutableStateOf("18");var details by mutableStateOf(GivingDetails(""))
    var busy by mutableStateOf(false);var locked by mutableStateOf(false);var authorized by mutableStateOf(false)
    var sandbox by mutableStateOf(false);var live by mutableStateOf(false);var message by mutableStateOf("Staff settings → authorize Square. Real charges disabled.")
    var verified by mutableStateOf<String?>(null);var readers by mutableStateOf<List<String>>(emptyList())
    var staffUnlocked by mutableStateOf(false);var formEpoch by mutableIntStateOf(0)
    /** Donor opted to cover the card fee; rate comes from staff settings (default Square in-person 2.6% + 15¢). */
    var coverFee by mutableStateOf(false);var feeBps by mutableIntStateOf(260);var feeFixed by mutableLongStateOf(15L)
    fun clearForm(){details=GivingDetails("");coverFee=false;formEpoch++}
}

/** All everyday authority, verification and recovery live here/on tablet; no USB or local host. */
class MainActivity:ComponentActivity(),DefaultLifecycleObserver {
    private val state:GivingState by viewModels()
    private val kiosk get()=application as KollelApplication
    private var showPin by mutableStateOf(false);private var pin by mutableStateOf("");private var confirm by mutableStateOf("")
    private var error by mutableStateOf("");private var settings by mutableStateOf(false)
    private var env by mutableStateOf("sandbox");private var appId by mutableStateOf("");private var location by mutableStateOf("")
    private var googleAccount by mutableStateOf("");private var notifyEmails by mutableStateOf("")
    private var feePercent by mutableStateOf("2.6");private var feeCents by mutableStateOf("15");private var depositTo by mutableStateOf("Undeposited Funds")
    private var callbackOrigin by mutableStateOf("");private var weeklyStatus by mutableStateOf(true)
    private var staffStatus by mutableStateOf<List<StaffCheck>>(emptyList());private var selfTestResults by mutableStateOf<List<StaffCheck>?>(null);private var testing by mutableStateOf(false)
    private var confirmRemoveLockdown by mutableStateOf(false);private var changingPin by mutableStateOf(false);private var oldPin by mutableStateOf("");private var newPin by mutableStateOf("");private var newConfirm by mutableStateOf("")
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.message=if(it.values.all {v->v})"Permissions ready. Authorize Square next." else "Check reader permissions in Android settings."
    }
    private val googleConsent=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {result ->
        try {
            val auth=Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data?:throw KioskError("GOOGLE_CONSENT_NOT_COMPLETED"))
            check(!auth.hasResolution() && auth.accessToken!=null)
            KioskLockdown.resumeIfManaged(this)
            if(!GoogleDonations(this,kiosk.vault).hasAllScopes(auth)) {
                state.message="Google allowed only part of the access. Tap Authorize again and tick BOTH boxes (Drive files and Send email)."
            } else {
                kiosk.vault.update {it.put("google_enabled",true)};TabletSyncWorker.now(this)
                state.message="Google authorized. Confirmed donations will sync automatically."
            }
        } catch(_:Exception){KioskLockdown.resumeIfManaged(this);state.message="GOOGLE_CONSENT_NOT_COMPLETED — payment records preserved."}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super<ComponentActivity>.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Waking with the power button returns straight to the kiosk: show over a non-secure (swipe) lock screen
        // and dismiss it. A secure PIN/pattern lock is never bypassed; the device owner also disables the keyguard.
        if(Build.VERSION.SDK_INT>=27) {
            setShowWhenLocked(true);setTurnScreenOn(true)
            getSystemService(android.app.KeyguardManager::class.java).requestDismissKeyguard(this,null)
        }
        state.sandbox=kiosk.mode==KioskMode.SANDBOX;state.live=!state.sandbox&&BuildConfig.REAL_CHARGES_ENABLED
        kiosk.vault.read().let {state.feeBps=it.optInt("fee_bps",260);state.feeFixed=it.optLong("fee_fixed_cents",15)}
        restore()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.RESUMED) {while(true){delay(1000);idleTick()}}}
        setContent {
            if(showPin) AlertDialog(onDismissRequest={showPin=false;pin="";confirm=""},title={Text(if(StaffAccess(this@MainActivity).configured())"Staff access" else "Create staff PIN")},text={Column {
                OutlinedTextField(pin,{if(it.length<=12&&it.all(Char::isDigit))pin=it},label={Text("6–12 digit staff PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
                if(!StaffAccess(this@MainActivity).configured())OutlinedTextField(confirm,{if(it.length<=12&&it.all(Char::isDigit))confirm=it},label={Text("Confirm PIN")},visualTransformation=PasswordVisualTransformation())
                Text(error)
            }},confirmButton={TextButton(onClick=::unlock){Text("Unlock")}},dismissButton={TextButton(onClick={showPin=false;pin="";confirm=""}){Text("Cancel")}})
            // Removing kiosk manager can only be undone with a Mac (ADB) after removing all accounts: confirm first.
            if(confirmRemoveLockdown && state.staffUnlocked) AlertDialog(onDismissRequest={confirmRemoveLockdown=false},
                title={Text("Remove kiosk lock-down?")},
                text={Text("The tablet will stop locking itself and will no longer start into the kiosk. Turning it back on needs the Mac setup again (removing all Google accounts first). For everyday maintenance use Staff exit instead.")},
                confirmButton={TextButton(onClick={confirmRemoveLockdown=false;KioskLockdown.remove(this@MainActivity);state.message="Kiosk manager removed. The tablet behaves normally again."}){Text("Remove permanently")}},
                dismissButton={TextButton(onClick={confirmRemoveLockdown=false}){Text("Keep kiosk lock-down")}})
            if(changingPin && state.staffUnlocked) AlertDialog(onDismissRequest={changingPin=false;oldPin="";newPin="";newConfirm=""},title={Text("Change staff PIN")},text={Column {
                fun digits(v:String)=v.length<=12&&v.all(Char::isDigit)
                OutlinedTextField(oldPin,{if(digits(it))oldPin=it},label={Text("Current PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
                OutlinedTextField(newPin,{if(digits(it))newPin=it},label={Text("New 6–12 digit PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
                OutlinedTextField(newConfirm,{if(digits(it))newConfirm=it},label={Text("Confirm new PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
                Text(error)
            }},confirmButton={TextButton(onClick=::changePin){Text("Change")}},dismissButton={TextButton(onClick={changingPin=false;oldPin="";newPin="";newConfirm=""}){Text("Cancel")}})
            if(settings && state.staffUnlocked) AlertDialog(onDismissRequest={settings=false},title={Text("Tablet configuration")},text={Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                Text("Public Square application details only. No token or application secret.")
                Row {TextButton(onClick={selectEnvironment("sandbox")}){Text("Sandbox")};TextButton(onClick={selectEnvironment("production")}){Text(if(BuildConfig.REAL_CHARGES_ENABLED)"Production (live)" else "Reader setup")}}
                Text("Environment: $env · "+if(env=="production"&&BuildConfig.REAL_CHARGES_ENABLED)"REAL CHARGES ENABLED" else "real charges disabled")
                OutlinedTextField(appId,{appId=it.trim()},label={Text("Square application ID")},singleLine=true)
                OutlinedTextField(location,{location=it.trim()},label={Text("Location ID · optional if one active location")},singleLine=true)
                OutlinedTextField(googleAccount,{googleAccount=it.trim().lowercase(java.util.Locale.ROOT)},label={Text("Organization Google account")},singleLine=true)
                OutlinedTextField(notifyEmails,{notifyEmails=it.lowercase(java.util.Locale.ROOT)},label={Text("Gift notice emails · comma-separated")},singleLine=true)
                OutlinedTextField(depositTo,{depositTo=it.take(100)},label={Text("QuickBooks Deposit To account")},singleLine=true)
                Row {
                    OutlinedTextField(feePercent,{feePercent=it.trim()},label={Text("Card fee %")},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(feeCents,{feeCents=it.trim()},label={Text("Card fee ¢")},singleLine=true,modifier=Modifier.weight(1f))
                }
                OutlinedTextField(callbackOrigin,{callbackOrigin=it.trim()},label={Text("Cloudflare Worker HTTPS origin")},singleLine=true)
                Text("Callback: $callbackOrigin/$env/square/callback")
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(weeklyStatus,{weeklyStatus=it})
                    Text("Weekly status email to the gift-notice addresses (Mondays after 9 am)")
                }
            }},confirmButton={TextButton(onClick=::saveSettings){Text("Save")}},dismissButton={TextButton(onClick={settings=false}){Text("Cancel")}})
            GivingScreen(state.amount,{state.amount=it},state.busy,state.locked,state.authorized,state.message,state.verified,::pay,::recover,state,::finishDonation,onStaffRequest={if(!state.staffUnlocked){error="";showPin=true}}) {
                if(state.staffUnlocked) StaffMenu(staffStatus,selfTestResults,testing,state.busy,onSelfTest=::selfTest,
                    onClose={state.staffUnlocked=false;settings=false;selfTestResults=null},sections=staffSections())
            }
        }
    }
    // ---- Unattended kiosk behavior: idle reset, auto-finish, dimming ----
    private var lastTouch=System.currentTimeMillis();private var idleHandled=false;private var dimmed=false
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
        lastTouch=System.currentTimeMillis();idleHandled=false
        if(dimmed) {setDim(false);if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN)wakeTap=true}
        if(wakeTap){if(event.actionMasked==android.view.MotionEvent.ACTION_UP||event.actionMasked==android.view.MotionEvent.ACTION_CANCEL)wakeTap=false;return true}
        return super.dispatchTouchEvent(event)
    }
    private var wakeTap=false // The tap that wakes a dimmed screen only brightens it; it never presses a button.
    private fun setDim(on:Boolean){dimmed=on;window.attributes=window.attributes.apply {screenBrightness=if(on)DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE}}
    private var ticks=0
    private fun idleTick() {
        if(state.staffUnlocked && ++ticks%5==0)refreshStaffStatus()
        val idle=System.currentTimeMillis()-lastTouch
        if(idle>=DIM_AFTER_MS && !dimmed && !state.busy)setDim(true)
        if(state.busy || idleHandled)return
        if(state.verified in setOf("COMPLETED","FAILED","CANCELED") && idle>=THANK_YOU_MS) {idleHandled=true;finishDonation();return}
        if(!state.locked && idle>=RESET_AFTER_MS) {
            idleHandled=true // Never interrupts a saved payment: locked records stay until verified/closed.
            state.clearForm();state.amount="18";state.staffUnlocked=false;settings=false;showPin=false;changingPin=false;selfTestResults=null
            pin="";confirm="";oldPin="";newPin="";newConfirm="";error=""
        }
    }
    private companion object {const val RESET_AFTER_MS=90_000L;const val DIM_AFTER_MS=120_000L;const val THANK_YOU_MS=15_000L;const val DIM_BRIGHTNESS=0.08f}
    override fun onResume() {
        super<ComponentActivity>.onResume()
        KioskLockdown.apply(this);lastTouch=System.currentTimeMillis();idleHandled=false
        if(kiosk.vault.read().has("oauth"))task {
            if(io {kiosk.session.complete()}) {state.message="Square authorization saved on this tablet.";KioskLockdown.resumeIfManaged(this@MainActivity);authorizeSdk()}
            else state.message="Complete Square consent, then return to this app."
        } else if(kiosk.session.hasCredentials() && !state.authorized)task {authorizeSdk()}
        restore();TabletSyncWorker.now(this);watchForNoCharge()
    }
    override fun onStop(owner:LifecycleOwner){state.clearForm();state.staffUnlocked=false;showPin=false;pin="";confirm="";settings=false;changingPin=false;oldPin="";newPin="";newConfirm=""}
    override fun onDestroy(){ProcessLifecycleOwner.get().lifecycle.removeObserver(this);super<ComponentActivity>.onDestroy()}
    private fun restore() {
        try {
            val record=kiosk.ledger.active()
            state.locked=record!=null || legacyPending()
            if(record!=null) {
                state.amount=GivingSafety.formatCents(record.getLong("cents")).drop(1)
                state.verified=record.getString("status").takeIf {record.optBoolean("square_verified") && it in setOf("COMPLETED","FAILED","CANCELED")}
                if(state.verified!=null)state.message="Square-verified ${state.verified}. Updates are retained independently."
                else state.message=if(record.optBoolean("sdk_no_payment")||record.getString("status")=="LAUNCHED")"Payment not completed. Confirming with Square that no charge was made…" else "Original donation preserved. Check its Square status before another gift."
            }
        } catch(_:Exception){state.locked=true;state.message="SAVED_STATE_UNREADABLE — preserve app data and contact staff."}
    }
    private fun legacyPending():Boolean {
        // Never erase an existing Mac-assisted financial reservation during this migration.
        if(kiosk.ledger.active()!=null)return false
        return listOf("SANDBOX","READER_SETUP").any {mode ->
            java.io.File(noBackupFilesDir,"kiosk-sdk-$mode.json").exists() || PrivateDonationJournal(this,mode).read()!=null
        }
    }
    private fun changePin() {
        val current=oldPin.toCharArray();val next=newPin.toCharArray();val again=newConfirm.toCharArray();oldPin="";newPin="";newConfirm=""
        task {
            val ok=io {runCatching {StaffAccess(this@MainActivity).change(current,next,again)}.isSuccess}
            if(ok){changingPin=false;error="";state.message="Staff PIN changed."} else error="PIN not changed: check the current PIN and that both new PINs match (6–12 digits)."
        }
    }
    private fun unlock() {
        val entered=pin.toCharArray();val confirmation=confirm.toCharArray();pin="";confirm=""
        task {
            val ok=io {val access=StaffAccess(this@MainActivity);if(!access.configured()){access.create(entered,confirmation);true}else access.verify(entered)}
            confirmation.fill('\u0000')
            if(ok){state.staffUnlocked=true;showPin=false;error="";selfTestResults=null;refreshStaffStatus();lifecycleScope.launch {delay(60000);state.staffUnlocked=false;settings=false}}else error="PIN not accepted; wait after five failed attempts."
        }
    }
    private fun editSettings(){env=kiosk.config.optString("environment","sandbox");appId=kiosk.config.optString("application_id");location=kiosk.config.optString("location_id");googleAccount=kiosk.vault.read().optString("google_account");notifyEmails=kiosk.vault.read().optString("notify_emails");kiosk.vault.read().let {feePercent=java.math.BigDecimal(it.optInt("fee_bps",260)).movePointLeft(2).stripTrailingZeros().toPlainString();feeCents=it.optLong("fee_fixed_cents",15).toString();depositTo=it.optString("qb_deposit_to").ifBlank {"Undeposited Funds"}};callbackOrigin=kiosk.config.optString("callback_origin");weeklyStatus=kiosk.vault.read().optBoolean("weekly_status",true);settings=true}
    private fun selectEnvironment(value:String) {
        val saved=kiosk.vault.read().optJSONObject("config_$value")
        env=value;appId=saved?.optString("application_id").orEmpty();location=saved?.optString("location_id").orEmpty()
        callbackOrigin=saved?.optString("callback_origin")?:callbackOrigin
    }
    private fun saveSettings()=task {
        check(state.staffUnlocked)
        check(!state.locked || (kiosk.ledger.active()==null && java.io.File(noBackupFilesDir,"kiosk-sdk-${if(env=="sandbox")"SANDBOX" else "READER_SETUP"}.json").exists()))
        val prefix=if(env=="sandbox")"sandbox-sq0idb-" else "sq0idp-"
        require(appId.matches(Regex(Regex.escape(prefix)+"[A-Za-z0-9_-]{1,128}")))
        require(location.isEmpty() || location.matches(Regex("[A-Za-z0-9_-]{1,192}")))
        require(googleAccount.isEmpty() || googleAccount.matches(Regex("[A-Za-z0-9.!#%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+")))
        val notices=notifyEmails.split(',').map {it.trim()}.filter {it.isNotEmpty()}
        require(feePercent.matches(Regex("[0-9]{1,2}(\\.[0-9]{1,2})?")) && feeCents.matches(Regex("[0-9]{1,3}")))
        val bps=java.math.BigDecimal(feePercent).movePointRight(2).intValueExact();val fixed=feeCents.toLong()
        FeePolicy(bps,fixed) // validates ranges
        require(notices.size<=5 && notices.all {it.matches(Regex("[A-Za-z0-9.!#%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+"))})
        val approvedOrigin=StandaloneHttp.callbackOrigin(callbackOrigin)
        val old=kiosk.config
        val changed=old.optString("application_id")!=appId || old.optString("environment")!=env
        val restart=kiosk.initialized()&&changed
        io {
            val root=kiosk.vault.read()
            check(googleAccount.isBlank() || !root.has("google_account") || root.getString("google_account")==googleAccount || (!root.has("sheet_id") && !root.has("sandbox_test_sheet_id"))) {"GOOGLE_ACCOUNT_MIGRATION_REQUIRES_REVIEW"}
            kiosk.session.configure(JSONObject().put("environment",env).put("application_id",appId).put("location_id",location).put("callback_origin",approvedOrigin))
            if(googleAccount.isNotBlank())kiosk.vault.update {it.put("google_account",googleAccount)}
            require(depositTo.isNotBlank() && depositTo.none {it.isISOControl()})
            kiosk.vault.update {it.put("notify_emails",notices.joinToString(",")).put("fee_bps",bps).put("fee_fixed_cents",fixed).put("qb_deposit_to",depositTo.trim()).put("weekly_status",weeklyStatus)}
        }
        settings=false
        if(restart)state.message="Configuration saved. Restart the tablet to apply the Square environment change."
        else {kiosk.square;recreate()}
    }
    private fun signIn()=task {
        check(state.staffUnlocked)
        if(!kiosk.initialized()){kiosk.square;recreate();return@task}
        val intent=io {kiosk.session.begin()};KioskLockdown.pause(this);startActivity(intent)
        state.message="Kiosk unpinned for Square sign-in. Tap Lock kiosk screen when finished."
    }
    private fun connect()=task {check(state.staffUnlocked);authorizeSdk()}
    private suspend fun authorizeSdk() {
        val token=io {kiosk.session.token()};val loc=io {kiosk.session.location()}
        val model=state;kiosk.square.onReadersChanged={model.readers=it;rememberReaders(it)}
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> {continuation ->
            kiosk.square.authorize(token,loc){ok,message ->state.authorized=ok;state.message=message;if(continuation.isActive)continuation.resumeWith(if(ok)Result.success(Unit) else Result.failure(KioskError("SQUARE_SDK_AUTHORIZATION_FAILED")))}
        }
    }
    private fun pay()=task {
        GivingSafety.requirePayment(kiosk.mode,BuildConfig.REAL_CHARGES_ENABLED);check(!state.locked && !legacyPending())
        state.details.validate();val gift=DonationPolicy().parse(state.amount);val donor=state.details.normalized()
        val fee=if(state.coverFee)FeePolicy(state.feeBps,state.feeFixed).coverFor(gift) else 0L
        val cents=gift+fee;DonationPolicy().validate(cents)
        authorizeSdk()
        val loc=io {kiosk.session.location()}
        val record=io {kiosk.ledger.reserve(cents,donor,loc,kiosk.config.getString("environment"),fee)}
        state.locked=true;state.message=if(state.sandbox)"Complete the Square test payment. This original attempt is saved." else "Complete the card payment on the reader. This original attempt is saved."
        io {kiosk.ledger.change(record.getString("id")){it.put("status","LAUNCHED")}}
        kiosk.square.start(SquareDispatch(record.getString("id"),record.getString("sdk_attempt_id"),cents)) {outcome ->
            when(outcome) {
                is SandboxSdkOutcome.NeedsBackendVerification -> verify(outcome.paymentId)
                is SandboxSdkOutcome.NeedsRecovery -> {TabletSyncWorker.now(this);watchForNoCharge()}
                is SandboxSdkOutcome.SdkReturnedNoPayment -> watchForNoCharge()
            }
        }
    }
    /**
     * Square's payment screen has closed (cancel, error, or a callback that never arrived) while the gift is still
     * only LAUNCHED. Ask Square directly: any payment with this gift's reference → normal verification; two empty
     * listings after the settle delay → close as not charged and unlock. Never depends on the SDK callback alone,
     * never relaunches payment, and retries on its own for about three minutes.
     */
    private var noChargeWatch:Job?=null
    private fun watchForNoCharge() {
        if(noChargeWatch?.isActive==true)return
        val record=runCatching {kiosk.ledger.active()}.getOrNull()?:return
        if(record.getString("status")!="LAUNCHED" || record.optBoolean("square_verified") || record.has("payment_hint"))return
        val id=record.getString("id")
        noChargeWatch=lifecycleScope.launch {
            try {io {kiosk.ledger.change(id){if(!it.optBoolean("sdk_no_payment"))it.put("sdk_no_payment",true).put("sdk_no_payment_at",System.currentTimeMillis())}}}
            catch(e:CancellationException){throw e}catch(_:Exception){return@launch}
            state.message="Payment not completed. Confirming with Square that no charge was made…"
            var empty=0
            repeat(30) {
                delay(6000)
                val current=kiosk.ledger.active()
                if(current==null || current.getString("id")!=id || current.has("payment_hint") || current.optBoolean("square_verified"))return@launch
                val found=try {io {kiosk.session.paymentCount(current)}} catch(e:CancellationException){throw e} catch(_:Exception){null}
                if(found!=null && found>0) {verify();return@launch} // A real payment exists: verify it normally.
                empty=if(found==0)empty+1 else 0
                if(empty>=2 && !state.busy) {
                    val closed=try {closeIfNotCharged()} catch(e:CancellationException){throw e} catch(_:Exception){false}
                    if(closed)return@launch
                }
            }
            state.message="Could not yet confirm with Square. Tap Check payment status; no new attempt is allowed."
        }
    }
    /** Returns true if the active record was a Square-confirmed no-charge cancel and is now closed. */
    private suspend fun closeIfNotCharged():Boolean {
        val record=kiosk.ledger.active()?:return false
        if(!record.optBoolean("sdk_no_payment") || !io {kiosk.session.confirmNoCharge(record)})return false
        io {kiosk.square.archiveClosed(record.getString("id"),"CANCELED","0".repeat(64));kiosk.ledger.closeNoCharge(record.getString("id"))}
        state.locked=false;state.verified=null;state.amount="18";state.clearForm()
        state.message="Payment canceled. No charge was made. Ready for the next gift."
        return true
    }
    private fun verify(hint:String?=null)=task {
        if(hint==null && closeIfNotCharged())return@task
        val original=kiosk.ledger.active()?:io {
            val pending=kiosk.square.pending()?:throw KioskError("LEGACY_REFERENCE_MISSING_PRESERVE_RECORDS")
            val legacy=PrivateDonationJournal(this@MainActivity,kiosk.mode.name).read()
            if(legacy!=null)check(legacy.getLong("cents")==pending.cents)
            val donor=legacy?.optJSONObject("details")?:JSONObject().put("name","").put("email","").put("honor","").put("anonymous",true)
            val file=java.io.File(noBackupFilesDir,"kiosk-sdk-${kiosk.mode.name}.json")
            kiosk.ledger.importLegacy(pending,donor,kiosk.session.location(),kiosk.config.getString("environment"),file.lastModified().takeIf{it>0}?:System.currentTimeMillis(),legacy?.optString("request_key"))
        }
        if(hint!=null)io {kiosk.ledger.change(original.getString("id")){it.put("payment_hint",hint)}}
        val payment=io {kiosk.session.verify(original,hint)}
        io {kiosk.ledger.verified(original.getString("id"),payment)};restore();TabletSyncWorker.now(this)
    }
    private fun recover(){verify()}
    private fun finishDonation()=task {
        val record=kiosk.ledger.active()?:throw KioskError("ORIGINAL_RECORD_REQUIRED")
        check(record.getString("status") in setOf("COMPLETED","FAILED","CANCELED"))
        check(record.optBoolean("square_verified"))
        // A provider-confirmed terminal result is already durable. Closing it must
        // work offline; Sheets/mail failures never strand the previous donor UI.
        kiosk.square.archiveSquareVerified(SquarePaymentProof(record.getString("payment_id"),record.getString("id"),record.getString("location"),record.getLong("cents"),"USD",record.getString("status"),"CARD"),record.getString("location"))
        io {
            if(record.has("legacy_request_key")) {
                val legacy=PrivateDonationJournal(this@MainActivity,kiosk.mode.name)
                if(legacy.read()!=null)legacy.deleteClosed(record.getString("legacy_request_key"))
            }
            kiosk.ledger.close(record.getString("id"))
        }
        state.locked=false;state.verified=null;state.amount="18";state.clearForm();state.message="Thank you. Ready for the next gift; saved updates will retry automatically."
        TabletSyncWorker.now(this)
    }
    private fun authorizeGoogle()=task {
        check(state.staffUnlocked && kiosk.vault.read().has("google_account"))
        // Kiosk lock-down blocks Google's own screens (seen as GOOGLE_AUTHORIZATION_16), so pause it first;
        // it resumes automatically once Google answers.
        KioskLockdown.pause(this)
        val google=GoogleDonations(this,kiosk.vault)
        val result=try {google.authorization()} catch(e:Exception){KioskLockdown.resumeIfManaged(this);throw e}
        if(result.hasResolution()){state.message="Google sign-in open. Tick BOTH boxes, then Continue.";googleConsent.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build());return@task}
        KioskLockdown.resumeIfManaged(this)
        if(!google.hasAllScopes(result))state.message="Google allowed only part of the access. Tap Authorize again and tick BOTH boxes (Drive files and Send email)."
        else {kiosk.vault.update {it.put("google_enabled",true)};TabletSyncWorker.now(this);state.message="Google authorized. Automatic sync is enabled."}
    }
    /** Saves the last reader status the app saw, so the weekly email can report it. */
    private fun rememberReaders(list:List<String>) {
        val text=list.joinToString(", ")
        lifecycleScope.launch {runCatching {io {
            if(list.any(KioskHealth::readerReady))kiosk.vault.update {it.put("reader_ready_ms",System.currentTimeMillis())}
            if(kiosk.vault.read().optString("reader_status")!=text)kiosk.vault.update {it.put("reader_status",text)
                .put("reader_status_at",java.time.ZonedDateTime.now(KioskHealth.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, h:mm a",java.util.Locale.US)))}
        }}}
    }
    private fun refreshStaffStatus(){staffStatus=runCatching {KioskHealth.local(this,kiosk,state.authorized,state.readers)}.getOrElse {listOf(StaffCheck("Status",StaffCheck.Level.FAIL,"Could not read status. Payment records preserved."))}}
    private fun errorCode(e:Exception)=when(e){is RemoteFailure->"HTTP_${e.status}";is KioskError->e.code;else->"NETWORK_OR_ACCOUNT_UNAVAILABLE"}
    /** No-charge check of everything a donation needs; emails the result to staff so email is tested too. */
    private fun selfTest()=task {
        check(state.staffUnlocked)
        testing=true;selfTestResults=null
        try {
            val results=mutableListOf<StaffCheck>()
            fun ok(label:String,detail:String){results+=StaffCheck(label,StaffCheck.Level.OK,detail)}
            fun warn(label:String,detail:String){results+=StaffCheck(label,StaffCheck.Level.WARN,detail)}
            fun fail(label:String,detail:String){results+=StaffCheck(label,StaffCheck.Level.FAIL,detail)}
            if(KioskLockdown.managed(this@MainActivity))ok("Kiosk lock","On.") else fail("Kiosk lock","Off — the tablet is not locked to the kiosk.")
            val signedIn=io {kiosk.session.hasCredentials()}
            if(!signedIn)fail("Square sign-in","Not signed in. Tap Sign in to Square.")
            else try {io {kiosk.session.token()};ok("Square sign-in","Square accepted the tablet's sign-in.")}
            catch(e:CancellationException){throw e}catch(e:Exception){fail("Square sign-in","${errorCode(e)}. Tap Sign in to Square.")}
            if(signedIn && !state.authorized)try {authorizeSdk()} catch(e:CancellationException){throw e} catch(_:Exception){}
            if(state.authorized)ok("Square payments","Connected.") else fail("Square payments","Not connected. Tap Reconnect Square.")
            if(state.authorized) {runCatching {kiosk.square.refreshReaders()};delay(800)}
            when {
                !state.authorized -> warn("Card reader","Not checked (Square not connected).")
                state.readers.any(KioskHealth::readerReady) -> ok("Card reader","Ready.")
                state.readers.isEmpty() -> if(state.sandbox)warn("Card reader","No reader. In Sandbox, use the simulated reader.") else fail("Card reader","No reader found. Charge it, then Pair a reader.")
                else -> fail("Card reader",state.readers.joinToString(", ")+". Try Reconnect reader.")
            }
            KioskHealth.power(this@MainActivity)?.let {if(it.charging)ok("Tablet power","${it.percent}%, plugged in.") else warn("Tablet power","${it.percent}%, NOT plugged in.")}
            val pending=io {KioskHealth.pending(kiosk.ledger.records())}
            if(pending.total==0 && pending.review==0)ok("Waiting to sync","Nothing waiting.") else warn("Waiting to sync","${pending.sheetRows} Sheet row(s), ${pending.emails} email(s), ${pending.review} to check by hand.")
            val root=io {kiosk.vault.read()}
            if(!root.has("google_account"))fail("Google","No Google account set. Open Settings.")
            else try {
                val google=GoogleDonations(this@MainActivity,kiosk.vault)
                val token=google.token()
                ok("Google connection","Connected as ${root.getString("google_account")}.")
                try {ok("Google Sheet",io {google.sheetStatus(token,kiosk.config.optString("environment","sandbox"))})}
                catch(e:CancellationException){throw e}catch(e:Exception){fail("Google Sheet",errorCode(e))}
                val recipients=root.optString("notify_emails").ifBlank {root.getString("google_account")}
                val failed=results.count {it.level==StaffCheck.Level.FAIL}
                val body="Kiosk self-test run from the staff menu"+(if(state.sandbox)" (Sandbox mode)" else "")+". No card was charged.\r\n\r\n"+
                    results.joinToString("") {"${if(it.level==StaffCheck.Level.OK)"OK  " else if(it.level==StaffCheck.Level.WARN)"NOTE" else "FIX "}  ${it.label}: ${it.detail}\r\n"}+
                    "\r\nIf you received this email, sending email works.\r\nApp version ${BuildConfig.VERSION_NAME}.\r\n"
                try {io {google.sendStatus(token,"Kiosk self-test: "+(if(failed==0)"passed" else "$failed problem${if(failed==1)"" else "s"}"),body)};ok("Test email","Sent to $recipients. Check that it arrived.")}
                catch(e:CancellationException){throw e}catch(e:Exception){fail("Test email",errorCode(e))}
            } catch(e:CancellationException){throw e}catch(e:Exception){fail("Google connection","${errorCode(e)}. Tap Connect Google.")}
            selfTestResults=results
            state.message=if(results.none {it.level==StaffCheck.Level.FAIL})"Self-test passed. No card was charged." else "Self-test found problems — see the list below."
        } finally {testing=false;refreshStaffStatus()}
    }
    private fun staffSections():List<StaffSection> {
        val ready=state.authorized&&!state.busy
        return buildList {
            add(StaffSection("Square and card reader","Sign in once; reconnect if payments or the reader stop working.",buildList {
                add(StaffAction("Sign in to Square",!state.busy,onClick=::signIn))
                add(StaffAction("Reconnect Square",!state.busy,onClick=::connect))
                add(StaffAction("Pair a reader",ready) {safe {kiosk.square.readerSettings {state.message=it}}})
                add(StaffAction("Reconnect reader",ready) {safe {kiosk.square.reconnect()};refreshStaffStatus()})
                add(StaffAction("Reader permissions",onClick=::grantPermissions))
                if(state.sandbox)add(StaffAction("Simulated reader",ready) {safe {kiosk.square.mockReader()}})
            }))
            add(StaffSection("Google emails and Sheet","Tick BOTH boxes when Google asks.",listOf(
                StaffAction("Connect Google",!state.busy,onClick=::authorizeGoogle),
                StaffAction("Sync now") {TabletSyncWorker.now(this@MainActivity);showSyncStatus();refreshStaffStatus()})))
            add(StaffSection("Settings","Square IDs, Google account, notice emails, card fee, QuickBooks and weekly email.",listOf(
                StaffAction("Kiosk settings",!state.busy,primary=true,onClick=::editSettings),
                StaffAction("Change staff PIN") {error="";changingPin=true})))
            add(StaffSection("Tablet","Leave the kiosk for maintenance. Restart the tablet or tap Lock kiosk to return.",buildList {
                add(StaffAction("Lock kiosk screen") {KioskLockdown.resume(this@MainActivity);state.message="Kiosk screen pinned."})
                add(StaffAction("Open tablet Settings") {openTabletSettings()})
                add(StaffAction("Staff exit (regular mode)",onClick=::staffExit))
                if(!KioskLockdown.managed(this@MainActivity))add(StaffAction("Enable automatic startup",onClick=::enableBootStartup))
            }))
            if(KioskLockdown.managed(this@MainActivity))add(StaffSection("Repair only","Only when repairing or replacing the tablet. Turning it back on needs a computer.",listOf(
                StaffAction("Remove kiosk lock-down",danger=true) {confirmRemoveLockdown=true})))
        }
    }
    private fun showSyncStatus(){val rows=kiosk.ledger.records().filter {it.getString("status")=="COMPLETED"};val sheets=rows.count {!it.optBoolean("sheet_done")};val emails=rows.count {it.optString("email_status") in setOf("PENDING","SENDING","NEEDS_STAFF_REVIEW")};val last=kiosk.vault.read();state.message="Saved updates: $sheets Sheet rows and $emails emails pending. Google: ${last.optString("google_sync_error").ifEmpty {"ready"}}; Square: ${last.optString("square_sync_error").ifEmpty {"ready"}}. ${KioskLockdown.status(this)}. Payment records preserved."}
    private fun grantPermissions(){val list=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.READ_PHONE_STATE);if(Build.VERSION.SDK_INT>=31)list+=listOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT);if(Build.VERSION.SDK_INT>=33)list+=Manifest.permission.POST_NOTIFICATIONS;if(Build.VERSION.SDK_INT<=29)list+=Manifest.permission.RECORD_AUDIO;permissions.launch(list.toTypedArray())}
    private fun enableBootStartup(){if(!state.staffUnlocked)return;if(Build.VERSION.SDK_INT>=29){val roles=getSystemService(android.app.role.RoleManager::class.java);if(roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)&&!roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME))startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))}}
    /** Staff shortcut: pause lock-down and open Android Settings; restart or Lock kiosk screen re-locks. */
    private fun openTabletSettings() {
        if(!state.staffUnlocked)return
        KioskLockdown.pause(this);state.staffUnlocked=false
        startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        state.message="Tablet Settings opened. Restart the tablet or tap Lock kiosk screen to lock the kiosk again."
    }
    private fun staffExit() {
        if(!state.staffUnlocked)return
        KioskLockdown.pause(this);state.staffUnlocked=false
        // Regular mode: the normal home screen is back until restart or Lock kiosk screen.
        if(KioskLockdown.managed(this)){startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));state.message="Regular mode. Restart the tablet or tap Lock kiosk screen to return to kiosk mode.";return}
        if(Build.VERSION.SDK_INT>=29&&getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_HOME))startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS))else finishAndRemoveTask()
    }
    private fun safe(block:()->Unit){try{block()}catch(_:Exception){state.message="READER_OPERATION_UNAVAILABLE — authorize Square and check permissions."}}
    private suspend fun <T> io(block:()->T):T=withContext(Dispatchers.IO){block()}
    private fun task(block:suspend ()->Unit){if(state.busy)return;state.busy=true;lifecycleScope.launch {try{block()}catch(e:CancellationException){throw e}catch(e:RemoteFailure){state.message="NETWORK_HTTP_${e.status} — saved payment and updates preserved."}catch(e:KioskError){state.message=e.code}catch(_:Exception){state.message="OPERATION_NOT_CONFIRMED — sign in/check connectivity, then reconcile the original gift. Saved records preserved."}finally{state.busy=false}}}
}
