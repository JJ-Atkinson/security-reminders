(ns dev.freeformsoftware.security-reminder.schedule.notifications
  "Pure diff algorithm: detects when plan mutations change who's assigned to
   events that already had notifications sent. No side effects."
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [dev.freeformsoftware.security-reminder.db.schema :as schema])
  (:import
   [java.time LocalDate]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Pure diff algorithm
;; =============================================================================

(defn- notification-event-key
  "Build a canonical event-key map from a sent-notification record."
  [n]
  (schema/flat-keys->event-key n))

(defn- latest-notification-by-person-event
  "Given sent-notifications, return a map of [person-id event-key] -> latest notification.
   Filters out notifications without an event-date (e.g. :welcome type)."
  [sent-notifications]
  (->> sent-notifications
       (filter :event-date)
       (group-by (juxt :person-id notification-event-key))
       (map (fn [[k vs]]
              [k (last (sort-by :sent-at vs))]))
       (into {})))

(>defn compute-corrections
       "Pure function: given the current plan, sent-notifications, and today's date string,
   compute correction actions needed.
   Returns a vector of {:person-id :event-key :action (:assigned or :rescinded) :plan-entry}."
       [plan sent-notifications today-str]
       [sequential? sequential? ::schema/date-str => vector?]
       (let [today       (LocalDate/parse today-str)
             latest      (latest-notification-by-person-event sent-notifications)
        ;; Index plan entries by event-key
             plan-by-key (into {} (map (juxt :event-key identity)) plan)]
         (reduce-kv
          (fn [corrections [person-id event-key] notification]
            (let [plan-entry (get plan-by-key event-key)
                  event-date (LocalDate/parse (:date event-key))]
         ;; Only process future events that still exist in the plan
              (if (or (nil? plan-entry) (.isBefore event-date today))
                corrections
                (let [currently-assigned? (boolean (some #{person-id} (:assigned plan-entry)))]
                  (cond
               ;; Was told :assigned/:reminder but no longer assigned -> rescind
                    (and (#{:assigned :reminder} (:type notification))
                         (not currently-assigned?))
                    (conj corrections
                          {:person-id  person-id
                           :event-key  event-key
                           :action     :rescinded
                           :plan-entry plan-entry})

               ;; Was told :rescinded but now assigned -> assign
                    (and (= :rescinded (:type notification))
                         currently-assigned?)
                    (conj corrections
                          {:person-id  person-id
                           :event-key  event-key
                           :action     :assigned
                           :plan-entry plan-entry})

                    :else corrections)))))
          []
          latest)))
