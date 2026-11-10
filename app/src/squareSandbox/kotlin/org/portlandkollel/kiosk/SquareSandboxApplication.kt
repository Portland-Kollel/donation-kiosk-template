package org.portlandkollel.kiosk

import android.app.Application
import org.portlandkollel.kiosk.square.SquareSandboxSdk

/** No credential input via resources, BuildConfig, Intent, public UI or logs. */
class SquareSandboxApplication : Application() {
    lateinit var square: SquareSandboxSdk
        private set
    override fun onCreate() {
        super.onCreate()
        square = SquareSandboxSdk(this,
            applicationId = BuildConfig.LEGACY_SANDBOX_APPLICATION_ID,
            locationId = BuildConfig.LEGACY_SANDBOX_LOCATION_ID)
    }
}
