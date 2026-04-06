(ns dev.freeformsoftware.security-reminder.push.send
  "Web Push notification sending. Combines VAPID auth, RFC 8291 encryption,
   and HTTP delivery via clj-http-lite."
  (:require
   [cheshire.core :as json]
   [clj-http.lite.client :as http]
   [dev.freeformsoftware.security-reminder.push.encryption :as encryption]
   [dev.freeformsoftware.security-reminder.push.vapid :as vapid]
   [dev.freeformsoftware.security-reminder.schedule.ops :as ops]
   [taoensso.telemere :as tel]))

(set! *warn-on-reflection* true)

(defn- endpoint->origin
  "Extract the origin (scheme + host + port) from a push endpoint URL."
  ^String [^String endpoint]
  (let [url (java.net.URI. endpoint)]
    (str (.getScheme url) "://" (.getHost url)
         (when (pos? (.getPort url)) (str ":" (.getPort url))))))

(defn send-push-notification!
  "Send a push notification to a single subscription.
   Returns :ok, :gone (subscription expired), or :error."
  [{:keys [vapid-private-key vapid-public-key vapid-subject]} subscription payload-str]
  (let [endpoint  (:endpoint subscription)
        priv-key  (vapid/load-private-key vapid-private-key)
        origin    (endpoint->origin endpoint)
        auth-hdr  (vapid/vapid-authorization-header
                   priv-key vapid-public-key origin vapid-subject)
        encrypted (encryption/encrypt-payload
                   (:p256dh subscription)
                   (:auth subscription)
                   (.getBytes ^String payload-str "UTF-8"))]
    (try
      (let [resp (http/post endpoint
                            {:body    encrypted
                             :headers {"Authorization"    auth-hdr
                                       "Content-Type"     "application/octet-stream"
                                       "Content-Encoding" "aes128gcm"
                                       "TTL"              "86400"}
                             :as      :stream
                             :throw-exceptions false})]
        (cond
          (<= 200 (:status resp) 299) :ok
          (= 410 (:status resp))      :gone
          :else
          (do (tel/log! {:level :warn
                         :data  {:status (:status resp) :endpoint endpoint}}
                        "Push notification delivery failed")
              :error)))
      (catch Exception e
        (tel/log! {:level :warn
                   :data  {:endpoint endpoint :error (ex-message e)}}
                  "Push notification HTTP error")
        :error))))

(defn send-push-to-person!
  "Send a push notification to all subscriptions for a person.
   Removes gone subscriptions from state. Returns updated state."
  [state push-conf person-id payload-map]
  (let [subs     (filterv #(= (:person-id %) person-id)
                          (get-in state [:schedule-db :push-subscriptions]))
        payload  (json/generate-string payload-map)]
    (when (seq subs)
      (tel/log! {:level :info
                 :data  {:person-id person-id :count (count subs)}}
                "Sending push notifications"))
    (reduce
     (fn [state sub]
       (let [result (send-push-notification! push-conf sub payload)]
         (if (= result :gone)
           (do (tel/log! {:level :info :data {:endpoint (:endpoint sub)}}
                         "Removing expired push subscription")
               (ops/remove-push-subscription state (:endpoint sub)))
           state)))
     state
     subs)))
