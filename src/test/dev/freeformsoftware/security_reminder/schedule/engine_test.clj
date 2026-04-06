(ns dev.freeformsoftware.security-reminder.schedule.engine-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.schedule.ops :as ops]
   [dev.freeformsoftware.security-reminder.test-helpers :as h]
   [integrant.core :as ig]))

(deftest test-view-plan-after-init
  (testing "fresh engine returns valid projected plan"
    (h/with-test-engine
      (fn [eng]
        (let [plan (engine/view-plan eng)]
          (is (vector? plan))
          (is (pos? (count plan)))
          (doseq [entry plan]
            (is (:event-key entry))
            (is (:assigned entry))))))))

(deftest test-note-absence-round-trip
  (testing "note-absence reflects in view-plan"
    (h/with-test-engine
      (fn [eng]
        (let [plan        (engine/view-plan eng)
              first-event (first plan)
              person-id   (first (:assigned first-event))
              event-key   (:event-key first-event)]
         ;; Mark absent
          (engine/with-state!-> eng (ops/note-absence person-id event-key))
          (let [new-plan      (engine/view-plan eng)
                updated-event (first (filter #(= (:event-key %) event-key) new-plan))]
           ;; The person should be in :absent
            (is (some #{person-id} (:absent updated-event)))))))))

(deftest test-note-absence-toggle
  (testing "calling note-absence twice removes the absence"
    (h/with-test-engine
      (fn [eng]
        (let [plan        (engine/view-plan eng)
              first-event (first plan)
              person-id   (first (:assigned first-event))
              event-key   (:event-key first-event)]
         ;; Toggle on
          (engine/with-state!-> eng (ops/note-absence person-id event-key))
         ;; Toggle off
          (engine/with-state!-> eng (ops/note-absence person-id event-key))
          (let [new-plan      (engine/view-plan eng)
                updated-event (first (filter #(= (:event-key %) event-key) new-plan))]
           ;; The person should NOT be absent
            (is (not (some #{person-id} (:absent updated-event))))))))))

(deftest test-add-one-off
  (testing "add-one-off event appears in view-plan"
    (h/with-test-engine
      (fn [eng]
        (engine/with-state!-> eng
          (ops/add-one-off {:id              (str "oo-" (System/currentTimeMillis))
                            :label           "Special Event"
                            :date            "2026-04-15"
                            :time-label      :afternoon
                            :people-required 2}))
        (let [plan (engine/view-plan eng)]
          (is (some #(= "Special Event" (:label %)) plan)))))))

(deftest test-remove-one-off
  (testing "remove-one-off event disappears from plan"
    (h/with-test-engine
      (fn [eng]
        (let [oo-id (str "oo-" (System/currentTimeMillis))]
          (engine/with-state!-> eng
            (ops/add-one-off {:id              oo-id
                              :label           "Temp Event"
                              :date            "2026-04-15"
                              :time-label      :afternoon
                              :people-required 2}))
          (let [plan-before (engine/view-plan eng)]
            (is (some #(= "Temp Event" (:label %)) plan-before))
            (engine/with-state!-> eng (ops/remove-one-off oo-id))
            (let [plan-after (engine/view-plan eng)]
              (is (not (some #(= "Temp Event" (:label %)) plan-after))))))))))

(deftest test-plan-persistence
  (testing "plan survives engine restart"
    (let [tmp-dir (str (fs/create-temp-dir {:prefix "sr-persist-"}))]
      (try
        (let [eng1 (ig/init-key ::engine/engine
                                {:db-folder   tmp-dir
                                 :people      h/test-people
                                 :base-url    "http://test.local"})
              env1 (assoc eng1 :on-assignment-change (fn []))]
          (engine/with-state!-> env1
            (ops/add-one-off {:id              (str "oo-" (System/currentTimeMillis))
                              :label           "Persist Test"
                              :date            "2026-04-15"
                              :time-label      :afternoon
                              :people-required 2}))
          (ig/halt-key! ::engine/engine eng1))
        ;; Restart with same dir
        (let [eng2 (ig/init-key ::engine/engine
                                {:db-folder   tmp-dir
                                 :people      h/test-people
                                 :base-url    "http://test.local"})
              plan (engine/view-plan eng2)]
          (is (some #(= "Persist Test" (:label %)) plan))
          (ig/halt-key! ::engine/engine eng2))
        (finally
          (fs/delete-tree tmp-dir))))))

(deftest test-set-instance-override
  (testing "set-instance-override changes plan people-required"
    (h/with-test-engine
      (fn [eng]
        (let [plan-before          (engine/view-plan eng)
              first-template-event (first (filter #(get-in % [:event-key :template-id]) plan-before))
              event-date           (:date first-template-event)
              template-id          (get-in first-template-event [:event-key :template-id])]
          (engine/with-state!-> eng (ops/set-instance-override event-date template-id 4))
          (let [plan-after (engine/view-plan eng)
                updated    (first (filter #(and (= event-date (:date %))
                                                (= template-id (get-in % [:event-key :template-id])))
                                          plan-after))]
            (is (= 4 (:people-required updated)))
            (is (= 4 (count (:assigned updated))))))))))

(deftest test-remove-instance-override
  (testing "remove-instance-override reverts to template default"
    (h/with-test-engine
      (fn [eng]
        (let [plan-before          (engine/view-plan eng)
              first-template-event (first (filter #(get-in % [:event-key :template-id]) plan-before))
              event-date           (:date first-template-event)
              template-id          (get-in first-template-event [:event-key :template-id])
              original-pr          (:people-required first-template-event)]
          (engine/with-state!-> eng (ops/set-instance-override event-date template-id 4))
          (engine/with-state!-> eng (ops/remove-instance-override event-date template-id))
          (let [plan-after (engine/view-plan eng)
                reverted   (first (filter #(and (= event-date (:date %))
                                                (= template-id (get-in % [:event-key :template-id])))
                                          plan-after))]
            (is (= original-pr (:people-required reverted)))))))))

(deftest test-instance-override-upsert
  (testing "set-instance-override replaces existing override"
    (h/with-test-engine
      (fn [eng]
        (let [plan                 (engine/view-plan eng)
              first-template-event (first (filter #(get-in % [:event-key :template-id]) plan))
              event-date           (:date first-template-event)
              template-id          (get-in first-template-event [:event-key :template-id])]
          (engine/with-state!-> eng (ops/set-instance-override event-date template-id 3))
          (engine/with-state!-> eng (ops/set-instance-override event-date template-id 5))
          (let [db        (engine/view-db eng)
                overrides (:instance-overrides db)
                matching  (filter #(and (= event-date (:event-date %))
                                        (= template-id (:event-template-id %)))
                                  overrides)]
            (is (= 1 (count matching)) "should have exactly one override, not duplicates")
            (is (= 5 (:people-required (first matching))))))))))

(deftest test-token-operations
  (testing "token rotation and lookup"
    (h/with-test-engine
      (fn [eng]
        (let [token (engine/get-token-for-person eng "p1")]
          (is (some? token) "person should have a token after init")
          (let [person (engine/lookup-person-by-token eng token)]
            (is (= "p1" (:id person)))))))))

(deftest test-token-rotation
  (testing "rotating token invalidates old one"
    (h/with-test-engine
      (fn [eng]
        (let [old-token (engine/get-token-for-person eng "p1")
              new-token (engine/generate-token)]
          (engine/with-state!-> eng (ops/rotate-token "p1" new-token))
          (is (not= old-token new-token))
          (is (nil? (engine/lookup-person-by-token eng old-token)))
          (is (some? (engine/lookup-person-by-token eng new-token))))))))

(deftest test-reminder-already-sent-checks-notifications
  (testing "reminder-already-sent? checks :sent-notifications with type :reminder"
    (h/with-test-engine
      (fn [eng]
        (let [event-key {:date "2026-04-01" :template-id "et-1"}]
          (is (not (engine/reminder-already-sent? eng "p1" event-key)))
          (engine/with-state!-> eng (ops/record-notification-sent "p1" event-key :reminder (engine/now-str)))
          (is (engine/reminder-already-sent? eng "p1" event-key))
         ;; :assigned notification should NOT count as a reminder
          (is (not (engine/reminder-already-sent? eng "p2" event-key)))
          (engine/with-state!-> eng (ops/record-notification-sent "p2" event-key :assigned (engine/now-str)))
          (is (not (engine/reminder-already-sent? eng "p2" event-key))))))))

(deftest test-list-sent-notifications-reverse-chrono
  (testing "list-sent-notifications returns newest first"
    (h/with-test-engine
      (fn [eng]
        (let [ek1 {:date "2026-04-01" :template-id "et-1"}
              ek2 {:date "2026-04-05" :template-id "et-2"}]
          (engine/with-state!-> eng (ops/record-notification-sent "p1" ek1 :reminder (engine/now-str)))
          (Thread/sleep 10)
          (engine/with-state!-> eng (ops/record-notification-sent "p2" ek2 :assigned (engine/now-str)))
          (let [notifications (engine/list-sent-notifications eng)]
            (is (= 2 (count notifications)))
           ;; Newest first
            (is (= "p2" (:person-id (first notifications))))
            (is (= "p1" (:person-id (second notifications))))))))))

(deftest test-notification-cap-at-500
  (testing "sent-notifications caps at 500 entries"
    (h/with-test-engine
      (fn [eng]
        (let [ek {:date "2026-04-01" :template-id "et-1"}]
          (dotimes [i 510]
            (engine/with-state!-> eng (ops/record-notification-sent (str "p" i) ek :reminder (engine/now-str))))
          (let [db (engine/view-db eng)]
            (is (= 500 (count (:sent-notifications db))))))))))

;; =============================================================================
;; Assignment override tests
;; =============================================================================

(deftest test-set-assignment-override
  (testing "set-assignment-override overrides appear in plan"
    (h/with-test-engine
      (fn [eng]
        (let [plan      (engine/view-plan eng)
              target    (first (filter #(get-in % [:event-key :template-id]) plan))
              event-key (:event-key target)]
          (engine/with-state!-> eng (ops/set-assignment-override event-key ["p4" "p5"]))
          (let [new-plan (engine/view-plan eng)
                updated  (first (filter #(= (:event-key %) event-key) new-plan))]
            (is (= ["p4" "p5"] (:assigned updated)))))))))

(deftest test-remove-assignment-override
  (testing "removing assignment override reverts to auto-assignment"
    (h/with-test-engine
      (fn [eng]
        (let [plan              (engine/view-plan eng)
              target            (first (filter #(get-in % [:event-key :template-id]) plan))
              event-key         (:event-key target)
              original-assigned (:assigned target)]
          (engine/with-state!-> eng (ops/set-assignment-override event-key ["p4" "p5"]))
          (engine/with-state!-> eng (ops/remove-assignment-override event-key))
          (let [new-plan (engine/view-plan eng)
                reverted (first (filter #(= (:event-key %) event-key) new-plan))]
            (is (= original-assigned (:assigned reverted)))))))))

(deftest test-assignment-override-upsert
  (testing "second set-assignment-override replaces first"
    (h/with-test-engine
      (fn [eng]
        (let [plan      (engine/view-plan eng)
              target    (first (filter #(get-in % [:event-key :template-id]) plan))
              event-key (:event-key target)]
          (engine/with-state!-> eng (ops/set-assignment-override event-key ["p1" "p2"]))
          (engine/with-state!-> eng (ops/set-assignment-override event-key ["p3" "p4"]))
          (let [db        (engine/view-db eng)
                overrides (:assignment-overrides db)
                matching  (filter #(= (:event-date %) (:date event-key)) overrides)]
            (is (= 1 (count matching)) "should have exactly one override")
            (is (= ["p3" "p4"] (:assigned (first matching))))))))))

(deftest test-pending-notifications-includes-reminders
  (testing "corrections + reminders pipeline produces reminders for people in the window"
    (h/with-test-engine
      (fn [eng]
        (let [now (engine/now-str)]
          (engine/with-state!-> eng
            (ops/compute-pending-corrections now)
            (ops/compute-pending-reminders now)))
        (let [notifications (engine/list-sent-notifications eng)
              reminders     (filter #(= :reminder (:type %)) notifications)]
          (is (pos? (count reminders))
              "should produce reminders for people in the window"))))))

(deftest test-ensure-db-strips-sent-reminders
  (testing "ensure-db! migration removes :sent-reminders from DB"
    (let [tmp-dir (str (fs/create-temp-dir {:prefix "sr-migrate-"}))]
      (try
        ;; Create a DB with legacy :sent-reminders key
        (let [db-file (str (fs/path tmp-dir "schedule-db.edn"))]
          (spit db-file
                (pr-str {:event-templates    [{:id              "et-1"
                                               :label           "Wed Evening"
                                               :day-of-week     3
                                               :time-label      :evening
                                               :people-required 2}]
                         :one-off-events     []
                         :absences           []
                         :sent-reminders     [{:person-id  "p1"
                                               :event-date "2026-04-01"
                                               :sent-at    "2026-03-25T10:00:00"}]
                         :sent-notifications []
                         :instance-overrides []
                         :sec-tokens         {}
                         :people             h/test-people}))
          (let [eng (ig/init-key ::engine/engine
                                 {:db-folder   tmp-dir
                                  :people      h/test-people
                                  :base-url    "http://test.local"})
                db  (engine/view-db eng)]
            (is (not (contains? db :sent-reminders)))
            (ig/halt-key! ::engine/engine eng)))
        (finally
          (fs/delete-tree tmp-dir))))))
