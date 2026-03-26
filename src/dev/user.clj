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
  (reset! !system (ig/resume (#'config/resolve-config! false) @!system)))

(defn restart-hard!
  []
  (tel/log! :info "Doing a hard restart!")
  (refresh-pages-after-eval! 200)
  (when @!system (stop))
  (e->nil (clj-reload/reload {:log-fn println}))
  (start))

(defn suspend-reload-resume!
  []
  (tel/log! :info "Doing a soft suspend/resume restart!")
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

(defn go [] (start))

(defn engine
  "Returns the engine component from the running system."
  []
  (get @!system :dev.freeformsoftware.security-reminder.schedule.engine/engine))

(def portal-instance (atom nil))

(defn portal
  "Open a Portal window and register a tap handler for it. The result can be
  treated like an atom."
  []
  (let [p ((e->nil (requiring-resolve 'portal.api/open))
            @portal-instance
            #_{:app false})]
    (reset! portal-instance p)
    (add-tap (e->nil (requiring-resolve 'portal.api/submit)))
    p))

(comment
  (tap> :test)
  (go)
  (stop)
  (restart)
  (restart-hard!))

(comment
  ;; === First deploy bootstrap ===
  ;; After starting the system, add real people:
  (require '[dev.freeformsoftware.security-reminder.schedule.engine :as engine])
  (engine/list-people (engine))
  (engine/add-person! (engine) {:name "Alice" :phone "+15551234567" :admin? false})
  (engine/add-person! (engine) {:name "Jarrett" :phone "+15559876543" :admin? true})
  ;; Get their access URLs:
  ;; Each add-person! returns the person ID. The token is in the DB.
  ;; Look up a token: (engine/get-token-for-person (engine) "p-123456")
  ;; Access URL: http://localhost:3000/{token}/schedule
  )
