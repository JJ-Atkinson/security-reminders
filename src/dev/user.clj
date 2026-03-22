(ns user
  (:require
   [clj-reload.core :as clj-reload]
   [dev.freeformsoftware.security-reminder.config :as config]
   [integrant.core :as ig]
   [taoensso.telemere :as tel]
   [dev.freeformsoftware.security-reminder.server.websocket :as websocket]))

(defmacro e->nil [form] `(try ~form (catch Exception e# nil)))

     ;; prevents compilation lockup for prod since clj-reload isn't on prod/build
(when-let [clj-reload-init (e->nil (requiring-resolve 'clj-reload/init))]
  (clj-reload-init {:dirs ["src/dev" "src/main" "src/test"]}))

^:clj-reload/keep
(defonce !system (atom nil))

(defn refresh-pages-after-eval!
  ([delay-ms]
   (websocket/broadcast! (str "{\"type\":\"reload\", \"delay\":\"" delay-ms "\"}"))
   (reset! websocket/clients #{}))
  ([]
   (refresh-pages-after-eval! 30)))

(defn start
  []
  (println "Starting system!")
  (reset! !system
    (ig/init (#'config/resolve-config! false))))

(defn stop
  []
  (ig/halt! @!system))

(defn suspend
  []
  (ig/suspend! @!system))

(defn resume
  []
  (refresh-pages-after-eval! 500)
  (ig/resume (#'config/resolve-config! false) @!system))

(defn restart-hard!
  []
  (tel/log! :info ["Doing a hard restart!"])
  (refresh-pages-after-eval! 200)
  (when @!system (stop))
  (e->nil (clj-reload/reload {:log-fn println}))
  (start))

(defn suspend-reload-resume!
  []
  (tel/log! :info ["Doing a soft suspend/resume restart!"])
  (suspend)
  (e->nil (clj-reload/reload {:log-fn println}))
  (resume))

^:clj-reload/keep
(defonce !last-restarted-at (atom 0))

(defn restart
  []
  (let [now          (System/currentTimeMillis)
        last-restart @!last-restarted-at]
    (reset! !last-restarted-at now)
    ;; if a user restarts twice within 5s, we do a full hard restart
    (if (or (> 5000 (- now last-restart))
            (not @!system))
      (restart-hard!)
      (suspend-reload-resume!))))

(def go start)

(comment
  (tap> :test)
  (go)
  (stop)
  (restart)
  (restart-hard!))
