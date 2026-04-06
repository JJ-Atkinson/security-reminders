(ns dev.freeformsoftware.security-reminder.sms.twilio
  "Twilio SMS integration via REST API using clj-http-lite."
  (:require
   [clj-http.lite.client :as http]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [dev.freeformsoftware.security-reminder.db.schema :as schema]
   [taoensso.telemere :as tel]))

(set! *warn-on-reflection* true)

(defn- resolve-person-name
  "Dev-only: dynamically resolve person name from phone number via engine db."
  [to-number]
  (try
    (let [view-db   @(requiring-resolve 'dev.freeformsoftware.security-reminder.schedule.engine/view-db)
          db-folder (or (System/getenv "GARDEN_STORAGE") "data")]
      (->> (:people (view-db {:db-folder db-folder}))
           (some #(when (= to-number (:phone %)) (:name %)))))
    (catch Exception _ nil)))

(>defn send-sms!
  "Send an SMS via Twilio REST API.
   conf: {:twilio-account-sid \"AC...\" :twilio-auth-token \"...\" :twilio-from-number \"+1...\" :twilio-mock? bool}
   to-number: E.164 phone number string
   message: SMS body text"
  [{:keys [twilio-account-sid twilio-auth-token twilio-from-number twilio-mock?]} to-number message]
  [::schema/twilio-conf ::schema/phone :string => map?]
  (if twilio-mock?
    (let [person-name (resolve-person-name to-number)]
      (tel/log! {:level :info :data {:to to-number :name person-name :length (count message)}} "Mock SMS")
      (tap> {:mock-sms? true :to to-number :name person-name :from twilio-from-number :body message})
      {:success true :status 200 :mock? true})
    (do
      (tel/log! {:level :info :data {:to to-number :length (count message)}} "Sending SMS")
      (let [url      (str "https://api.twilio.com/2010-04-01/Accounts/"
                          twilio-account-sid
                          "/Messages.json")
            response (http/post url
                                {:basic-auth       [twilio-account-sid twilio-auth-token]
                                 :form-params      {"To"   to-number
                                                    "From" twilio-from-number
                                                    "Body" message}
                                 :throw-exceptions false})]
        (if (<= 200 (:status response) 299)
          (do (tel/log! {:level :info :data {:to to-number}} "SMS sent successfully")
              {:success true :status (:status response)})
          (do (tel/log! {:level :error
                         :data  {:to     to-number
                                 :status (:status response)
                                 :body   (:body response)}}
                        "SMS send failed")
              {:success false :status (:status response) :body (:body response)}))))))
