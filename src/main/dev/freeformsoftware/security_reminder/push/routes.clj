(ns dev.freeformsoftware.security-reminder.push.routes
  (:require
   [cheshire.core :as json]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.schedule.ops :as ops]
   [dev.freeformsoftware.security-reminder.schedule.time-layer :as time-layer]
   [taoensso.telemere :as tel]))

(set! *warn-on-reflection* true)

(defn- parse-json-body
  [request]
  (when-let [body (:body request)]
    (json/parse-string (slurp body) true)))

(defn- handle-subscribe
  [{:keys [time-layer] :as _conf} request]
  (let [body      (parse-json-body request)
        endpoint  (:endpoint body)
        keys-map  (:keys body)
        person    (:person request)]
    (if (or (not endpoint) (not (:p256dh keys-map)) (not (:auth keys-map)))
      {:status 400 :body "Missing endpoint or keys"}
      (let [env          (time-layer/scheduler-env time-layer)
            subscription {:person-id  (:id person)
                          :endpoint   endpoint
                          :p256dh     (:p256dh keys-map)
                          :auth       (:auth keys-map)
                          :created-at (engine/now-str)
                          :user-agent (get-in request [:headers "user-agent"])}]
        (engine/with-state!-> env
          (ops/add-push-subscription subscription))
        (tel/log! {:level :info :data {:person (:name person) :endpoint endpoint}}
                  "Push subscription registered")
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string {:ok true})}))))

(defn- handle-unsubscribe
  [{:keys [time-layer] :as _conf} request]
  (let [body     (parse-json-body request)
        endpoint (:endpoint body)]
    (if (not endpoint)
      {:status 400 :body "Missing endpoint"}
      (let [env (time-layer/scheduler-env time-layer)]
        (engine/with-state!-> env
          (ops/remove-push-subscription endpoint))
        (tel/log! {:level :info :data {:endpoint endpoint}} "Push subscription removed")
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string {:ok true})}))))

(defn routes
  [conf]
  {"POST /push/subscribe"   (fn [request] (handle-subscribe conf request))
   "POST /push/unsubscribe" (fn [request] (handle-unsubscribe conf request))})
