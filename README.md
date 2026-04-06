# Security Reminder

Clojure web app for scheduling a security duty rotation with email reminders via Application Garden.

## Features

- **Scheduling** — 3 recurring weekly events (Wed evening, Sun morning, Sun evening) with deterministic round-robin assignment and an 8-week rolling projection
- **Absence handling** — Members toggle "I'm out" / "I'm back"; the algorithm auto-substitutes with minimal disruption, avoiding consecutive assignments
- **Email notifications** — garden-email-powered reminders (8-day heads-up + 1-day final), plus correction messages when assignments change. Emails include interactive schedule cards with absence toggle links.
- **Admin tools** — Manage people, override assignments, adjust per-instance staffing, add one-off events, view message history and logs
- **Auth** — Stateless URL-path token auth with no cookies or passwords
- **UI** — Server-rendered HTML with HTMX for live partial updates, styled with Tailwind CSS
- **Tech stack** — Clojure, Integrant, Ring/Jetty, Hiccup, HTMX, garden-email, EDN file-based storage, deployed on Application Garden

## Dev Setup

**Prerequisites:** Clojure CLI, babashka, and Node.js (for Tailwind CSS). If using nix, `direnv allow` handles deps.

```bash
# Copy dev secrets template
cp resources/config/secrets.edn.sample resources/config/secrets.edn

# Start REPL (auto nREPL via lambdaisland/launchpad)
bin/launchpad
# Watch the tailwind files and auto-compile new used classes
bin/watch-resources
```

In the REPL:

```clojure
(user/go)       ;; start the system, automatically done by bin/launchpad
(user/restart)  ;; restart (soft reload, or hard restart if called twice quickly). auto reloads htmx pages

;; can be called at the repl to force all known htmx pages to reload at the same url - useful for style development
(user/refresh-pages-after-eval!)
```

App runs at `http://localhost:3000/{token}/schedule`.

### Dev-only routes

| Route | Purpose |
|-------|---------|
| `/dev/login` | Table of all users with direct links to their schedule pages |
| `/dev/reminder-email` | Preview a sample reminder email using real DB data |
| `/dev/correction-email` | Preview a sample correction email using real DB data |
| `/dev/welcome-email` | Preview a sample welcome email using real DB data |

`deps.local.edn` is gitignored and optional for local dependency overrides.

## Deploying (Application Garden)

```bash
bin/deploy      # build resources, update changelog, commit, deploy
```

This runs `bin/build-resources` (minified CSS/JS), promotes the `## Unreleased` section in `CHANGELOG.md` to today's date, commits everything, and runs `garden deploy`.

### Push Notifications (VAPID keys)

Generate VAPID keys for Web Push:

```bash
bin/generate-vapid-keys
```

- **Dev:** Copy the output into `resources/config/secrets.edn`
- **Prod:** Set as Garden secrets:
  ```bash
  garden secrets add VAPID_PUBLIC_KEY
  garden secrets add VAPID_PRIVATE_KEY
  garden secrets add VAPID_SUBJECT   # MUST include mailto: prefix, e.g. mailto:admin@yourdomain.com
  ```

### Secrets

`GARDEN_STORAGE` is auto-provided and holds `schedule-db.edn` and `schedule-plan.edn`.

### First deploy bootstrap

The app starts with placeholder people from `config.edn`. Replace them with real people via `garden repl`:

```clojure
(require '[dev.freeformsoftware.security-reminder.schedule.engine :as engine])
(def e (get @dev.freeformsoftware.security-reminder.run.prod/!system
            :dev.freeformsoftware.security-reminder.schedule.engine/engine))

;; Remove placeholder people and add real ones:
(engine/list-people e)
(engine/add-person! e {:name "Alice" :email "alice@example.com" :admin? false})

;; Look up access token for a person:
(engine/get-token-for-person e "p-123456")
```

### Useful commands

```bash
garden logs     # view application logs
garden repl     # connect to running REPL
garden sftp     # browse GARDEN_STORAGE files
```

## Config Architecture

| File | Purpose |
|------|---------|
| `resources/config/config.edn` | Base config (always loaded) |
| `resources/config/secrets.edn` | Dev secrets (gitignored) |
| `resources/config/prod-config.edn` | Prod overrides (committed, non-secret) |

Configs are deep-merged in order: `config.edn` -> `secrets.edn` (dev) or `prod-config.edn` (prod). `#n/ref` tags resolve cross-references within the merged config.
