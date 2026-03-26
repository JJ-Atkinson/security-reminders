(ns dev.freeformsoftware.security-reminder.server.routes-test
  "Tests for route input validation. Uses the route handlers directly with mock requests."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.server.routes :as routes]
   [dev.freeformsoftware.security-reminder.test-helpers :as h]))

(defn- make-conf
  "Build a conf map for route construction."
  [eng]
  (let [tl {:engine eng :trigger! (fn [])}]
    {:env :test
     :engine eng
     :time-layer tl}))

(defn- find-handler
  "Look up a route handler from the routes map."
  [route-map method-and-path]
  (get route-map method-and-path))

(defn- admin-request
  "Build a mock request with admin person and params."
  [token params]
  {:person {:id "p5" :name "Eve" :admin? true}
   :sec-token token
   :params params})

;; =============================================================================
;; Admin user validation tests
;; =============================================================================

(deftest test-add-user-validation
  (h/with-test-engine
    (fn [eng]
      (let [conf (make-conf eng)
            admin-routes (routes/admin-routes conf)
            handler (find-handler admin-routes "POST /admin/users")
            token (engine/get-token-for-person eng "p5")]

        (testing "empty name returns 400"
          (let [resp (handler (admin-request token {:name "" :phone "+15551234567"}))]
            (is (= 400 (:status resp)))))

        (testing "blank name returns 400"
          (let [resp (handler (admin-request token {:name "   " :phone "+15551234567"}))]
            (is (= 400 (:status resp)))))

        (testing "invalid phone returns 400"
          (let [resp (handler (admin-request token {:name "Test" :phone "not-a-phone"}))]
            (is (= 400 (:status resp)))))

        (testing "phone without + returns 400"
          (let [resp (handler (admin-request token {:name "Test" :phone "15551234567"}))]
            (is (= 400 (:status resp)))))

        (testing "valid input returns redirect"
          (let [resp (handler (admin-request token {:name "Test" :phone "+15551234567"}))]
            (is (= 302 (:status resp)))))))))

(deftest test-add-event-validation
  (h/with-test-engine
    (fn [eng]
      (let [conf (make-conf eng)
            admin-routes (routes/admin-routes conf)
            handler (find-handler admin-routes "POST /admin/events")
            token (engine/get-token-for-person eng "p5")]

        (testing "empty label returns 400"
          (let [resp (handler (admin-request token {:label "" :date "2026-04-15"
                                                    :time-label "morning" :people-required "2"}))]
            (is (= 400 (:status resp)))))

        (testing "invalid date returns 400"
          (let [resp (handler (admin-request token {:label "Test" :date "not-a-date"
                                                    :time-label "morning" :people-required "2"}))]
            (is (= 400 (:status resp)))))

        (testing "invalid time-label returns 400"
          (let [resp (handler (admin-request token {:label "Test" :date "2026-04-15"
                                                    :time-label "midnight" :people-required "2"}))]
            (is (= 400 (:status resp)))))

        (testing "zero people-required returns 400"
          (let [resp (handler (admin-request token {:label "Test" :date "2026-04-15"
                                                    :time-label "morning" :people-required "0"}))]
            (is (= 400 (:status resp)))))

        (testing "negative people-required returns 400"
          (let [resp (handler (admin-request token {:label "Test" :date "2026-04-15"
                                                    :time-label "morning" :people-required "-1"}))]
            (is (= 400 (:status resp)))))

        (testing "valid input returns redirect"
          (let [resp (handler (admin-request token {:label "Test Event" :date "2026-04-15"
                                                    :time-label "morning" :people-required "2"}))]
            (is (= 302 (:status resp)))))))))

(deftest test-template-update-validation
  (h/with-test-engine
    (fn [eng]
      (let [conf (make-conf eng)
            admin-routes (routes/admin-routes conf)
            handler (find-handler admin-routes "POST /admin/templates")
            token (engine/get-token-for-person eng "p5")]

        (testing "blank template-id returns 400"
          (let [resp (handler (admin-request token {:template-id "" :people-required "2"}))]
            (is (= 400 (:status resp)))))

        (testing "zero people-required returns 400"
          (let [resp (handler (admin-request token {:template-id "et-1" :people-required "0"}))]
            (is (= 400 (:status resp)))))

        (testing "valid input returns redirect"
          (let [resp (handler (admin-request token {:template-id "et-1" :people-required "3"}))]
            (is (= 302 (:status resp)))))))))

(deftest test-override-validation
  (h/with-test-engine
    (fn [eng]
      (let [conf (make-conf eng)
            admin-routes (routes/admin-routes conf)
            handler (find-handler admin-routes "POST /admin/overrides")
            token (engine/get-token-for-person eng "p5")]

        (testing "invalid date returns 400"
          (let [resp (handler (admin-request token {:event-date "bad" :template-id "et-1"
                                                    :people-required "2"}))]
            (is (= 400 (:status resp)))))

        (testing "blank template-id returns 400"
          (let [resp (handler (admin-request token {:event-date "2026-04-01" :template-id ""
                                                    :people-required "2"}))]
            (is (= 400 (:status resp)))))

        (testing "valid input returns redirect"
          (let [resp (handler (admin-request token {:event-date "2026-04-01" :template-id "et-1"
                                                    :people-required "3"}))]
            (is (= 302 (:status resp)))))))))
