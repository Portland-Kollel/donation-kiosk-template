package org.portlandkollel.kiosk

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GivingScreenTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var state:GivingState
    private var payments=0
    private fun show(sandbox:Boolean) {
        compose.setContent {
            if(!::state.isInitialized) state=GivingState().apply { this.sandbox=sandbox;authorized=true;message="" }
            GivingScreen(state.amount,{state.amount=it},state.busy,state.locked,state.authorized,state.message,state.verified,
                {payments++},{},state,{}) {}
        }
    }
    private fun details() {
        compose.onNodeWithText("Just a coffee",substring=true).performClick()
        compose.onNodeWithText("Continue  →").performClick()
        compose.onNodeWithText("First name").performScrollTo().performTextInput("Synthetic")
        compose.onNodeWithText("Last name").performScrollTo().performTextInput("Donor")
        compose.onNodeWithText("Email").performScrollTo().performTextInput("synthetic@example.invalid")
        compose.onNodeWithText("Cell phone").performScrollTo().performTextInput("503 555 0142")
        compose.onNodeWithText("In honor of · optional").performScrollTo().performTextInput("Synthetic dedication")
        compose.onNodeWithText("Review your gift  →").performScrollTo().performClick()
    }
    @Test fun completeFormKeepsSeparateNamesContactAndDedication() {
        show(true);details()
        compose.onNodeWithText("Synthetic dedication").assertExists()
        compose.onNodeWithText("Give in Square Sandbox").performClick()
        compose.runOnIdle {
            assertEquals(1,payments);assertEquals("Synthetic dedication",state.details.honor);assertEquals("1",state.amount)
            assertEquals("Synthetic",state.details.firstName);assertEquals("Donor",state.details.lastName);assertEquals("Synthetic Donor",state.details.name)
            assertEquals("503 555 0142",state.details.phone);assertTrue(state.details.thankYou);assertFalse(state.coverFee)
        }
    }
    @Test fun coveringTheCardFeeUpdatesTheTotal() {
        show(true);details()
        compose.onNodeWithText("Help cover card processing").performClick()
        compose.onNodeWithText("$1.19").assertExists()
        compose.onNodeWithText("Add $0.19 so the Kollel receives the full $1.").assertExists()
        compose.onNodeWithText("%",substring=true).assertDoesNotExist()
        compose.runOnIdle { assertTrue(state.coverFee) }
    }
    @Test fun physicalReaderModeCannotSubmitAnyDonation() {
        show(false);details()
        compose.onNodeWithText("Real donations disabled").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0,payments) }
    }
    @Test fun nothingRequiredAndAnonymousWarnsAboutReceiptAndClearsForm() {
        show(true)
        compose.onNodeWithText("Continue  →").performClick()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithText("Anonymous gifts will not receive a year-end tax-deductible receipt.",substring=true).assertExists()
        compose.onNodeWithText("Review your gift  →").performScrollTo().performClick()
        compose.onNodeWithText("Anonymous").assertExists()
        compose.runOnIdle { state.clearForm() }
        compose.onNodeWithText("Continue  →").assertExists()
        compose.runOnIdle { assertEquals("",state.details.name);assertEquals("",state.details.honor) }
    }
}
