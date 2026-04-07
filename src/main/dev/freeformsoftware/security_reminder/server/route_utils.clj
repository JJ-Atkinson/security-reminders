(ns dev.freeformsoftware.security-reminder.server.route-utils
  (:require
   [clojure.set :as set]
   [clojure.string :as str])
  (:import
    [java.time LocalDate]
    [java.time.format DateTimeParseException]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Input validation helpers
;; =============================================================================

(defn valid-date?
  [s]
  (when (string? s)
    (try (LocalDate/parse s)
         true
         (catch DateTimeParseException _ false))))

(defn valid-email?
  [s]
  (and (string? s)
       (re-matches #".+@.+\..+" s)))

(defn valid-name?
  [s]
  (and (string? s) (not (str/blank? s))))

(defn valid-people-required?
  [n]
  (and (int? n) (<= 1 n 50)))

(def valid-time-labels #{:morning :afternoon :evening})

(defn bad-request
  [msg]
  {:status 400 :headers {"Content-Type" "text/plain"} :body msg})

(defn merge-routes
  [& routes]
  (reduce (fn [acc rts]
            (if-let [duplicate-routes (seq (set/intersection (set (keys acc)) (set (keys rts))))]
              (let [ex (ex-info "Duplicate routes detected!" {:dup duplicate-routes})]
                (tap> ex)
                (throw ex))
              (merge acc rts)))
          routes))

(defn wrap-routes
  [wrap-fn routes]
  (update-vals routes wrap-fn))
