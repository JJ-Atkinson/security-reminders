# garden-email Notes

## Setup
- Dep: `io.github.nextjournal/garden-email {:git/sha "ca4e1d7f5fefba1501ebd83d71b9cf409cb94080"}`
- Ring middleware `(garden-email/wrap-with-email)` required even for send-only (routes webhook callbacks)
- Sender address: `security-reminder@apps.garden` (from `garden-email/my-email-address`)

## Sending
```clojure
(garden-email/send-email! {:to {:email "..." :name "..."}
                           :from {:email garden-email/my-email-address :name "Security Reminder"}
                           :subject "..."
                           :text "plain text"
                           :html "<html>...</html>"
                           :headers {"In-Reply-To" "<msg-id>" "References" "<msg-id>"}})
;; => {:ok true :message-id "..."}
```

## Threading / Reply Chains
- `send-email!` accepts undocumented `:headers` map (confirmed in source of `reply!`)
- Pass `{"In-Reply-To" "<prev-msg-id>" "References" "<prev-msg-id>"}` + "Re: " subject prefix to thread
- `reply!` is a convenience for replying to *received* emails — not needed for our outbound-only case
- Tested: sent a reply with In-Reply-To header to jarrett@freeformsoftware.dev, threading worked

## Double-Opt-In
- First email to a new address triggers a Garden confirmation email
- Original message buffered until recipient clicks confirm
- Blocked from sending more until confirmed

## Other
- `{{subscribe-link}}` placeholder in body controls where opt-in/unsubscribe link appears (otherwise auto-appended footer)
- Dev mode (`garden-email/dev-mode?`): no real emails sent locally, mock outbox at `mock-email/outbox-url`
- `reply!` source: merges `{:subject (str "Re: " subject) :to (or reply-to from) :headers {"In-Reply-To" msg-id}}`
