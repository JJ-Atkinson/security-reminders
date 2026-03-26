(ns dev.freeformsoftware.security-reminder.logging-conf
  "Integrant component for configuring logging settings."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [integrant.core :as ig]
   [taoensso.telemere :as tel]
   [dev.freeformsoftware.security-reminder.logging.log-buffer :as log-buffer]
   [zprint.core :as zprint]))

(set! *warn-on-reflection* true)

(defn- load-zprint-config!
  "Load zprintrc.edn from classpath (copied by build-resources) and apply.
   Falls back to .zprintrc in the working directory for dev."
  []
  (when-let [opts (or (some-> (io/resource "config/zprintrc.edn") slurp edn/read-string)
                      (let [f (io/file ".zprintrc")]
                        (when (.exists f)
                          (edn/read-string (slurp f)))))]
    (zprint/set-options! opts)))

(defmethod ig/init-key ::logging-conf
  [_ {:keys [log-level]}]
  (let [level (or log-level :info)
        lb    (log-buffer/create-log-buffer)]
    (tel/set-min-level! level)
    (log-buffer/install-handler! lb)
    (load-zprint-config!)
    (println "Setting log level to" level)
    {:log-level level :log-buffer lb}))

(defmethod ig/halt-key! ::logging-conf
  [_ _]
  (log-buffer/remove-handler!))
