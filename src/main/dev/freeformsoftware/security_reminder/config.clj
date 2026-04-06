(ns dev.freeformsoftware.security-reminder.config
  "Application configuration loader."
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
        ;; Resolve db-folder: use GARDEN_STORAGE in prod if available
        ;; Must happen BEFORE nref resolution so #n/ref :db-folder picks up the updated value
        full-config (if-let [gs (garden-storage-path)]
                      (assoc full-config :db-folder (str gs "/" (:db-folder full-config "data")))
                      full-config)
        ;; Resolve VAPID keys from env vars (prod) if not already in config (dev)
        full-config (cond-> full-config
                      (not (:vapid-public-key full-config))
                      (assoc :vapid-public-key (System/getenv "VAPID_PUBLIC_KEY"))
                      (not (:vapid-private-key full-config))
                      (assoc :vapid-private-key (System/getenv "VAPID_PRIVATE_KEY"))
                      (not (:vapid-subject full-config))
                      (assoc :vapid-subject (System/getenv "VAPID_SUBJECT")))
        full-config (resolve-nrefs full-config)
        parsed-config (:system full-config)]
    (ig/load-namespaces parsed-config)
    parsed-config))
