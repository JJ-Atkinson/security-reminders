(ns dev.freeformsoftware.security-reminder.config
  "Application configuration loader.

   In prod, Twilio secrets are read from environment variables
   (set via `garden secrets add`):
     - TWILIO_ACCOUNT_SID
     - TWILIO_AUTH_TOKEN
     - TWILIO_FROM_NUMBER"
  (:require
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [integrant.core :as ig]
   [taoensso.telemere :as tel]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn nref?
  [x]
  (and (map? x) (contains? x ::nref)))

(defn deep-merge
  "Deep merge maps, with understanding that an nref is a scalar not a map."
  [a b]
  (if (or (nref? a) (nref? b))
    b ;; When a value is scalar (nref), the second value always wins
    (merge-with (fn [x y]
                  (if (and (map? x) (map? y)) (deep-merge x y) y))
                a
                b)))

(defn- garden-storage-path
  "Returns the GARDEN_STORAGE path if set, nil otherwise."
  []
  (System/getenv "GARDEN_STORAGE"))

(defn read-prod-env-secrets
  "Read Twilio secrets from environment variables (for prod/Garden).
   Warns and uses placeholders if any are missing (safe when twilio-mock? is true)."
  []
  (let [required {"TWILIO_ACCOUNT_SID" :twilio-account-sid
                  "TWILIO_AUTH_TOKEN"  :twilio-auth-token
                  "TWILIO_FROM_NUMBER" :twilio-from-number}
        env-vals (into {}
                       (map (fn [[env-var k]]
                              [k (or (System/getenv env-var) "NOT_SET")])
                            required))
        missing  (keep (fn [[env-var _k]]
                         (when (str/blank? (System/getenv env-var))
                           env-var))
                       required)]
    (when (seq missing)
      (tel/log! {:level :warn :data {:missing missing}} "Missing Twilio environment variables (SMS will not work)"))
    (assoc env-vals :twilio-mock? true)))

(defn read-config-files!
  [enable-prod?]
  (keep (fn [s]
          (when s
            (try
              (slurp s)
              (catch Exception e
                (when enable-prod?
                  (tel/error! {:data {:file s} :msg "Could not read config file!"} e))
                nil))))
        [(io/resource "config/config.edn")
         (when-not enable-prod? (io/resource "config/secrets.edn"))
         (when enable-prod? (io/resource "config/prod-config.edn"))]))

(defn reader-nref
  [key]
  (assert (or (keyword? key)
              (and (vector? key)
                   (every? keyword? key))))
  {::nref (if (keyword? key) [key] key)})

(defn reader-file-str
  [key]
  (str/trim (slurp key)))

(defn resolve-nrefs
  [config]
  (let [get-exists! (fn [path]
                      (let [m (get-in config (butlast path))]
                        (assert (and (map? m) (contains? m (last path))) (str "Unable to find key " path " in config!"))
                        (get m (last path))))]
    (walk/prewalk
     (fn [x]
       (if (nref? x)
         (get-exists! (::nref x))
         x))
     config)))

(defn resolve-config!
  [enable-prod?]
  (let [full-config
        (->> (read-config-files! enable-prod?)
             (map (partial ig/read-string
                           {:readers {'n/ref             reader-nref
                                      'n/reader-file-str reader-file-str}}))
             (reduce deep-merge))
        ;; In prod, merge env-var secrets before resolving nrefs
        full-config (if enable-prod?
                      (deep-merge full-config (read-prod-env-secrets))
                      full-config)
        full-config (resolve-nrefs full-config)
        ;; Resolve db-folder: use GARDEN_STORAGE in prod if available
        full-config (if-let [gs (garden-storage-path)]
                      (assoc full-config :db-folder (str gs "/" (:db-folder full-config "data")))
                      full-config)
        parsed-config (:system full-config)]
    (ig/load-namespaces parsed-config)
    parsed-config))
