package org.portlandkollel.kiosk.square

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.squareup.sdk.mobilepayments.MobilePaymentsSdk
import com.squareup.sdk.mobilepayments.core.Result.Success
import com.squareup.sdk.mobilepayments.mockreader.ui.MockReaderUI
import com.squareup.sdk.mobilepayments.payment.CurrencyCode
import com.squareup.sdk.mobilepayments.payment.Money
import com.squareup.sdk.mobilepayments.payment.Payment.OnlinePayment
import com.squareup.sdk.mobilepayments.payment.PaymentParameters
import com.squareup.sdk.mobilepayments.payment.ProcessingMode
import com.squareup.sdk.mobilepayments.payment.PromptMode
import com.squareup.sdk.mobilepayments.payment.PromptParameters
import org.portlandkollel.kiosk.core.SquareDispatch
import org.portlandkollel.kiosk.core.SquareDispatchGate

/** A callback is only a hint for server lookup. Nothing here can declare a donation paid. */
sealed interface SandboxSdkOutcome {
    data class NeedsBackendVerification(val referenceId: String, val paymentId: String) : SandboxSdkOutcome
    data class NeedsRecovery(val referenceId: String) : SandboxSdkOutcome
    /** SDK explicitly returned a failure (e.g. donor canceled). Still requires Square to confirm no payment exists. */
    data class SdkReturnedNoPayment(val referenceId: String) : SandboxSdkOutcome
}

/**
 * Opt-in development adapter, owned by Application, never the public screen.
 * Secure enrollment/OAuth credential delivery and runtime permission UI are not implemented.
 * No production initializer, token storage, token prompt or automatic retry exists.
 */
class SquareSandboxSdk(application: Application, applicationId: String, private val locationId: String) {
    private val app = application
    private val gate = SquareDispatchGate(AtomicDispatchJournal(application))
    private var clearAuthorizationCallback: (() -> Unit)? = null

    init {
        require(applicationId.startsWith("sandbox-sq0idb-")) { "Production is disabled." }
        require(Regex("[A-Za-z0-9_-]{1,192}").matches(locationId))
        MobilePaymentsSdk.initialize(applicationId, application)
        check(MobilePaymentsSdk.isSandboxEnvironment()) { "Sandbox SDK required." }
    }

    /**
     * Trusted runtime seam only. This token must come from a reviewed SDK OAuth broker.
     * A proxy-backed server token placeholder is NOT a credential transferable to Android.
     * The app does not retain the argument; Square SDK authorization persistence is SDK-owned.
     */
    fun authorizeFromTrustedRuntime(sandboxOAuthToken: String, onAuthorized: (Boolean) -> Unit) {
        require(sandboxOAuthToken.isNotBlank() && sandboxOAuthToken.none { it.isWhitespace() })
        check(MobilePaymentsSdk.isSandboxEnvironment())
        checkRuntimePermissions()
        clearAuthorizationCallback?.invoke()
        val callback = MobilePaymentsSdk.authorizationManager().authorize(sandboxOAuthToken, locationId) { result ->
            onAuthorized(result is Success && MobilePaymentsSdk.isSandboxEnvironment())
        }
        clearAuthorizationCallback = { callback.clear() }
    }

    fun showMockReaderForAttendedTest() {
        check(MobilePaymentsSdk.isSandboxEnvironment())
        check(MobilePaymentsSdk.authorizationManager().authorizationState.isAuthorized)
        checkRuntimePermissions()
        MockReaderUI.show()
    }

    /**
     * Caller must have backend device authorization + a current trusted staff lease.
     * Dispatch is a server-reserved immutable reference/SDK-ID/amount tuple.
     * This seam is deliberately not exposed from the kiosk's current public UI.
     */
    fun startReservedPayment(dispatch: SquareDispatch, onOutcome: (SandboxSdkOutcome) -> Unit) {
        check(MobilePaymentsSdk.isSandboxEnvironment())
        check(MobilePaymentsSdk.authorizationManager().authorizationState.isAuthorized)
        checkRuntimePermissions()
        val parameters = PaymentParameters.Builder(
            amount = Money(dispatch.cents, CurrencyCode.USD),
            processingMode = ProcessingMode.ONLINE_ONLY,
            allowCardSurcharge = false,
            paymentAttemptId = dispatch.sdkAttemptId,
        ).referenceId(dispatch.referenceId).autocomplete(true).build()
        val prompt = PromptParameters(mode = PromptMode.DEFAULT)
        gate.beforeSdkCall(dispatch) // Durable commit precedes all possible SDK/network side effects.
        try {
            MobilePaymentsSdk.paymentManager().startPaymentActivity(parameters, prompt) { result ->
                val payment = if (result is Success) result.value else null
                if (payment is OnlinePayment) {
                    onOutcome(SandboxSdkOutcome.NeedsBackendVerification(dispatch.referenceId, payment.id))
                } else {
                    onOutcome(SandboxSdkOutcome.NeedsRecovery(dispatch.referenceId))
                }
            }
        } catch (_: Exception) {
            // Keep the durable reservation. An exception never means "safe to charge again".
            onOutcome(SandboxSdkOutcome.NeedsRecovery(dispatch.referenceId))
        }
    }

    private fun checkRuntimePermissions() {
        val required = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 31) {
            required += Manifest.permission.BLUETOOTH_CONNECT
            required += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        check(required.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            "Square Sandbox runtime permissions must be granted by an attended operator."
        }
    }

    fun pendingDispatch(): SquareDispatch? = gate.pending()

    fun deauthorize() {
        clearAuthorizationCallback?.invoke()
        clearAuthorizationCallback = null
        MockReaderUI.hide()
        MobilePaymentsSdk.authorizationManager().deauthorize()
        // Never clear a pending dispatch when deauthorizing or resetting public UI.
    }
}
