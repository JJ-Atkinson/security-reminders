(ns dev.freeformsoftware.security-reminder.server.websocket
  (:require
   [ring.websocket :as ws]
   [taoensso.telemere :as tel]))


(set! *warn-on-reflection* true)

^:clj-reload/keep
(def clients (atom #{}))

(defn broadcast!
  "Send a message to all connected clients"
  [message]
  (doseq [client @clients]
    (try
      (ws/send client message)
      (catch Exception e
        (swap! clients disj client)
        (tel/error! "Failed to send websocket message" e)))))

(defn reload-handler
  "WebSocket handler for dev reload"
  [req]
  (assert (ws/upgrade-request? req))
  {::ws/listener {:on-open    (fn [socket]
                                (swap! clients conj socket)
                                #_(tel/log! :info "WebSocket client connected"))

                  :on-message (fn [_socket message]
                                (tel/log! {:level :debug :data {:message message}} "WebSocket message received"))

                  :on-close   (fn [socket _status-code _reason]
                                (swap! clients disj socket)
                                #_(tel/log! :info
                                          ["WebSocket client disconnected"
                                           {:status _status-code :reason _reason}]))

                  :on-error   (fn [socket error]
                                (swap! clients disj socket)
                                (tel/error! "WebSocket error" error))}})
