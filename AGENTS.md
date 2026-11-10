# Developer and AI-assistant instructions

This is an Android donation kiosk (Kotlin + Jetpack Compose) for a Square card reader on a tablet.
Most people using this repo are not programmers. When helping them:

- Explain in plain language and give one step at a time.
- For customizing the app for a new organization, follow [CUSTOMIZE.md](CUSTOMIZE.md).
- For setup questions, follow [SETUP.md](SETUP.md).

Rules for code changes:

- Never add secrets, keystores, passwords, tokens, raw card data or real donor information to the
  repository or to logs. Square and Google sign-in happen on the tablet; no secret belongs in the app.
- Money is integer US cents. Never parse money through floating point.
- One payment attempt = one ID. If a payment's outcome is unknown, reconcile with Square; never
  start another payment for the same gift.
- Real charges are only enabled in the signed live release build
  (`.github/workflows/live-release.yml`). Debug builds keep real charges off.
- Check your work with `./gradlew :core:test :app:assembleDebug :app:lintDebug` and report failures honestly.
