# Donation Kiosk for Square

**A locked-down tablet donation kiosk for shuls, schools and nonprofits. Free to use and adapt.**
Built by [Portland Kollel](https://portlandkollel.org).

---

We started with a basic Square reader and the Square app, and it had real problems. Kids could play
with the app, and we couldn't track who gave what.

So we built our own kiosk app with Claude Code. It runs on the Square reader and is fully locked down,
so donors can only make a donation and nothing else.

What it does:

- **Works with the reader you already have.** We used Square. You can build the same idea around a
  PayPal or Stripe reader (see [Using a different reader](#using-a-different-reader)).
- **Lets donors cover the fee.** Square's fees are higher, but the app asks donors whether they'd like
  to cover the exact fee, and most of them do.
- **Sends emails automatically.** Every donor who gives an email gets a thank-you, and your staff get a
  notice for every gift.
- **Keeps records for you.** Each donation goes straight into a Google Sheet, including a tab ready to
  import into QuickBooks Online.

---

## What the donor sees

1. **Pick an amount**: quick buttons ($18, $36, $54, $108, $180, $1 coffee) or type any amount
2. **Their details (all optional)**: name, email, cell, address, "in honor / in memory of", or *Give
   anonymously*
3. **Review**: with an optional "Add $1.60 so we receive the full $54" checkbox
4. **Tap, insert or swipe** on the Square reader
5. **Thank-you screen**, then back to the start after 15 seconds

## Kiosk features

- The tablet is **fully locked to the app**. There's no home button, no other apps and no settings.
  It restarts straight into the kiosk.
- **Hidden staff menu**: press and hold the logo for 3 seconds, then enter a PIN.
- Resets after 90 seconds of no activity and dims the screen when idle.
- Each gift is saved, encrypted on the tablet, before the card is charged. If anything is interrupted,
  the app asks Square what happened. It never charges twice.
- Nothing needs to stay running on a computer. The only online helper is a free Cloudflare Worker for
  Square sign-in.

## What you need

| Item | Notes |
| --- | --- |
| Android tablet | We use a Google Pixel Tablet standing upright. Android 9 or newer. |
| Square Reader | Contactless + chip reader that pairs with Android |
| Square account | The same one you already use |
| Google account | Google Workspace works best. Used for the Sheet and emails. |
| Cloudflare account | Free plan |
| GitHub account | Free. Builds the app for you, so there's nothing to install for that step. |
| A computer for setup | Mac, Windows or Linux, to install the app on the tablet once over USB |

## Get started

1. **[SETUP.md](SETUP.md)**: step-by-step setup, from zero to taking donations.
2. **[CUSTOMIZE.md](CUSTOMIZE.md)**: put in your organization's name, logo, wording and categories.

> **Not a programmer?** Neither are most of us. Open this repository in
> [Claude Code](https://claude.ai/code) and say: *"Help me set up this donation kiosk for my
> organization, one step at a time. Start with CUSTOMIZE.md."*

## Using a different reader

The app is built around Square's Mobile Payments SDK. If you use a Stripe Terminal or PayPal/Zettle
reader, the screens, kiosk lock-down, fee cover, Google Sheet and emails can all stay the same. Only the
payment part (`app/src/main/kotlin/.../TabletSquare.kt` and the Square verification in `core/`) needs to
be swapped. Ask Claude Code: *"Replace the Square payment code with Stripe Terminal, keeping everything
else."*

## Updates

This repository is updated automatically whenever Portland Kollel releases a tested version. To get
updates in your own copy, use **Sync fork** on GitHub, then build a new release (see
[SETUP.md, Updating](SETUP.md#updating)). Your tablet keeps its records when you install an update
signed with the same key.

## Questions

We're happy to help. Email Boruch Dov Diskind at **rbd@portlandkollel.org**.

## License

[MIT](LICENSE). Free to use, change and share. The Portland Kollel name and logo are ours, so please
swap in your own.

*This software is provided as-is. You are responsible for your own payment processing, tax receipts and
compliance.*
