# Unattended kiosk behavior (0.4.3+)

- **No visible menus for donors.** The staff menu opens only by pressing and holding the Portland Kollel logo
  (top-left) for 3 seconds, then entering the staff PIN. Status codes and staff messages are hidden from donors.
- **Idle reset:** after 90 seconds without a touch, any half-finished gift and the staff menu are cleared and the
  start screen returns. A saved, unverified payment is never interrupted.
- **Thank-you screen** closes itself 15 seconds after a Square-verified result.
- **Dimming:** after 2 minutes without a touch the screen dims; the first tap only brightens it and never presses
  a button.
- **Self-locking kiosk (kiosk manager / device owner):** on every start the app pins itself with no prompt and no
  escape gesture, stays the home screen, opens after a restart, and keeps the screen on while plugged in.
  **Regular mode:** Staff → Staff exit unpins the kiosk and gives back the normal home screen and apps. Return to
  kiosk mode by restarting the tablet or Staff → Lock kiosk screen. Staff → Remove kiosk lock-down removes kiosk
  manager permanently (re-enabling needs the ADB command again).

## One-time kiosk-manager setup (owner)
1. Tablet Settings → Security & privacy → Device unlock → Screen lock → **None**.
2. Settings → Passwords & accounts: remove every account temporarily (Android refuses otherwise).
3. Tablet plugged into the Mac, then:

```bash
/opt/homebrew/bin/adb shell dpm set-device-owner org.portlandkollel.kiosk/.KioskAdminReceiver
```

   Expect `Success: Device owner set to package org.portlandkollel.kiosk`.
4. Add the Google account back (needed later for Sheets/email).
5. Open the kiosk once; it pins itself. Restart the tablet to confirm it returns straight to the locked kiosk.
