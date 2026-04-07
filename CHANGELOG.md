# Changelog

## Unreleased

## 2026-04-06

- Add cache busting for static assets (CSS/JS) using GARDEN_GIT_REVISION as query string
- Set long Cache-Control (1 year, immutable) on static assets so browsers serve from disk cache between deploys

## 2026-04-06

- Fix crash in correction computation when welcome notifications (no event-date) are present

## 2026-04-06

- Improve log viewer: format timestamps as human-readable strings (e.g. "Sun Apr 6, 2026, 17:04.123 CDT") instead of raw instants
- Fix long unbroken strings in log messages causing horizontal scroll on mobile

## 2026-04-06

- Add per-person notification preferences (`:notifications/send-via-email?`, `:notifications/send-via-push?`) with defaults to enabled
- Redesign Settings page: always-visible email checkbox, 3-state push section (not installed / enable button / enabled checkbox)
- Engine skips email or push sending when the person has opted out

## 2026-04-06

- Replace hand-rolled VAPID/RFC 8291 crypto with `nl.martijndwars/web-push` library; delete `vapid.clj` and `encryption.clj`
- Remove `clj-http-lite` dependency (webpush-java handles HTTP internally)
- Add visible success/failure feedback on push notification enable button
- Add `Notification.requestPermission()` call before subscribing (required by iOS)
- Show warning on settings page when VAPID keys are not configured

## 2026-04-06

- Fix push button not showing on iOS: use hyperscript `init` instead of `on load`, consolidate eligibility check into single async `shouldShowPushButton()`

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
