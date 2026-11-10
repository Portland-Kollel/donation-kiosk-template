# Make it yours

The app currently says **Portland Kollel** and uses our logo, categories and wording. Change these
before you build your own copy.

> **Easiest way:** open this repository in [Claude Code](https://claude.ai/code) and paste:
>
> *"Customize this donation kiosk for my organization. Our name is ___, our short name is ___ (used in
> 'so ___ receives the full $54'), our donation categories are ___, and our logo is the file I'm adding.
> Follow CUSTOMIZE.md, change every item on its checklist, then run the checks."*

## Checklist

### 1. Organization name and wording

Search the whole project for `Portland Kollel` and `the Kollel`, and replace them with your
organization's name. The main places are:

| File | What's there |
| --- | --- |
| `app/src/main/kotlin/org/portlandkollel/kiosk/GivingScreen.kt` | Logo description; "Add $X so **the Kollel** receives the full $Y"; receipt wording |
| `app/src/main/kotlin/org/portlandkollel/kiosk/GoogleDonations.kt` | Email "From" name, email sign-off, Google Sheet title |
| `app/src/main/kotlin/org/portlandkollel/kiosk/KioskBootReceiver.kt` | "Open Kollel Giving Kiosk" notification |
| `app/src/main/AndroidManifest.xml` | App name shown on the tablet (`android:label`) |
| `app/src/androidTest/.../GivingScreenTest.kt` | Update the expected fee-cover sentence to match |

Leave the internal names alone. That means `org.portlandkollel.kiosk` (the Kotlin package),
`KollelApplication`, and strings like `kollel-standalone` that name encryption keys. Renaming those
breaks things and nobody sees them.

### 2. Logo

Replace `app/src/main/res/drawable/portland_kollel_logo.png` with your logo. Keep the same file name, or
rename it and update the references in `GivingScreen.kt`. A wide PNG with a transparent background, about
600 pixels wide, works well.

### 3. Donation categories

`core/src/main/kotlin/org/portlandkollel/kiosk/core/Donation.kt` contains:

```kotlin
GENERAL("General Donation"), LEARNING("Kollel Learning"), SEED("Camp SEED"), PARNAS("Parnas HaYom")
```

Change the words in quotes to your own categories.

### 4. Quick amounts and tax-receipt wording

Search `GivingScreen.kt` for `18` / `36` / `54` / `108` / `180` to change the quick amounts. Search for
`tax-deductible` to change what donors are told about receipts. (Our kiosk tells donors their receipt
comes at year end. The kiosk itself only sends a thank-you.)

### 5. QuickBooks names (only if you use the QuickBooks import tab)

In `GoogleDonations.kt`, `QB_PRODUCT` and `QB_CLASS` are set to our QuickBooks product/service
(`Local Donation:Tablet Donation`) and class (`Local Donation`). Change them to match yours. The
"Deposit To" account is a staff setting on the tablet.

### 6. Your own app ID (recommended)

In `app/build.gradle.kts`, change `applicationId = "org.portlandkollel.kiosk"` to something unique to you,
e.g. `org.yourshul.kiosk`. **Change only `applicationId`, not `namespace`.** Do this before your first
install. Changing it later means a fresh install.

When you change it, the kiosk lock-down command in [SETUP.md](SETUP.md) becomes:

```bash
adb shell dpm set-device-owner org.yourshul.kiosk/org.portlandkollel.kiosk.KioskAdminReceiver
```

### 7. Things you set on the tablet, not in code

These are in the staff menu, so there's nothing to edit: Square application ID and location, Cloudflare
Worker address, the Google account that sends emails, who receives gift notices, the card fee (default
2.6% + 15¢ for Square in-person), the QuickBooks deposit account and the staff PIN.

### 8. Check your work

If you have Android Studio or the Android SDK installed:

```bash
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Otherwise just push your changes. GitHub runs the same checks automatically.
