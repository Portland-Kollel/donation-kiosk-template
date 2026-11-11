# Setup guide

Follow these in order. Each part is short. Plan on an afternoon the first time.

> **Stuck?** Open this repository in [Claude Code](https://claude.ai/code) and ask: *"I'm on step N of
> SETUP.md and ___ happened."* Or email rbd@portlandkollel.org.

**Contents**

1. [Make your own copy](#1-make-your-own-copy)
2. [Customize it](#2-customize-it)
3. [Create your signing key](#3-create-your-signing-key-once)
4. [Build the app](#4-build-the-app)
5. [Set up the Cloudflare sign-in helper](#5-set-up-the-cloudflare-sign-in-helper-free)
6. [Set up Square](#6-set-up-square)
7. [Set up Google (Sheet + emails)](#7-set-up-google-sheet--emails)
8. [Set up the tablet](#8-set-up-the-tablet)
9. [Test with a real $1 gift](#9-test-with-a-real-1-gift)
10. [Updating](#updating)

---

## 1. Make your own copy

1. Sign in to GitHub (free account).
2. On this repository's page, click **Use this template → Create a new repository**. Choose your
   account or organization, give it a name, and pick **Private** (recommended).
3. In your copy: **Actions** tab → click **"I understand my workflows, go ahead and enable them."**

## 2. Customize it

Follow [CUSTOMIZE.md](CUSTOMIZE.md) to put in your name, logo, categories, and **your own app ID**
(step 6 there). Do this before step 3.

## 3. Create your signing key (once)

Android apps are "signed" with a key file. Every update must be signed with the same key, or the tablet
has to be wiped and reinstalled. **Guard it like a password.**

On your computer you need Java installed (the `keytool` command). Pick your own password when asked.

```bash
keytool -genkeypair -v -keystore ~/kiosk-release.jks -alias kiosk -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Your Organization, O=Your Organization, C=US"
```

Write down two fingerprints. You'll need them in steps 6 and 7:

```bash
keytool -list -v -keystore ~/kiosk-release.jks -alias kiosk
```

- **SHA-256** (for Square): remove the colons and make it lowercase, e.g. `ab12cd34ef56…`
- **SHA1** (for Google): keep as shown, with colons

Save the `.jks` file and password in your password manager, plus one offline backup.

Now give GitHub the key so it can build the app for you. Convert the file to text:

- **Mac:** `base64 -i ~/kiosk-release.jks | pbcopy` (copies it to the clipboard)
- **Windows (PowerShell):** `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\kiosk-release.jks")) | Set-Clipboard`

In your GitHub copy: **Settings → Secrets and variables → Actions → New repository secret**, three times:

| Name | Value |
| --- | --- |
| `KOLLEL_RELEASE_KEYSTORE_B64` | Paste the clipboard |
| `KOLLEL_RELEASE_KEYSTORE_PASSWORD` | Your key password |
| `KOLLEL_RELEASE_KEY_ALIAS` | `kiosk` |

(The names start with `KOLLEL_` because that's what the build file expects. Leave them as they are.)

## 4. Build the app

1. Find the app's version: open `app/build.gradle.kts` and look for `versionName = "…"` (e.g. `0.5.5`).
2. In your GitHub copy, open the branch dropdown (it says `main`), type `live-release/v` plus that
   version (e.g. `live-release/v0.5.5`) and click **Create branch**.
3. Open the **Actions** tab and watch "Live release" run (about 10 minutes).
4. When it's green, go to **Releases** (right side of the repo's main page). Download the `.apk` file and
   `signature.txt`.
5. Open `signature.txt` and check that its SHA-256 matches your key from step 3.

This is the **live** version. It can take real payments once you switch it to Production in the staff
menu. It starts in Sandbox (test mode).

## 5. Set up the Cloudflare sign-in helper (free)

Square's sign-in needs a web address to send the tablet back to. This small Worker provides it. It
never sees card data or payments.

You need [Node.js](https://nodejs.org) installed and a free [Cloudflare](https://dash.cloudflare.com)
account.

```bash
cd web/cloudflare-callback
```

```bash
npm ci --ignore-scripts
```

```bash
npx wrangler login
```

```bash
npx wrangler deploy
```

The last command prints your Worker's address, e.g. `https://kollel-square-callback.yourname.workers.dev`.
Open `wrangler.jsonc`, set `PUBLIC_ORIGIN` to that exact address, and run `npx wrangler deploy` again.
Save the address. This is your **Worker origin**.

## 6. Set up Square

At [developer.squareup.com](https://developer.squareup.com), sign in with your normal Square account.

1. **Create an application** (any name, e.g. "Donation Kiosk").
2. Switch to the **Production** tab at the top.
3. **OAuth → Redirect URL:** `<your Worker origin>/production/square/callback`
4. **Mobile Payments SDK → Add signature → Android:** your app ID from CUSTOMIZE step 6, and the
   **SHA-256** from step 3 (lowercase, no colons).
5. Copy the **Application ID** (starts with `sq0idp-`). You'll type it on the tablet.
6. If you have more than one Square location, copy the **Location ID** of the one the kiosk belongs to
   (Square Dashboard → Account & Settings → Locations).

No secret is needed. The app signs in with PKCE.

## 7. Set up Google (Sheet + emails)

The app creates its own private Google Sheet and sends emails from one Google account, e.g. a
`donations@yourorg.org` mailbox.

At [console.cloud.google.com](https://console.cloud.google.com), signed in as that account:

1. **Create a project** (e.g. "donation-kiosk").
2. **APIs & Services → Library**: enable **Google Drive API**, **Google Sheets API** and **Gmail API**.
3. **APIs & Services → OAuth consent screen**: choose **Internal** if you use Google Workspace.
   Otherwise choose **External** and add the account as a **test user**. Add the scopes
   `.../auth/drive.file` and `.../auth/gmail.send`.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID → Android**: your app ID
   and the **SHA1** fingerprint from step 3.

## 8. Set up the tablet

### 8a. Prepare the tablet

1. *Settings → Security → Screen lock* → **None**.
2. *Settings → Passwords & accounts*: **remove every account for now.** Android won't allow kiosk mode
   otherwise. You'll add the Google account back in 8d.
3. Turn on developer mode: *Settings → About tablet* → tap **Build number** 7 times. Then *Settings →
   System → Developer options* → turn on **USB debugging**.

### 8b. Install the app and lock it down

On your computer, install Android's
[platform-tools](https://developer.android.com/tools/releases/platform-tools) (gives you `adb`). Plug in
the tablet, allow the USB-debugging prompt on it, then from the folder with the downloaded `.apk`:

```bash
adb install -r your-downloaded-file.apk
```

```bash
adb shell dpm set-device-owner org.portlandkollel.kiosk/.KioskAdminReceiver
```

(If you changed the app ID, use the longer form from [CUSTOMIZE.md](CUSTOMIZE.md) step 6.) Expect
`Success: Device owner set…`.

### 8c. Staff menu basics

Open the app. **Press and hold the logo for 3 seconds** to open the staff menu, and create a staff PIN.
Under **Square and Google settings**, enter:

- **Square application ID** (`sq0idp-…`) and **Location ID** (if you have more than one location)
- **Cloudflare Worker HTTPS origin**: your Worker origin from step 5
- **Gift notice emails**: who gets an email for every gift, e.g. `office@yourorg.org`
- **Card fee %** and **¢**: what the fee-cover checkbox adds. The default 2.6% + 15¢ is Square's
  in-person rate.

### 8d. Connect Square, the reader and Google

1. Add your Google account back to the tablet (*Settings → Passwords & accounts*).
2. Staff menu → switch to **Production** → **Sign in to Square** → approve → tap the button to return
   to the kiosk.
3. **Grant reader permissions** → **Reader setup and pairing** → hold the reader's button until its
   lights flash to pair your Square Reader (2nd generation).
4. **Organization Google account** → choose the account → **Authorize organization Google account**.
   Tick **both** permission boxes.
5. **Close staff menu**. Restart the tablet to confirm it comes straight back to the locked kiosk.

## 9. Test with a real $1 gift

1. Give $1 with your own card and your own email.
2. Check you get the thank-you email, staff get the gift notice, and the Google Sheet (in that account's
   Drive) has a new row.
3. Refund it in Square Dashboard, and delete the test rows from the Sheet.

You're live.

### QuickBooks

The Sheet has a **QuickBooks import** tab with only completed gifts, laid out for QuickBooks Online's
sales-receipt import. Set the **QuickBooks Deposit To account** in the staff menu (default: Undeposited
Funds).

### Everyday staff tips

- **Staff menu:** hold the logo 3 seconds → PIN.
- **Staff exit:** the tablet works normally until restarted or until you tap **Pin kiosk screen**.
- **Never clear the app's data.** Unsynced gift records live on the tablet.

---

## Updating

When a new version comes out here:

1. Open your copy in Claude Code and say: *"Bring in the latest changes from
   github.com/Portland-Kollel/donation-kiosk-template, keeping my customizations."*
2. Create a new `live-release/v…` branch with the new version number from `app/build.gradle.kts` (step 4).
   If you change the app yourself, raise `versionCode` and `versionName` there first: each version can
   only be released once.
3. Download the new `.apk`, plug in the tablet, and run:

```bash
adb install -r your-new-file.apk
```

Because it's signed with the same key, it installs over the old version and keeps all records.
