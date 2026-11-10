# How it works

- **The tablet app does everything.** No computer or server needs to stay on. The app talks directly to
  Square (payments), Google Sheets (records) and Gmail (thank-you emails and gift notices).
- **One tiny helper on Cloudflare (free plan).** Square's sign-in needs a web address to return to. The
  Cloudflare Worker in `web/cloudflare-callback` receives that one-time sign-in code, encrypts it for
  your tablet, and forgets it within ten minutes. It never sees card data, payments or long-lived tokens.
- **No secrets in the app.** Square sign-in uses PKCE (no app secret). Google sign-in uses Google Play
  services on the tablet with only two permissions: files the app creates (`drive.file`) and sending
  email (`gmail.send`).
- **Safe payments.** Each gift is saved, encrypted on the tablet, before the card is charged. A gift is
  only marked complete after Square confirms it. If the tablet loses power mid-payment, it asks Square
  what happened rather than charging again.
- **Records.** Each completed gift becomes one row in a private Google Sheet the app creates (including a
  QuickBooks Online import tab). Every attempt, including canceled ones, goes in a second tab.

Code layout:

| Folder | What it is |
| --- | --- |
| `app/` | The Android app (screens, Square, Google, kiosk lock-down) |
| `core/` | Money math, fee-cover formula, donor-detail checks, payment verification (with tests) |
| `square-sandbox/`, `device-security/` | Square test-mode helpers and Android Keystore/TLS helpers |
| `web/cloudflare-callback/` | The small Cloudflare Worker for Square sign-in |
| `.github/workflows/` | Automatic checks, and the signed live build (`live-release.yml`) |
