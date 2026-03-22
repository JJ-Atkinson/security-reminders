(ns dev.freeformsoftware.security-reminder.run.prod
  (:require
   [dev.freeformsoftware.security-reminder.config :as config]
   dev.freeformsoftware.security-reminder.logging-conf
   dev.freeformsoftware.security-reminder.server.core
   [integrant.core :as ig]
   [taoensso.telemere.slf4j]
   [taoensso.telemere :as tel])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce !system (atom nil))

(defn start-server!
  []
  (reset! !system
    (ig/init (config/resolve-config! true))))

(defn -main
  [& args]
  (start-server!))
