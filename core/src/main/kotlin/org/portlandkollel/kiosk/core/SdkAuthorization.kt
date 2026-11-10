package org.portlandkollel.kiosk.core

/** What to do before using the Square Mobile Payments SDK for a location. */
enum class SdkAuthorizationStep {
    /** SDK already holds authorization for exactly this location: do not call authorize again. */
    REUSE,
    /** SDK is not authorized: call authorize. */
    AUTHORIZE,
    /** SDK is authorized, but for a different or unrecorded location: deauthorize, then authorize. */
    REAUTHORIZE,
}

/**
 * Square documents checking the SDK's existing authorization state before calling
 * authorize. Calling authorize while already authorized fails, which previously
 * surfaced on Give as SQUARE_SDK_AUTHORIZATION_FAILED even after a good sign-in.
 */
object SdkAuthorizationPolicy {
    fun decide(sdkAuthorized: Boolean, authorizedLocation: String?, requestedLocation: String): SdkAuthorizationStep {
        require(requestedLocation.isNotBlank())
        return when {
            !sdkAuthorized -> SdkAuthorizationStep.AUTHORIZE
            authorizedLocation == requestedLocation -> SdkAuthorizationStep.REUSE
            else -> SdkAuthorizationStep.REAUTHORIZE
        }
    }
}
