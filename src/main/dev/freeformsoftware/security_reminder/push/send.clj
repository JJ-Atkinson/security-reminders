(ns dev.freeformsoftware.security-reminder.push.send
  "Web Push notification sending via webpush-java library."
  (:require
   [cheshire.core :as json]
   [dev.freeformsoftware.security-reminder.schedule.ops :as ops]
   [taoensso.telemere :as tel])
  (:import
    [nl.martijndwars.webpush Notification PushService]
    [org.bouncycastle.jce.provider BouncyCastleProvider]
    [java.security Security]))

(set! *warn-on-reflection* true)

;; BouncyCastle must be registered before PushService can work
(when-not (Security/getProvider "BC")
  (Security/addProvider (BouncyCastleProvider.)))

(defn- build-push-service
  ^PushService [{:keys [vapid-public-key vapid-private-key vapid-subject]}]
  (PushService. vapid-public-key vapid-private-key vapid-subject))

(defn send-push-notification!
  "Send a push notification to a single subscription.
   Returns :ok, :gone (subscription expired), or :error."
  [push-conf subscription payload-str]
  (try
    (let [svc          (build-push-service push-conf)
          notification (Notification. ^String (:endpoint subscription)
                                      ^String (:p256dh subscription)
                                      ^String (:auth subscription)
                                      (.getBytes ^String payload-str "UTF-8"))
          response     (.send svc notification)
          status       (.getStatusLine response)
          status-code  (.getStatusCode status)]
      (cond
        (<= 200 status-code 299) :ok
        (= 410 status-code) :gone
        :else
        (do (tel/log! {:level :warn
                       :data  {:status status-code :endpoint (:endpoint subscription)}}
                      "Push notification delivery failed")
            :error)))
    (catch Exception e
      (tel/log! {:level :warn
                 :data  {:endpoint (:endpoint subscription) :error (ex-message e)}}
                "Push notification error")
      :error)))

(defn send-push-to-person!
  "Send a push notification to all subscriptions for a person.
   Removes gone subscriptions from state. Returns updated state."
  [state push-conf person-id payload-map]
  (let [subs    (filterv #(= (:person-id %) person-id)
                         (get-in state [:schedule-db :push-subscriptions]))
        payload (json/generate-string payload-map)]
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
