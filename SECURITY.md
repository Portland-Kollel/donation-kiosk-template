# Security

- Never commit keystores, passwords, tokens, API keys or real donor data. `.gitignore` already blocks the
  common ones, but check before you push.
- Keep your release signing key (`.jks` file) and its password in a password manager plus one offline
  backup. Losing it means every tablet has to be reinstalled from scratch.
- Square's reader handles the card. The app never sees card numbers. Using Square does not by itself make
  you PCI compliant; check your obligations with Square.
- Found a security problem in this project? Please do not post details in a public issue. Email the
  contact in [README.md](README.md) instead.

See [ARCHITECTURE.md](ARCHITECTURE.md) for how payments, sign-in and records are protected.
