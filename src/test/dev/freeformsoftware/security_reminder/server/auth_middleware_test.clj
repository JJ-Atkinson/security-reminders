(ns dev.freeformsoftware.security-reminder.server.auth-middleware-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.freeformsoftware.security-reminder.server.auth-middleware :as auth-middleware]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.test-helpers :as h]))

(deftest test-wrap-token-auth-valid-token
  (testing "valid token attaches :person and rewrites :uri"
    (h/with-test-engine
      (fn [eng]
        (let [token (engine/get-token-for-person eng "p1")
              handler (auth-middleware/wrap-token-auth
                       (fn [req] {:person (:person req) :uri (:uri req) :sec-token (:sec-token req)})
                       eng)
              resp (handler {:uri (str "/" token "/schedule")})]
          (is (= "p1" (get-in resp [:person :id])))
          (is (= "/schedule" (:uri resp)))
          (is (= token (:sec-token resp))))))))

(deftest test-wrap-token-auth-invalid-token
  (testing "invalid token passes through without :person"
    (h/with-test-engine
      (fn [eng]
        (let [handler (auth-middleware/wrap-token-auth
                       (fn [req] {:person (:person req) :uri (:uri req)})
                       eng)
              resp (handler {:uri "/badtoken/schedule"})]
          (is (nil? (:person resp)))
          (is (= "/badtoken/schedule" (:uri resp))))))))

(deftest test-wrap-token-auth-no-auth-route
  (testing "no-auth routes like /health pass through"
    (h/with-test-engine
      (fn [eng]
        (let [handler (auth-middleware/wrap-token-auth
                       (fn [req] {:uri (:uri req)})
                       eng)
              resp (handler {:uri "/health"})]
          ;; /health has no valid token, passes through unchanged
          (is (= "/health" (:uri resp))))))))

(deftest test-wrap-require-person-with-person
  (testing "request with :person passes through"
    (let [handler (auth-middleware/wrap-require-person
                   {:env :test}
                   (fn [_] {:status 200}))
          resp (handler {:person {:id "p1" :name "Alice"}})]
      (is (= 200 (:status resp))))))

(deftest test-wrap-require-person-without-person
  (testing "request without :person returns 401"
    (let [handler (auth-middleware/wrap-require-person
                   {:env :test}
                   (fn [_] {:status 200}))
          resp (handler {})]
      (is (= 401 (:status resp))))))

(deftest test-wrap-admin-auth-admin-user
  (testing "admin user passes through"
    (let [handler (auth-middleware/wrap-admin-auth
                   {:env :test}
                   (fn [_] {:status 200}))
          resp (handler {:person {:id "p5" :name "Eve" :admin? true}})]
      (is (= 200 (:status resp))))))

(deftest test-wrap-admin-auth-non-admin
  (testing "non-admin user gets 403"
    (let [handler (auth-middleware/wrap-admin-auth
                   {:env :test}
                   (fn [_] {:status 200}))
          resp (handler {:person {:id "p1" :name "Alice" :admin? false}})]
      (is (= 403 (:status resp))))))
