# Changelog

## Unreleased 

- Allow system to boot when the userdb is broken.

## 2026-04-06

- Lost the user db :/

## 2026-04-06

- Replace SMS/Twilio with email via garden-email (threaded reply chains, rich HTML schedule cards)
- Replace phone numbers with email addresses in person data
- Double security token length (6 → 12 chars), disable token rotation on send
- Add admin page pre-signed links with copy button
- Add GET absence toggle route for email "I'm out" links
- Add dev email preview routes (/dev/reminder-email, /dev/correction-email)
- Improve admin users page: "View schedule" links, responsive button layout
- Fix duplicate :dev alias in launchpad startup

## 2026-03-26

- Fix build to properly add in built artifacts from tailwind and htmx
- Initial release: schedule engine, SMS reminders, HTMX UI
- Application Garden deployment support
- Config via env vars for prod secrets
