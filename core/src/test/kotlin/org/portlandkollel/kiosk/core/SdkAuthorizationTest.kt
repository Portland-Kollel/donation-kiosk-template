package org.portlandkollel.kiosk.core

import org.junit.Assert.*
import org.junit.Test

class SdkAuthorizationTest {
    @Test fun alreadyAuthorizedSameLocationIsReusedNotReauthorized() {
        // Regression: Give after a successful Connect must not call SDK authorize again.
        assertEquals(SdkAuthorizationStep.REUSE, SdkAuthorizationPolicy.decide(true, "LOC1", "LOC1"))
    }
    @Test fun unauthorizedSdkAuthorizes() {
        assertEquals(SdkAuthorizationStep.AUTHORIZE, SdkAuthorizationPolicy.decide(false, null, "LOC1"))
        assertEquals(SdkAuthorizationStep.AUTHORIZE, SdkAuthorizationPolicy.decide(false, "LOC1", "LOC1"))
    }
    @Test fun differentOrUnknownLocationReauthorizes() {
        assertEquals(SdkAuthorizationStep.REAUTHORIZE, SdkAuthorizationPolicy.decide(true, "LOC2", "LOC1"))
        assertEquals(SdkAuthorizationStep.REAUTHORIZE, SdkAuthorizationPolicy.decide(true, null, "LOC1"))
    }
    @Test fun blankLocationRejected() {
        assertThrows(IllegalArgumentException::class.java) { SdkAuthorizationPolicy.decide(true, "LOC1", " ") }
    }
}
