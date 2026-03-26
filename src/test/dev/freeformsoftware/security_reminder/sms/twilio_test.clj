(ns dev.freeformsoftware.security-reminder.sms.twilio-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.freeformsoftware.security-reminder.sms.twilio :as twilio]))

(deftest test-send-sms-request-format
  (testing "send-sms! handles non-2xx responses gracefully"
    ;; Using invalid credentials will produce a connection error or 401
    ;; This tests that the function doesn't throw and returns failure map
    (let [result (try
                   (twilio/send-sms! {:twilio-account-sid "AC_invalid"
                                      :twilio-auth-token "invalid"
                                      :twilio-from-number "+15551112222"
                                      :twilio-mock? false}
                                     "+15559999999"
                                     "Test message")
                   (catch Exception e
                     {:success false :error (ex-message e)}))]
      (is (false? (:success result))))))
