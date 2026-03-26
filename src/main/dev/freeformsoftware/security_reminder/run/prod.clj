(ns dev.freeformsoftware.security-reminder.run.prod
  (:require
   [dev.freeformsoftware.security-reminder.config :as config]
   dev.freeformsoftware.security-reminder.logging-conf
   dev.freeformsoftware.security-reminder.schedule.engine
   dev.freeformsoftware.security-reminder.schedule.time-layer
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

(defn start-garden!
  "Entry point for Application Garden deployment."
  [{:keys [port] :or {port 7777}}]
  (tel/log! {:level :info :data {:port port}} "Starting for Garden deployment")
  (let [system (ig/init (config/resolve-config! true))]
    (reset! !system system)
    (tel/log! :info "Garden deployment started successfully")
    system))

(defn -main
  [& _args]
  (start-server!))
