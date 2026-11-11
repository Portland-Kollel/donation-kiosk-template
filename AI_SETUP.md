# Build your own donation kiosk with an AI assistant

**How to use this page:** open an AI coding assistant that can work with GitHub (for example
[Claude Code](https://claude.ai/code)), then copy everything in the box below and paste it in as your
first message. The AI will ask you a few questions, make your own copy of the app, and walk you through
setup one step at a time.

You don't need to be a programmer. Plan on an afternoon.

---

```text
You are helping me build my organization's own donation kiosk app, based on this open-source project:
https://github.com/Portland-Kollel/donation-kiosk-template

It is an Android app (Kotlin + Jetpack Compose) for a tablet with a Square card reader. Donors pick an
amount, optionally enter their details, and pay by card. The tablet is locked to the app. Each gift is
emailed to staff, the donor gets a thank-you email, and every gift goes into a Google Sheet (with a
QuickBooks Online import tab).

I am not a programmer. Please:
- Explain in plain language, one step at a time, and wait for me to finish each step before going on.
- Tell me exactly what to click or type. When I need to run a command, give it to me on its own.
- Ask me for screenshots when something looks wrong.

BEFORE YOU START, read these files in the repository and follow them:
AGENTS.md (rules), README.md (overview), CUSTOMIZE.md (what to change), SETUP.md (setup steps).

SAFETY RULES (always follow these):
- Never put passwords, signing keys (.jks files), tokens, API secrets or real donor information in the
  code, in a commit, or in our chat. Never ask me to paste a password or secret to you. I type those
  only into the places that need them (GitHub Secrets, Square, Google, the tablet).
- The app-signing key is created by me, on my computer, and stays with me. You must not create, hold or
  upload it for me.
- Test in Square Sandbox (test mode) first. Real charges happen only after I say so.
- Never tell me to clear the app's data on the tablet once it is live. Gift records are stored there.
- Do not change the payment-safety logic (one payment attempt per gift, checking with Square when a
  result is unknown). Money is whole US cents, never decimals.

STEP 1 - INTERVIEW ME. Ask me these, a few at a time, and wait for my answers:
1. Our organization's full name, and a short name for sentences like "so ___ receives the full $54".
2. Our website address. Look at it for our logo, colors, fonts and how we describe ourselves.
3. Our logo file (I will add it), or permission to use the logo from our website.
4. Main color and accent color (suggest them from our website).
5. Quick-amount buttons (the original uses $18, $36, $54, $108, $180 and a $1 "coffee").
6. Donation categories (e.g. General Donation, Building Fund).
7. The headline and short sentence on the first screen.
8. What donors should be told about tax receipts (e.g. "sent at the end of the year").
9. Which email addresses get a notice for every gift, and which Google account sends the emails.
10. Whether we use QuickBooks Online, and if so our product/service and class names for donations.
11. A unique app ID, e.g. org.ourname.kiosk (suggest one from our website domain).
Then summarize my answers and ask me to confirm.

STEP 2 - MAKE MY COPY. Walk me through clicking "Use this template" on the GitHub page to create my own
repository (Private is fine), then work in my copy.

STEP 3 - CUSTOMIZE. Follow CUSTOMIZE.md and change every item on its checklist using my answers:
name and wording everywhere ("Portland Kollel", "the Kollel"), logo, colors, fonts if I asked,
quick amounts, categories, receipt wording, QuickBooks names, app name, and applicationId (only
applicationId, not namespace or the Kotlin package). Update the tests that check this wording. Keep
the MIT license and credit to Portland Kollel. Show me a short list of what you changed.

STEP 4 - CHECK AND SAVE. Run the project's checks if you can
(./gradlew :core:test :app:assembleDebug :app:lintDebug), or push and let GitHub Actions run them.
Fix any failures and tell me honestly if something still fails. Commit and push to my repository.

STEP 5 - SETUP. Walk me through SETUP.md in order, one step at a time:
signing key on my computer -> GitHub Secrets -> build the live app -> free Cloudflare sign-in helper ->
Square Developer app (Production redirect URL, Android signature, Application ID) -> Google Cloud
project (Drive, Sheets and Gmail APIs; drive.file and gmail.send only) -> prepare the tablet ->
install over USB -> kiosk lock-down command (with my applicationId) -> staff menu settings ->
connect Square, the reader and Google.

STEP 6 - TEST. First a test gift in Sandbox. Then, when I say so, one real $1 gift with my own card:
check the thank-you email, the staff notice and the Google Sheet row, then refund it in the Square
Dashboard and delete the test rows.

STEP 7 - HAND-OFF NOTES. Write a short document in my repository listing our accounts (not
passwords), where each password is kept, our IDs and settings, how to install an update, and a monthly
check-list.

Start with Step 1 now.
```

---

**Hardware we recommend:** a modern Android tablet from Google, Samsung or Lenovo with Android 13 or
newer (not Amazon Fire), the Square Reader (2nd generation, contactless + chip), a locking tablet stand
or wall mount, and long USB-C cables near an outlet. See [README.md](README.md#what-you-need).

**Questions?** See [README.md](README.md#questions).
