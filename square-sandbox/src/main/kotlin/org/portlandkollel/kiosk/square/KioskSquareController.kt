package org.portlandkollel.kiosk.square

import android.app.Application
import com.squareup.sdk.mobilepayments.MobilePaymentsSdk
import com.squareup.sdk.mobilepayments.core.Result.Success
import com.squareup.sdk.mobilepayments.core.Result.Failure
import com.squareup.sdk.mobilepayments.mockreader.ui.MockReaderUI
import com.squareup.sdk.mobilepayments.payment.*
import org.portlandkollel.kiosk.core.*
import java.io.File
import android.util.AtomicFile
import org.json.JSONObject

/** Full-app controller. Production payments are reachable only when realChargesEnabled (live release). */
class KioskSquareController(application: Application, val mode: KioskMode, applicationId: String, private val realChargesEnabled: Boolean) {
    private val file = AtomicFile(File(application.noBackupFilesDir, "kiosk-sdk-${mode.name}.json"))
    private var clearReaders: (() -> Unit)? = null
    private var clearAuth: (() -> Unit)? = null
    var onReadersChanged: ((List<String>) -> Unit)? = null
    init {
        val prefix=if(mode==KioskMode.SANDBOX) "sandbox-sq0idb-" else "sq0idp-"
        check(Regex(Regex.escape(prefix)+"[A-Za-z0-9_-]{1,128}").matches(applicationId))
        MobilePaymentsSdk.initialize(applicationId, application)
        check(MobilePaymentsSdk.isSandboxEnvironment() == (mode == KioskMode.SANDBOX))
    }
    /** Location this app last authorized the SDK for; persisted because SDK authorization survives restarts. */
    private val authorizedLocationFile = File(application.noBackupFilesDir, "kiosk-sdk-auth-location-${mode.name}.txt")
    private fun authorizedLocation(): String? = authorizedLocationFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    fun authorize(token: String, location: String, done: (Boolean, String) -> Unit) {
        val manager = MobilePaymentsSdk.authorizationManager()
        when (SdkAuthorizationPolicy.decide(manager.authorizationState.isAuthorized, authorizedLocation(), location)) {
            // Already authorized for this location: calling authorize again fails in the SDK.
            SdkAuthorizationStep.REUSE -> { observeReaders(); done(true, "Square authorized. Reader setup is available."); return }
            SdkAuthorizationStep.REAUTHORIZE -> { clearAuth?.invoke(); clearAuth = null; authorizedLocationFile.delete(); manager.deauthorize() }
            SdkAuthorizationStep.AUTHORIZE -> {}
        }
        clearAuth?.invoke()
        val callback = manager.authorize(token, location) { result ->
            when (result) {
                is Success -> { authorizedLocationFile.writeText(location); observeReaders(); done(true, "Square authorized. Reader setup is available.") }
                is Failure -> done(false, "Square authorization: ${result.errorCode}")
            }
        }
        clearAuth = { callback.clear() }
    }
    private fun observeReaders() {
        clearReaders?.invoke()
        val callback = MobilePaymentsSdk.readerManager().setReaderChangedCallback { refreshReaders() }
        clearReaders = { callback.clear() }; refreshReaders()
    }
    fun refreshReaders() {
        onReadersChanged?.invoke(MobilePaymentsSdk.readerManager().getReaders().map { "Reader: ${it.status::class.simpleName ?: it.status}" })
    }
    fun readerSettings(done: (String) -> Unit) {
        check(MobilePaymentsSdk.authorizationManager().authorizationState.isAuthorized)
        MobilePaymentsSdk.settingsManager().showSettings { result ->
            refreshReaders()
            done(if (result is Success) "Reader settings closed." else "Reader settings could not open.")
        }
    }
    fun reconnect() {
        check(MobilePaymentsSdk.authorizationManager().authorizationState.isAuthorized)
        MobilePaymentsSdk.readerManager().getReaders().forEach { MobilePaymentsSdk.readerManager().retryConnection(it) }
        refreshReaders()
    }
    fun mockReader() {
        check(mode == KioskMode.SANDBOX && MobilePaymentsSdk.isSandboxEnvironment())
        MockReaderUI.show()
    }
    @Synchronized fun pending(): SquareDispatch? {
        val bytes = try { file.readFully() } catch (e: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()) throw e
            return null
        }
        val j = JSONObject(String(bytes, Charsets.UTF_8))
        return SquareDispatch(j.getString("id"), j.getString("sdk_attempt_id"), j.getLong("cents"))
    }
    @Synchronized fun start(dispatch: SquareDispatch, done: (SandboxSdkOutcome) -> Unit) {
        GivingSafety.requirePayment(mode, realChargesEnabled) // BEFORE journal or any payment SDK call.
        check(MobilePaymentsSdk.isSandboxEnvironment() == (mode == KioskMode.SANDBOX))
        check(pending() == null)
        val bytes = JSONObject().put("id", dispatch.referenceId).put("sdk_attempt_id", dispatch.sdkAttemptId).put("cents", dispatch.cents).toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream); check(pending() == dispatch) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        val parameters = PaymentParameters.Builder(amount = Money(dispatch.cents, CurrencyCode.USD), processingMode = ProcessingMode.ONLINE_ONLY,
            allowCardSurcharge = false, paymentAttemptId = dispatch.sdkAttemptId).referenceId(dispatch.referenceId).autocomplete(true).build()
        try {
            // Sandbox has no physical reader: always offer Square's simulated reader before the payment screen.
            if (mode == KioskMode.SANDBOX) runCatching { MockReaderUI.show() }
            MobilePaymentsSdk.paymentManager().startPaymentActivity(parameters, PromptParameters(mode = PromptMode.DEFAULT, additionalPaymentMethods = emptyList())) { result ->
                val payment = if (result is Success) result.value else null
                when {
                    payment is Payment.OnlinePayment -> done(SandboxSdkOutcome.NeedsBackendVerification(dispatch.referenceId, payment.id))
                    result is Failure -> done(SandboxSdkOutcome.SdkReturnedNoPayment(dispatch.referenceId))
                    else -> done(SandboxSdkOutcome.NeedsRecovery(dispatch.referenceId))
                }
            }
        } catch (_: Exception) { done(SandboxSdkOutcome.NeedsRecovery(dispatch.referenceId)) }
    }
    fun archiveSquareVerified(proof:SquarePaymentProof,location:String) {
        val original=pending()?:return
        val status=proof.confirm(original,location)
        check(status in setOf("COMPLETED","FAILED","CANCELED"))
        archiveClosed(proof.reference,status,"0".repeat(64))
    }
    /** Legacy backend closure; standalone caller instead validates a fresh Square proof above. */
    @Synchronized fun archiveClosed(reference: String, status: String, permit: String) {
        check(status in setOf("COMPLETED", "FAILED", "CANCELED") && Regex("[0-9a-f]{64}").matches(permit))
        val dispatch = pending()
        if (dispatch != null) {
            check(dispatch.referenceId == reference)
            val archive = AtomicFile(File(file.baseFile.parentFile, "closed-$reference.json"))
            val current=file.readFully()
            if(archive.baseFile.exists())check(archive.readFully().contentEquals(current)) else {
                val output=archive.startWrite()
                try { output.write(current);output.fd.sync();archive.finishWrite(output) }
                catch(e:Exception) { archive.failWrite(output);throw e }
            }
            check(archive.readFully().contentEquals(current))
            file.delete()
        }
    }
}
