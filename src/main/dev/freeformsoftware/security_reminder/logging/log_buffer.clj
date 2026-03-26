(ns dev.freeformsoftware.security-reminder.logging.log-buffer
  "In-memory ring buffer for recent log entries. Pure functions + state creation."
  (:require [taoensso.telemere :as tel])
  (:import [java.time Instant Duration]))

(set! *warn-on-reflection* true)

(def ^:private all-logs-limit 1000)
(def ^:private error-logs-limit 50)

(defn create-log-buffer
  "Create a new log buffer with empty queues."
  []
  {:all-logs   (atom clojure.lang.PersistentQueue/EMPTY)
   :error-logs (atom clojure.lang.PersistentQueue/EMPTY)})

(defn- push-capped
  "Conj item onto a PersistentQueue, popping from front if over cap."
  [queue item cap]
  (let [q (conj queue item)]
    (if (> (count q) cap)
      (pop q)
      q)))

(def ^:private format-signal (tel/format-signal-fn {:incl-newline? false}))

(defn- strip-ansi
  "Remove ANSI escape sequences from a string."
  [^String s]
  (.replaceAll s "\\x1B\\[[0-9;]*m" ""))

(defn make-handler
  "Return a Telemere handler fn that appends to log-buffer queues."
  [log-buffer]
  (fn [signal]
    (let [level     (:level signal)
          error     (:error signal)
          msg-str   (str (force (:msg_ signal)))
          entry     {:inst      (Instant/now)
                     :level     level
                     :ns        (str (:ns signal))
                     :msg       (if (seq msg-str) msg-str (str (:id signal)))
                     :data      (or (:data signal) (when error (ex-data error)))
                     :error     (when error (strip-ansi (str error)))
                     :formatted (str (format-signal signal))}
          is-error? (#{:error :fatal} level)]
      (swap! (:all-logs log-buffer) push-capped entry all-logs-limit)
      (when is-error?
        (swap! (:error-logs log-buffer) push-capped entry error-logs-limit)))))

(defn recent-logs
  "Return all buffered log entries in reverse-chronological order."
  [log-buffer]
  (reverse (seq @(:all-logs log-buffer))))

(defn recent-errors
  "Return error log entries in reverse-chronological order."
  [log-buffer]
  (reverse (seq @(:error-logs log-buffer))))

(defn errors-in-last-24h?
  "True if any error occurred in the past 24 hours."
  [log-buffer]
  (let [cutoff (.minus (Instant/now) (Duration/ofHours 24))]
    (boolean (some #(.isAfter ^Instant (:inst %) cutoff)
                   @(:error-logs log-buffer)))))

(defn install-handler!
  "Install the log buffer as a Telemere handler."
  [log-buffer]
  (tel/add-handler! ::log-buffer (make-handler log-buffer) {:async nil}))

(defn remove-handler!
  "Remove the log buffer Telemere handler."
  []
  (tel/remove-handler! ::log-buffer))
