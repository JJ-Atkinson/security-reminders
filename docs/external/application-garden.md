# application.garden Documentation

> Source: https://application.garden | Docs: https://docs.apps.garden
> Last updated: 2026-03-22

---

## Official Documentation (from docs.apps.garden)

application.garden is a platform for hosting small web applications written in Clojure. It focuses on providing a good developer experience and having a low barrier entry to quickly deploy your projects.

It provides good out-of-the-box solutions for common problems:

- getting a public domain with https
- interacting with your live application in production through a REPL
- persisting storage across application restarts
- managing secrets
- single-sign-on and user account management
- sending and receiving email
- scheduling background tasks

---

## Getting Started

To develop for application.garden, you first need to install the garden CLI.

### Installing the CLI

There are a few options for installing the `garden` command line utility.

#### Using brew

MacOS binaries are provided using brew.

```bash
brew update
brew install nextjournal/brew/garden
```

#### Using nix

MacOS and Linux packages are available as a nix flake at `github:nextjournal/garden-cli`. Make sure you have a recent nix with flakes enabled.

Install into a profile:

```bash
nix profile install github:nextjournal/garden-cli
```

Run without installing:

```bash
nix run github:nextjournal/garden-cli
```

#### Using standalone binaries

You can download standalone binaries for MacOS and Linux from [GitHub releases](https://github.com/nextjournal/garden-cli/releases).

### Creating a Project

1. Create an empty directory: `mkdir my-project`
2. Enter the directory: `cd my-project`
3. Create a garden project: `garden init`

This creates a minimal example project, along with a `garden.edn` file with settings for your project.

You can also register an existing Clojure project with application.garden, by running `garden init` from the existing project's root directory. Make sure to add a `:nextjournal/garden` alias with an `:exec-fn` for your application's entrypoint to the project's `deps.edn` like this:

```clojure
{:deps {io.github.nextjournal/clerk {:git/sha "4c4983182061abd24201cd43fab6ee55dc16b7e8"}}
 :aliases
 {:nextjournal/garden {:exec-fn nextjournal.clerk/serve!}}}
```

Your application needs to listen for HTTP requests on port 7777 and respond with a 200 status code to HEAD requests to `/`.

### Deploying a Project

application.garden uses Git to deploy your project. This means you need to commit your code and the `garden.edn` configuration file before you can deploy it.

```bash
git add .
git commit
garden deploy
```

This will push the code of the latest commit to application.garden and start the application. You will see the start-up messages of your application, until it is ready. An application is considered ready when `HEAD /` succeeds with status 200.

You can now visit your application at the URL printed in the command output.

If you want to see your application output after launch, use `garden logs`.

Call `garden help` for an overview of all available commands.

---

## nREPL Support

By default we automatically inject a nREPL server into your deployed application.

Use `garden repl` to tunnel to the remote nREPL server and open an interactive REPL session connected to your deployed application.

You can also connect your editor to the nREPL server through the tunnel (e.g. using `cider-connect` from Emacs).

If you do not need the REPL on the terminal, you can run without it: `garden repl --headless`.

To evaluate little snippets non-interactively use `garden repl --eval '(System/getenv "GARDEN_PROJECT_NAME")'`.

You can disable automatically injecting an nREPL server using the `:skip-inject-nrepl` option in `garden.edn` or during deploy (`garden deploy --skip-inject-nrepl`). You can then start your own nREPL server configured however you like from your app, as long as it is listening on the port in the `"GARDEN_NREPL_PORT"` environment variable and binding to `"0.0.0.0"`:

```clojure
(require '[nrepl.server :as nrepl])
(nrepl/start-server {:port (System/getenv "GARDEN_NREPL_PORT") :bind "0.0.0.0"})
```

---

## Deployment Strategies

There are two strategies of how to update deployments:

- **`:zero-downtime`** (default): Spawn a second instance of your app and redirect traffic after it has started up and responds with status 200 on `HEAD /`. There is no downtime on failure.

- **`:restart`**: First stop the running instance, then spawn a new instance. Use this strategy, when your application cannot handle multiple instances running at the same time (using Datomic Local for example requires exclusive access to your persistent storage).

Use with e.g. `garden deploy --deploy-strategy restart` or configure it in the project's `garden.edn` file:

```clojure
{:project "my-project"
 :deploy-strategy :restart}
```

---

## Monorepo Support

If you are developing in a monorepo, or you want to develop an application.garden app and some libraries from the same place, you can run `garden init` and `garden deploy` from a subdirectory. In this case, the app defined in the `deps.edn` of the same subdirectory is started. You can refer to other libraries of the repo using relative paths.

---

## Groups

By default, the application.garden developer who registered a project owns it and has sole control. You can allow other developers to update and deploy your project, by adding it to a group using `garden groups`.

Example:

```bash
# create a new group
garden groups create --group-handle "my-group"
# add the current project to the group
garden groups add-project --group-handle "my-group"
# add another developer to the group
garden groups add-member --group-handle "my-group" --person-nickname "bob"
```

---

## Storage

Each application has a storage volume mounted at a path passed in the `GARDEN_STORAGE` environment variable. The contents of this directory are preserved across restarts and updates of your project. Note that data in the file system outside this directory is volatile.

Write to a file in persistent storage:

```clojure
(spit (str (System/getenv "GARDEN_STORAGE") "/foo.txt") "hello world")
```

Read from a file in storage:

```clojure
(slurp (str (System/getenv "GARDEN_STORAGE") "/foo.txt"))
```

You can also access storage via SFTP:

```bash
garden sftp
```

Local development: `garden run` creates `.garden/storage` directory locally.

---

## Secrets

You can provide secrets to your application using `garden secrets`. Secrets are available to your application as environment variables.

Add secret:

```bash
garden secrets add foo
```

Read secret:

```clojure
(System/getenv "foo")
```

Secret names must match `[a-zA-Z_]+`.

---

## Domains

Each project gets a public domain at `https://<project-name>.apps.garden` by default.

If you want to use a custom domain, use `garden publish --domain <your_custom_domain>` and follow the instructions. You need to be able to manage DNS records for your custom domain.

---

## User Accounts (Garden ID)

application.garden removes the hassle of managing user accounts and logins using Garden ID. By signing up once at Garden ID or logging in with a Github or Apple account, users can access any application.garden project using Garden ID, without the need to register a new account every time.

---

## Email (Garden Email)

It's easy to send and receive email from projects on application.garden using Garden Email.

---

## Scheduling Background Tasks (Garden Cron)

You can run periodic background tasks with Garden Cron. It's a simple scheduler for repeated executions and useful for example if you need to run clean-up jobs for the database, fetch data in the background, or notify your users periodically.

---

## Project Templates

`garden init` is compatible with deps-new templates. If you invoke `garden init` in an empty directory, it will create a project based on the default template.

You can use other templates with the `--template` option:

```bash
garden init --template io.github.nextjournal/garden-template-clerk
```

Check out the [application-garden-template topic on GitHub](https://github.com/topics/application-garden-template) to find more templates for use with application.garden. If you create your own template and want to share it, add the `application-garden-template` topic to your project.

---

## Examples

Check out the [application-garden topic on GitHub](https://github.com/topics/application-garden) for examples and libraries for use with application.garden.

---

---

# CLI Reference (from source analysis)

Current version: **v0.1.15**

## All Commands

### Project Management

| Command | Description |
|---------|-------------|
| `garden init` | Initialize a new project in the local directory |
| `garden deploy` | Deploy a project to application.garden |
| `garden run` | Run a project locally (simulates garden env) |
| `garden list` | List your projects and their status |
| `garden info` | Show information about a project |
| `garden rename <new-name>` | Rename a project |
| `garden stop` | Stop a running project |
| `garden restart` | Restart a running project |
| `garden delete` | Permanently delete a project and all data |

### Monitoring

| Command | Description |
|---------|-------------|
| `garden logs` | Show project logs on stdout |
| `garden logs -n 100` | Show last 100 log lines |
| `garden logs -f json` | Logs in JSON format (also: `text`, `raw`, `edn`) |
| `garden stats` | Show traffic statistics |

### Development

| Command | Description |
|---------|-------------|
| `garden repl` | Open nREPL connected to deployed project |
| `garden repl --headless` | Only tunnel nREPL (connect your own editor) |
| `garden repl -e "(+ 1 2)"` | Evaluate expression remotely |
| `garden repl --port 6666` | Use specific local port for tunnel |
| `garden sftp` | SFTP session to persistent storage |

### Secrets

| Command | Description |
|---------|-------------|
| `garden secrets list` | List all secrets for a project |
| `garden secrets add <name>` | Add a secret (prompts for value) |
| `garden secrets add <name> --force` | Overwrite existing secret |
| `garden secrets remove <name>` | Remove a secret |

### Custom Domains

| Command | Description |
|---------|-------------|
| `garden publish <domain>` | Publish to custom domain |
| `garden publish <domain> --remove` | Remove custom domain |

### Groups

| Command | Description |
|---------|-------------|
| `garden groups list` | List your groups |
| `garden groups create <handle>` | Create a group |
| `garden groups delete <handle>` | Delete a group |
| `garden groups add-member <handle> --person-nickname <nick>` | Add member |
| `garden groups remove-member <handle> --person-nickname <nick>` | Remove member |
| `garden groups list-members <handle>` | List members |
| `garden groups add-project <handle> --project <project>` | Add project to group |
| `garden groups remove-project <handle> --project <project>` | Remove project |
| `garden groups list-projects <handle>` | List group projects |

### Other

| Command | Description |
|---------|-------------|
| `garden version` | Print CLI version |
| `garden help [command]` | Show help |

### Global Options

| Option | Description |
|--------|-------------|
| `--quiet` / `-q` | Suppress output |
| `--output-format edn\|json` | Machine-readable output |
| `--help` | Show help for command |
| `--debug` | Enable debug output (prints SSH commands) |
| `--project <name>` | Specify project (default: from `garden.edn`) |

### Deploy Options

| Option | Description |
|--------|-------------|
| `--git-ref <ref>` | Git branch/commit/tag to deploy (default: HEAD) |
| `--force` / `-f` | Force deploy even if code unchanged |
| `--deploy-strategy <mode>` | `:zero-downtime` (default) or `:restart` |
| `--skip-inject-nrepl` | Don't inject nREPL server |

### Init Options

| Option | Description |
|--------|-------------|
| `--project <name>` | Project name (random if omitted) |
| `--force` / `-f` | Overwrite existing `garden.edn` |
| `--template <name>` | deps-new template (default: `io.github.nextjournal/garden-template`) |

---

## Configuration Files

### garden.edn

```clojure
{:project "my-project-name"          ; Required. Project identifier.
 :deploy-strategy :restart           ; Optional. :zero-downtime (default) or :restart
 :ssh-server "user@host:port"        ; Optional. Override SSH destination.
 :skip-inject-nrepl true}            ; Optional. Don't inject nREPL server.
```

### deps.edn (application.garden specific)

```clojure
{:deps { ... }
 :aliases
 {:nextjournal/garden
  {:exec-fn my-ns/start!             ; Entry point function
   :exec-args {:port 7777}}}}        ; Optional args passed to start!
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `GARDEN_PROJECT_NAME` | Your project name |
| `GARDEN_STORAGE` | Path to persistent storage directory |
| `GARDEN_EMAIL_ADDRESS` | Your project's email address |
| `GARDEN_URL` | Your project's public URL |
| `GARDEN_NREPL_PORT` | nREPL port |
| `GARDEN_PORT` | Application port (used by garden-clerk) |

---

---

# Library Documentation

## garden-id (Authentication)

**Repository:** https://github.com/nextjournal/garden-id
**License:** ISC

Simplified authentication for application.garden based on OpenID Connect.

### Installation

```clojure
;; deps.edn
{io.github.nextjournal/garden-id {:git/sha "<latest-sha>"}}
```

### Usage

Wrap your Ring app with `ring.middleware.session/wrap-session` and `nextjournal.garden-id/wrap-auth`.

Redirecting to the path in `nextjournal.garden-id/login-uri` will send the user to a login page. Upon successful login it redirects to `/` and user data is stored in the session.

In local development authentication is mocked and you can impersonate arbitrary users.

```clojure
(require '[nextjournal.garden-id :as garden-id]
         '[ring.middleware.session :as session]
         '[ring.middleware.session.cookie :refer [cookie-store]])

(def wrapped-app
  (-> app
      (garden-id/wrap-auth)
      (session/wrap-session {:store (cookie-store)})))
```

### Login/Logout

```clojure
garden-id/login-uri              ; path to redirect for login
garden-id/logout-uri             ; path to redirect for logout
(garden-id/logged-in? request)   ; => true/false
(garden-id/get-user request)     ; => user map from session
```

### Authorization

You can configure authorization by passing a map as the second argument to `nextjournal.garden-id/wrap-auth`.

#### GitHub

```clojure
(garden-id/wrap-auth my-app {:github [RESTRICTIONS...]})
```

Possible restrictions:
- `{:login "githubhandle"}` — the user `githubhandle`
- `{:id 1234567}` — the user with GitHub ID 1234567
- `{:login ifn}` — call ifn with GitHub login handle, pass if returns true
- `{:id ifn}` — call ifn with GitHub id, pass if returns true
- `{:organization "myorg"}` — members of the organization `myorg`
- `{:organization "myorg" :team "myteam"}` — members of team `myteam` of organization `myorg`

The user is permitted if they pass **any** listed restriction.

You need a valid GitHub API token in the environment variable `GITHUB_API_TOKEN` that is scoped to read the organization members. Use `garden secrets add GITHUB_API_TOKEN` to set this.

#### Apple ID

```clojure
(garden-id/wrap-auth my-app {:apple []})
```

---

## garden-email (Email)

**Repository:** https://github.com/nextjournal/garden-email

A small helper library to send and receive email with application.garden.

### Installation

```clojure
{io.github.nextjournal/garden-email {:git/sha "<latest-sha>"}}
```

### My Email Address

Your own email address is available in `nextjournal.garden-email/my-email-address`. You can send email from this address, including plus-addresses, and receive email at this address.

```clojure
(require '[nextjournal.garden-email :as garden-email])

garden-email/my-email-address

(garden-email/plus-address "foo@example.com" "bar")
; => "foo+bar@example.com"
```

### Sending Email

```clojure
(garden-email/send-email! {:to {:email "foo@example.com"
                                :name "Foo Bar"}
                           :from {:email garden-email/my-email-address
                                  :name "My App"}
                           :subject "Hi!"
                           :text "Hello World!"
                           :html "<html><body><h1>Hello World!</h1></body></html>"})
```

Every parameter except for `{:to {:email "..."}}` is optional.

### Double-Opt-In

The first time you send an email to a new email address, the recipient gets a generic email to confirm receipt preferences. Your original email gets buffered and sent to the recipient once they confirm. You are blocked from sending more email to that address until the recipient confirms. After confirmation, you can continue sending as usual. application.garden automatically adds an unsubscribe footer to every email.

### Receiving Email

```clojure
(defn on-receive [{:keys [message-id from to reply-to subject text html]}]
  (println (format "Received email from %s to %s with subject %s." from to subject)))

(def wrapped-ring-handler
  (-> my-ring-handler (garden-email/wrap-with-email {:on-receive on-receive})))
```

If you do not provide a custom callback, garden-email saves incoming email to persistent storage, which you can interact with using:

- `inbox`
- `save-to-inbox!`
- `delete-from-inbox!`
- `clear-inbox!`

### Development

When running locally in development, no actual emails are sent. Instead mock-emails are collected, viewable at the URL in `nextjournal.garden-email/outbox-url`, assuming you have added the ring middleware to your handler.

To mock incoming email, you can call `nextjournal.garden-email/receive-email`.

### Mailbox Rendering

`nextjournal.garden-email.render` has helper functions to render a mailbox.

---

## garden-cron (Scheduled Tasks)

**Repository:** https://github.com/nextjournal/garden-cron

The purpose of `garden-cron` is to run a function repeatedly on a schedule, specified in a syntax akin to crontab. Uses [chime](https://github.com/jarohen/chime) internally.

### Installation

```clojure
{io.github.nextjournal/garden-cron {:git/sha "<latest-sha>"}}
```

### Usage

```clojure
(require '[nextjournal.garden-cron :refer [defcron]])

(defn rooster [_time]
  (println "Cock-a-doodle-doo!"))

(defcron #'rooster {:hour [6] :weekday (range 1 6)})
```

This will make the rooster wake you at 6am in the morning, but only on weekdays.

### Cron Expression Syntax

A cron expression is a map with these keys:

| Key | Values | Default |
|-----|--------|---------|
| `:month` | integers from 1 to 12 | every month |
| `:day` | integers from 1 to 31 | every day |
| `:weekday` | integers from 1 (Mon) to 7 (Sun). 0 is NOT permitted | every weekday |
| `:hour` | integers from 0 to 23 | 0 (or every hour if only :minute/:second specified) |
| `:minute` | integers from 0 to 59 | 0 (or every minute if only :second specified) |
| `:second` | integers from 0 to 59 | 0 |

Values can be:
- A vector, list or set of numbers: `[6]`, `#{0 30}`, `(range 0 60 5)`
- The value `true` or `:*` to always activate

A cron expression triggers when **all** its keys trigger, subject to the following defaults:

- A cron expression triggers on every month, unless specified.
- A cron expression triggers on every day, unless specified.
- A cron expression triggers on every weekday, unless specified.
- A cron expression triggers on hour 0, unless specified.
- A cron expression triggers on minute 0, unless specified.
- A cron expression triggers on second 0, unless specified.
- Additionally, if only minutes resp. only seconds are specified, it triggers on any hour resp. any hour and minute, as well. In doubt be more explicit.

### Disabling a Schedule

A cron expression can be disabled by calling `defcron` without a schedule (second argument). This is primarily useful during development.

```clojure
(defcron #'my-fn)  ; disables scheduling
```

An optional third argument to `defcron` specifies the starting time; it defaults to `ZonedDateTime/now`. This can be used to match against a different time zone or delay scheduling until the software is started.

### Additional Functions

- **`next-cron`** — Computes the next trigger moment, given a cron schedule and a time. It will always be at least 1 second after the given time.
- **`cron-seq`** — Computes an infinite list of when a cron schedule triggers, suitable for `chime-at`.
- **`cron-merge`** — Merges multiple, potentially infinite, lists of instants in chronological order. This can be used if you need more flexibility than a single cron schedule provides.

```clojure
(nextjournal.garden-cron/next-cron {:hour [6]} (java.time.ZonedDateTime/now))
(nextjournal.garden-cron/cron-seq {:hour [6]})
(nextjournal.garden-cron/cron-merge
  (cron-seq {:minute (range 0 60 5)})
  (cron-seq {:minute (range 1 60 5)}))
```

---

## garden-clerk (Clerk Notebooks)

**Repository:** https://github.com/nextjournal/garden-clerk

A helper library for serving [Clerk](https://clerk.vision) notebooks on application.garden.

### Installation

```clojure
{io.github.nextjournal/garden-clerk {:git/sha "<latest-sha>"}}
```

### Static Build

```clojure
{:deps {io.github.nextjournal/garden-clerk {:git/sha "<version>"}}
 :aliases
 {:nextjournal/garden {:exec-fn nextjournal.garden-clerk/serve-static!
                       :exec-args {:paths ["hello.md" "world.clj"]
                                   :index "hello.md"}}}}
```

Arguments passed in `:exec-args` are passed through to `nextjournal.clerk/build!`.

### Dynamic Serving

```clojure
(nextjournal.garden-clerk/serve!)
```

Configures bind address to `"0.0.0.0"`, reads port from `GARDEN_PORT` env var, uses `GARDEN_STORAGE` for caching.

---

---

# Default Template (Example Application)

This is the full template generated by `garden init` (from `io.github.nextjournal/garden-template`):

```clojure
(ns my-project
  (:require [clojure.java.io :as io]
            [org.httpkit.server :as server]
            ;; garden-id
            [hiccup.page :as page]
            [ring.middleware.session :as session]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [nextjournal.garden-id :as garden-id]
            ;; garden-email
            [ring.middleware.params :as ring.params]
            [nextjournal.garden-email :as garden-email]
            [nextjournal.garden-email.render :as render-email]
            [nextjournal.garden-email.mock :as mock-email]
            ;; garden-cron
            [nextjournal.garden-cron :as garden-cron]))

(defn html-response [req body]
  (assoc req
         :status 200
         :headers {"content-type" "text/html"}
         :body body))

;; increment a counter every 5 seconds
(defonce counter (atom 0))
(defn scheduled-task [_] (swap! counter inc))
(garden-cron/defcron #'scheduled-task {:second (range 0 60 5)})

(defn cron-fragment []
  [:div.mt-5
   [:a {:href "https://docs.apps.garden/#scheduling-background-tasks"}
    [:h2.bb-greenish-50.w-fit.text-xl "Scheduled tasks"]]
   [:div.mt-2 "Counter has been incremented " [:span.bg-greenish-30.rounded.p-1 @counter] " times, since the application started."]])

;; list persistent storage
(defn ls-storage []
  (.list (io/file (System/getenv "GARDEN_STORAGE"))))

(defn storage-fragment []
  [:div.mt-5
   [:a {:href "https://docs.apps.garden/#storage"}
    [:h2.bb-greenish-50.w-fit.text-xl "Storage"]]
   [:div.mt-2 [:p "Persistent storage contains the following files:"]]
   [:ul.mt-2.ml-3.list-disc
    (for [d (ls-storage)]
      [:li.mt-1 [:span.bg-greenish-30.rounded.text-sm.p-1 d]])]])

(defn auth-fragment [req]
  [:div.mt-5
   [:a {:href "https://docs.apps.garden/#user-accounts"}
    [:h2.bb-greenish-50.w-fit.text-xl "User Session"]]
   (if (garden-id/logged-in? req)
     [:div.mt-2
      [:p "You are logged in as:"]
      [:div.bg-greenish-30.rounded.my-1.p-2.overflow-auto {:style {:width "32rem"}}
       (pr-str (garden-id/get-user req))]
      [:a.underline {:href garden-id/logout-uri} "logout"]]
     [:div.mt-2
      [:p "You are not logged in."]
      [:a.underline {:href garden-id/login-uri} "login"]])])

(defn email-fragment [{{:keys [message]} :session}]
  [:div.mt-5
   [:a {:href "https://docs.apps.garden/#email"}
    [:h2.bb-greenish-50.w-fit.text-xl "Email"]]
   [:p.mt-2 "Garden projects allow you to send and receive emails"]
   [:form.mt-2 {:action "/send-email" :method "POST"}
    [:label {:for "to"} "to"]
    [:input.rounded-md.p-1.px-2.block.w-full.border.focus:outline-0.bg-greenish-20.border-none {:name "to" :type "email" :required true}]
    [:label {:for "subject"} "subject"]
    [:input.rounded-md.p-1.px-2.block.w-full.border.focus:outline-0.bg-greenish-20.border-none {:name "subject" :type "text"}]
    [:label {:for "text"} "plain text"]
    [:textarea.rounded-md.p-1.px-2.block.w-full.border.focus:outline-0.bg-greenish-20.border-none {:name "text"}]
    [:label {:for "html"} "html email"]
    [:textarea.rounded-md.p-1.px-2.block.w-full.border.focus:outline-0.bg-greenish-20.border-none {:name "html"}]
    [:button.mt-2.text-center.rounded-md.p-1.px-2.block.w-full.border.focus:outline-0.bg-greenish-30.border-none "send"]]
   (when-some [{:keys [ok error]} message]
     [:div.mt-2.overflow-auto {:style {:width "32rem"}}
      (cond ok [:p.p-3.rounded-md.bg-greenish-30 ok]
            error [:p.p-3.rounded-md.bg-red-500 error])])
   (when garden-email/dev-mode?
     [:div.mt-2.ml-5
      [:a {:href mock-email/outbox-url}
       [:h3.w-fit.bb-greenish-50 "Mock Outbox"]]])
   [:div.mt-2.ml-5
    [:h3.w-fit.bb-greenish-50 "Inbox"]
    [:p.mt-2 "You can send me email at "
     [:a.underline {:href (str "mailto:" garden-email/my-email-address)} garden-email/my-email-address]]
    (render-email/render-mailbox (garden-email/inbox))]])

(defn home-page [req]
  (page/html5
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:link {:rel "preconnect" :href "https://fonts.bunny.net"}]
    [:link {:rel "preconnect" :href "https://ntk148v.github.io"}]
    (page/include-css "https://fonts.bunny.net/css?family=fira-code:400,600")
    (page/include-css "https://fonts.bunny.net/css?family=inter:400,700")
    (page/include-css "https://ntk148v.github.io/iosevkawebfont/latest/iosevka.css")
    (page/include-js "https://cdn.tailwindcss.com?plugins=typography")
    [:script {:type "importmap"}
     "{\"imports\": {\"squint-cljs/core.js\": \"https://cdn.jsdelivr.net/npm/squint-cljs@0.7.96/src/squint/core.js\"}}"]
    [:script {:type :module :src "https://login.auth.application.garden/leaf.mjs"}]
    [:style {:type "text/css"}
     ":root {
     --greenish: rgba(146, 189, 154, 1);
     --greenish-60: rgba(146, 189, 154, 0.6);
     --greenish-50: rgba(146, 189, 154, 0.5);
     --greenish-30: rgba(146, 189, 154, 0.3);
     --greenish-20: rgba(146, 189, 154, 0.2);
     }
     body { background: #000 !important; font-family: 'Fira Sans', sans-serif; color: var(--greenish); }
     .font-iosevka { font-family: 'Iosevka Web', monospace; }
     .text-greenish { color: var(--greenish); }
     .text-greenish-60 { color: var(--greenish-60); }
     .bg-greenish { background-color: var(--greenish); }
     .bg-greenish-20 { background-color: var(--greenish-20); }
     .bg-greenish-30 { background-color: var(--greenish-30); }
     .border-greenish-50 { border: 4px solid var(--greenish-30); }
     .bb-greenish-30 { border-bottom: 2px solid var(--greenish-30); }
     .bb-greenish-50 { border-bottom: 2px solid var(--greenish-50); }
  "]]
   [:body.flex.text-greenish.justify-center.items-center.font-sans.antialiased.not-prose.text-sm.md:text-base.mt-12
    [:div.flex-col.max-w-md.md:max-w-2xl
     [:div.flex.justify-center
      [:h1.font-iosevka.w-fit.text-greenish.text-3xl.font-light.bb-greenish-50 "Welcome to application.garden!"]]
     [:div#leaf.flex.justify-center.mt-4.md:mt-8
      {:class "h-[100px] md:h-[150px]"
       :style {:stroke "rgba(146, 189, 154, 1)" :stroke-width "0.01"}}]
     [:div.mt-5
      [:p "This is just an example project to get you started with application.garden features, please refer to our "
       [:a.underline {:href "https://docs.apps.garden"} "documentation"] " for more details."]]
     (cron-fragment)
     (storage-fragment)
     (auth-fragment req)
     (email-fragment req)]]))

(defn send-email! [req]
  (let [{:strs [to subject text html]} (:form-params req)]
    (-> req
        (assoc :status 303 :headers {"Location" "/"})
        (update :session assoc :message
                (try {:ok (pr-str
                           (garden-email/send-email! (cond-> {:to {:email to}}
                                                       (not= "" subject) (assoc :subject subject)
                                                       (not= "" text) (assoc :text text)
                                                       (not= "" html) (assoc :html html))))}
                     (catch Exception e {:error (ex-message e)}))))))

(defn app [{:as req :keys [request-method uri]}]
  (case [request-method uri]
    ;; application.garden pings your project with a HEAD request at `/`
    ;; to know whether it successfully started
    [:head "/"] {:status 202}
    [:post "/send-email"] (send-email! req)
    [:get "/"] (-> req
                   (update :session dissoc :message)
                   (html-response (home-page req)))
    {:status 404 :body "not found"}))

(def wrapped-app
  (-> app
      ;; garden-email
      (ring.params/wrap-params)
      (garden-email/wrap-with-email #_{:on-receive (fn [email] (println "Got mail"))})
      ;; garden-id
      (garden-id/wrap-auth #_{:github [{:team "nextjournal"}]})
      (session/wrap-session {:store (cookie-store)})))

(defn start! [opts]
  (let [server (server/run-server #'wrapped-app
                                  (merge {:legacy-return-value? false
                                          :host "0.0.0.0"
                                          :port 7777}
                                         opts))]
    (println (format "server started on port %s"
                     (server/server-port server)))))
```

### Default deps.edn

```clojure
{:deps {http-kit/http-kit {:mvn/version "2.8.0-SNAPSHOT"}
        ring/ring-core {:mvn/version "1.12.1"}
        hiccup/hiccup {:mvn/version "2.0.0-RC3"}
        io.github.nextjournal/garden-cron {:git/sha "23d5af087f2c76cc884273883b18d30cb4fa4997"}
        io.github.nextjournal/garden-id {:git/sha "06b67b5cf77d4d0573975f6cbe9bb6d69cce60eb"}
        io.github.nextjournal/garden-email {:git/sha "ca4e1d7f5fefba1501ebd83d71b9cf409cb94080"}}
 :aliases {:nextjournal/garden {:exec-fn my-project/start!}}}
```

---

## Resources

- **Website:** https://application.garden
- **Documentation:** https://docs.apps.garden
- **CLI Source:** https://github.com/nextjournal/garden-cli
- **Template:** https://github.com/nextjournal/garden-template
- **garden-id:** https://github.com/nextjournal/garden-id
- **garden-email:** https://github.com/nextjournal/garden-email
- **garden-cron:** https://github.com/nextjournal/garden-cron
- **garden-clerk:** https://github.com/nextjournal/garden-clerk
- **garden-env:** https://github.com/nextjournal/garden-env (Nix environment for local builds)
- **Slack:** #clerk-garden on Clojurians Slack
- **Blog:** https://blog.nextjournal.com/garden.html
- **Examples:** https://github.com/topics/application-garden
- **Templates:** https://github.com/topics/application-garden-template
