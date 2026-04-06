# Changelog

## Unreleased

## 2026-04-06

- Add Web Push notification support (VAPID, RFC 8291 encryption, clj-http-lite delivery)
- Add Settings page accessible to all users; admin-only pages (Users, Events, Messages, Logs) remain restricted
- Replace Admin button on schedule page with Settings button for all users
- Add push subscription API (subscribe/unsubscribe endpoints)
- Add service worker push and notification click handlers
- Add `bin/generate-vapid-keys` script for VAPID key pair generation
- Wire VAPID keys through config (secrets.edn for dev, Garden env vars for prod)

## 2026-04-06

- Add PWA support: manifest, service worker, app icons, and meta tags for mobile "Add to Home Screen"
- Refactor routes: move handler logic into page namespaces (ui.pages.schedule, ui.pages.admin.{users,events,info}), each exporting a `routes` function; routes.clj now just composes route maps with middleware
- Move input validation helpers (valid-date?, valid-email?, etc.) to route-utils
- Add welcome email template with dev preview route (/dev/welcome-email)
- Support :welcome notification type in sent messages (schema, engine, admin UI)
- Fix email sending crash: patch huff/malli version incompatibility (huff 0.1.x + malli 0.19.x)

## 2026-04-06

- Remove In-Reply-To/References email threading (garden-email API doesn't forward custom headers)

## 2026-04-06

- Fix email threading: wrap bare message-id from garden API in angle brackets for In-Reply-To/References headers

## 2026-04-06

- Allow system to boot when the userdb is broken.
- Fix the config loading so that the GARDEN_STORAGE env var is respected

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
