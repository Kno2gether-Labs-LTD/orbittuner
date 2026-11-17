# Security policy

## Reporting a vulnerability
Please report privately through GitHub: **Security → Report a vulnerability** on this repository. Don't open a public issue for security problems.

Include what you found, how to reproduce it, and the app or service version. We'll acknowledge your report and keep you updated while we fix it.

## Design notes
- The tuner, metronome and backing tracks work entirely on the phone. Microphone audio for tuning never leaves the device.
- **Sing a line** (beta) is the only feature that sends audio: a clip of at most 20 seconds goes to the song service to be turned into words. The service doesn't log or store it.
- No analytics, ads or tracking SDKs. The app talks only to its song service over HTTPS.
- The app's sign-in token is kept in encrypted storage, and Android backup is disabled for the app's data.
- The song service stores portal passwords as salted PBKDF2 hashes, protects portal forms with CSRF tokens, throttles sign-in attempts, and encrypts API keys saved in the portal with a deploy secret (`SECRETS_KEY`).
- Secrets are set with `wrangler secret put` and never live in the repository.
