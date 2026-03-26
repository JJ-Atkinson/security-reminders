(ns dev.freeformsoftware.security-reminder.schedule.reminders
  "SMS message formatting for reminders and corrections. Pure string functions."
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [dev.freeformsoftware.security-reminder.db.schema :as schema]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; SMS formatting
;; =============================================================================

(>defn format-reminder-sms
  "Format a reminder SMS message. reminder-group controls the message style:
   groups >= 2 — heads-up in advance
   groups <= 1 or nil — final reminder"
  [person-name event-label event-date link reminder-group]
  [:string :string ::schema/date-str :string [:maybe :int] => :string]
  (if (and reminder-group (>= reminder-group 2))
    (str "Hi " person-name ", heads up: you're assigned to "
         event-label " on " event-date
         ". View schedule & mark absences: " link)
    (str "Hi " person-name ", reminder: you're assigned to "
         event-label " on " event-date
         ". View schedule & mark absences: " link)))

(>defn format-correction-sms
  "Format a correction SMS message."
  [action person-name event-label event-date link]
  [keyword? :string :string ::schema/date-str :string => :string]
  (case action
    :rescinded (str "Hi " person-name ", update: you are no longer assigned to "
                    event-label " on " event-date
                    ". No action needed. View schedule: " link)
    :assigned (str "Hi " person-name ", update: you've been assigned to "
                   event-label " on " event-date
                   ". View schedule & mark absences: " link)))
