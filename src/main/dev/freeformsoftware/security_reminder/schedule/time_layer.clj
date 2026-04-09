(ns dev.freeformsoftware.security-reminder.schedule.time-layer
  "Integrant component that owns cron scheduling and debounce logic.
   Enriches the base engine env with :today-str and :on-assignment-change
   before passing to engine mutation functions."
  (:require [chime.core :as chime]
            [com.fulcrologic.guardrails.malli.core :refer [>defn]]
            [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
            [dev.freeformsoftware.security-reminder.schedule.ops :as ops]
            [integrant.core :as ig]
            [nextjournal.garden-cron :as garden-cron]
            [taoensso.telemere :as tel])
  (:import [java.time Duration Instant LocalDate ZoneId ZonedDateTime]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; REPL time override
;; =============================================================================

(def ^:private ^ZoneId chicago-tz (ZoneId/of "America/Chicago"))

^:clj-reload/keep (defonce !instance (atom nil))

(defn- effective-today
  "Single source of 'what date is it'. Returns date string.
   Uses !repl-date if set, otherwise LocalDate/now."
  [env]
  (str (or (:repl-date env) (LocalDate/now chicago-tz))))

;; =============================================================================
;; Scheduler env
;; =============================================================================

(>defn scheduler-env
       "Enrich the base engine map with time-layer concerns:
   :today-str and :on-assignment-change (debounce trigger).
   Reads :repl-date from !instance so REPL overrides affect route handlers too."
       [{:keys [engine trigger!]}]
       [[:map [:engine map?] [:trigger! fn?]] => map?]
       (assoc
        engine
        :today-str            (effective-today @!instance)
        :on-assignment-change trigger!))

;; =============================================================================
;; Debounce
;; =============================================================================

(defn- debounced-fn
  [f delay-mins]
  (let [!existing-call (atom nil)
        cancel!        (fn $cancel []
                         (swap! !existing-call (fn $close [^java.lang.AutoCloseable s]
                                                 (some-> s
                                                         (.close))
                                                 nil)))
        schedule!      (fn $schedule! [& args]
                         (cancel!)
                         (reset! !existing-call
                                 (chime/chime-at [(.plus (Instant/now) (Duration/ofMinutes (long delay-mins)))]
                                                 (fn $chime-fn [_t] (apply f args)))))]
    {:trigger! schedule!
     :cancel!  cancel!}))

(defn- send-changed-notifications!
  [{:keys [engine] :as inst}]
  (try (let [env (assoc engine :today-str (effective-today inst))]
         (engine/with-state!-> env
           (ops/compute-pending-corrections (engine/now-str))
           (ops/compute-pending-reminders (engine/now-str))))
       (catch Exception e (tel/error! "Send notifications failed" e))))

;; =============================================================================
;; Cron
;; =============================================================================

(defn daily-task!
  "Refresh plan, send corrections and reminders."
  [{:keys [engine] :as inst}]
  (tel/log! :info "Cron: daily refresh and reminders")
  (try (let [env (assoc engine :today-str (effective-today inst))
             now (engine/now-str)]
         (engine/with-state!-> env
           (ops/refresh-plan)
           (ops/compute-pending-corrections now)
           (ops/compute-pending-reminders now)))
       (catch Exception e (tel/error! "Cron: daily job failed" e))))

(defn cron-daily
  [_time]
  (some-> @!instance
          (daily-task!)))

(def ^:private chicago-now (ZonedDateTime/now chicago-tz))

(garden-cron/defcron #'cron-daily {:hour [7] :minute [0]} chicago-now)

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(defmethod ig/init-key ::time-layer
  [_ {:keys [engine delay-minutes] :or {delay-minutes 10}}]
  (tel/log! {:level :info :data {:delay-minutes delay-minutes}} "Initializing time layer")
  (let [{:keys [trigger! cancel!]} (debounced-fn send-changed-notifications! delay-minutes)]
    (reset! !instance {:engine engine :cancel! cancel!})
    {:engine   engine
     :trigger! (fn [] (trigger! @!instance))
     :cancel!  cancel!}))

(defmethod ig/halt-key! ::time-layer
  [_ {:keys [cancel!]}]
  (tel/log! :info "Shutting down time layer")
  (when cancel! (cancel!))
  (reset! !instance nil))

;; =============================================================================
;; REPL time control
;; =============================================================================

(defn- inc-day!
  "Advance !repl-date by 1 day (initializes from today if nil).
   Refreshes plan, computes corrections and reminders in a single transaction."
  []
  (swap! !instance update
         :repl-date
         (fn [d]
           (let [^LocalDate d (or d (LocalDate/now))]
             (.plusDays d 1))))
  (cron-daily nil)
  (:repl-date @!instance))

(defn- unlock-debounce!
  "If a debounced notification is pending, cancel the delay and run it immediately."
  []
  (let [{:keys [cancel!] :as inst} @!instance]
    (when cancel! (cancel!))
    (send-changed-notifications! inst)))

(comment
  ;; Advance to tomorrow, plan refreshes automatically
  (inc-day!)
  ;; Toggle an absence in the UI, then fire corrections immediately
  (unlock-debounce!))
