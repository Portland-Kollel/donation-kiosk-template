package org.portlandkollel.kiosk

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KioskLaunchTest {
    @Test fun completeAppLaunchesAndRecreatesWithoutCredentialsOrNetwork() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("org.portlandkollel.kiosk",activity.packageName)
                assertFalse(BuildConfig.REAL_CHARGES_ENABLED)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val app=activity.application as KollelApplication
                app.vault.update {it.put("launch_probe",true)}
                assertTrue(app.vault.read().getBoolean("launch_probe"))
            }
            scenario.recreate()
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }
    @Test fun interruptedGiftReopensWithItsOriginalAmountAndLock() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app=activity.application as KollelApplication
                app.ledger.reserve(100,org.portlandkollel.kiosk.core.GivingDetails("",anonymous=true),"synthetic-location","sandbox")
            }
            try {
                scenario.recreate()
                scenario.onActivity { activity ->
                    val state=androidx.lifecycle.ViewModelProvider(activity)[GivingState::class.java]
                    assertTrue(state.locked);assertEquals("1.00",state.amount)
                }
            } finally {
                scenario.onActivity { activity ->
                    (activity.application as KollelApplication).vault.update {it.remove("active");it.put("records",org.json.JSONArray())}
                }
            }
        }
    }

    @Test fun fullAppSurvivesBothDisplayRotationsWithoutComputerServices() {
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {assertFalse(it.isFinishing)}
                automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {assertFalse(it.isFinishing)}
            } finally {automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)}
        }
    }

}
