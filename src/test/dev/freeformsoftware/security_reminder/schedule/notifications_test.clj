(ns dev.freeformsoftware.security-reminder.schedule.notifications-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.freeformsoftware.security-reminder.schedule.notifications :as notif]
   [dev.freeformsoftware.security-reminder.schedule.reminders :as reminders]))

(def sample-plan
  [{:event-key       {:date "2026-04-01" :template-id "et-1"}
    :label           "Wed Evening"
    :date            "2026-04-01"
    :time-label      :evening
    :people-required 2
    :assigned        ["p1" "p2"]
    :absent          []
    :understaffed?   false}
   {:event-key       {:date "2026-04-05" :template-id "et-2"}
    :label           "Sun Morning"
    :date            "2026-04-05"
    :time-label      :morning
    :people-required 2
    :assigned        ["p3" "p4"]
    :absent          []
    :understaffed?   false}])

(deftest test-no-notifications-no-corrections
  (testing "when there are no sent notifications, no corrections are needed"
    (let [corrections (notif/compute-corrections sample-plan [] "2026-03-25")]
      (is (empty? corrections)))))

(deftest test-assigned-person-removed-emits-rescind
  (testing "person was notified :assigned but is no longer in :assigned -> rescind"
    (let [notifications [{:person-id         "p1"
                          :event-date        "2026-04-01"
                          :event-template-id "et-1"
                          :type              :assigned
                          :sent-at           "2026-03-25T10:00:00"}]
          ;; Plan where p1 was replaced by p5
          plan          [(assoc (first sample-plan) :assigned ["p5" "p2"])
                         (second sample-plan)]
          corrections   (notif/compute-corrections plan notifications "2026-03-25")]
      (is (= 1 (count corrections)))
      (is (= "p1" (:person-id (first corrections))))
      (is (= :rescinded (:action (first corrections)))))))

(deftest test-rescinded-person-reassigned-emits-assign
  (testing "person was notified :rescinded but is now assigned -> assign"
    (let [notifications [{:person-id         "p1"
                          :event-date        "2026-04-01"
                          :event-template-id "et-1"
                          :type              :rescinded
                          :sent-at           "2026-03-25T10:00:00"}]
          corrections   (notif/compute-corrections sample-plan notifications "2026-03-25")]
      (is (= 1 (count corrections)))
      (is (= "p1" (:person-id (first corrections))))
      (is (= :assigned (:action (first corrections)))))))

(deftest test-matching-state-no-corrections
  (testing "person's notification matches current assignment -> no correction"
    (let [notifications [{:person-id         "p1"
                          :event-date        "2026-04-01"
                          :event-template-id "et-1"
                          :type              :assigned
                          :sent-at           "2026-03-25T10:00:00"}]
          corrections   (notif/compute-corrections sample-plan notifications "2026-03-25")]
      (is (empty? corrections)))))

(deftest test-past-events-ignored
  (testing "notifications for past events don't generate corrections"
    (let [notifications [{:person-id         "p1"
                          :event-date        "2026-03-20"
                          :event-template-id "et-1"
                          :type              :assigned
                          :sent-at           "2026-03-18T10:00:00"}]
          ;; Plan doesn't contain the past event (it wouldn't in practice)
          corrections   (notif/compute-corrections sample-plan notifications "2026-03-25")]
      (is (empty? corrections)))))

(deftest test-latest-notification-wins
  (testing "when multiple notifications exist, only the latest matters"
    (let [notifications [{:person-id         "p1"
                          :event-date        "2026-04-01"
                          :event-template-id "et-1"
                          :type              :assigned
                          :sent-at           "2026-03-24T10:00:00"}
                         {:person-id         "p1"
                          :event-date        "2026-04-01"
                          :event-template-id "et-1"
                          :type              :rescinded
                          :sent-at           "2026-03-25T10:00:00"}]
          ;; p1 is back in :assigned but latest notification was :rescinded
          corrections   (notif/compute-corrections sample-plan notifications "2026-03-25")]
      (is (= 1 (count corrections)))
      (is (= :assigned (:action (first corrections)))))))

(deftest test-one-off-event-corrections
  (testing "corrections work for one-off events too"
    (let [plan          [{:event-key       {:date "2026-04-10" :one-off-id "oo-1"}
                          :label           "Special Event"
                          :date            "2026-04-10"
                          :time-label      :afternoon
                          :people-required 2
                          :assigned        ["p3" "p4"]
                          :absent          []
                          :understaffed?   false}]
          notifications [{:person-id        "p1"
                          :event-date       "2026-04-10"
                          :one-off-event-id "oo-1"
                          :type             :assigned
                          :sent-at          "2026-03-25T10:00:00"}]
          corrections   (notif/compute-corrections plan notifications "2026-03-25")]
      (is (= 1 (count corrections)))
      (is (= :rescinded (:action (first corrections)))))))

(deftest test-corrections-ignore-reminder-group
  (testing "corrections still work when notifications have :sent-for-reminder-group metadata"
    (let [notifications [{:person-id               "p1"
                          :event-date              "2026-04-01"
                          :event-template-id       "et-1"
                          :type                    :reminder
                          :sent-for-reminder-group 8
                          :sent-at                 "2026-03-25T10:00:00"}]
          ;; Plan where p1 was replaced by p5
          plan          [(assoc (first sample-plan) :assigned ["p5" "p2"])
                         (second sample-plan)]
          corrections   (notif/compute-corrections plan notifications "2026-03-25")]
      (is (= 1 (count corrections)))
      (is (= "p1" (:person-id (first corrections))))
      (is (= :rescinded (:action (first corrections)))))))

(deftest test-format-correction-sms
  (testing "rescind message"
    (let [msg (reminders/format-correction-sms :rescinded
                                               "Alice"      "Wed Evening"
                                               "2026-04-01" "https://example.com/tok/schedule")]
      (is (re-find #"no longer assigned" msg))
      (is (re-find #"Wed Evening" msg))))
  (testing "assign message"
    (let [msg (reminders/format-correction-sms :assigned
                                               "Bob"        "Sun Morning"
                                               "2026-04-05" "https://example.com/tok/schedule")]
      (is (re-find #"assigned to" msg))
      (is (re-find #"Sun Morning" msg)))))

(deftest test-format-reminder-sms-by-group
  (testing "group >= 2 (heads up) message"
    (let [msg (reminders/format-reminder-sms "Alice" "Wed Evening" "2026-04-01" "https://example.com/tok/schedule" 8)]
      (is (re-find #"heads up" msg))
      (is (re-find #"Wed Evening" msg))))
  (testing "group 1 (reminder) message"
    (let [msg (reminders/format-reminder-sms "Alice" "Wed Evening" "2026-04-01" "https://example.com/tok/schedule" 1)]
      (is (re-find #"reminder" msg))
      (is (re-find #"Wed Evening" msg))))
  (testing "nil group (legacy) message"
    (let [msg (reminders/format-reminder-sms "Alice" "Wed Evening" "2026-04-01" "https://example.com/tok/schedule" nil)]
      (is (re-find #"reminder" msg))
      (is (re-find #"Wed Evening" msg)))))
