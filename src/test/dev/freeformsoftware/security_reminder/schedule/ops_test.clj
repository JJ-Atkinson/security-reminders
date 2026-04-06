(ns dev.freeformsoftware.security-reminder.schedule.ops-test
  "Pure scenario tests for schedule.ops — no I/O, no temp dirs, no SMS."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.freeformsoftware.security-reminder.schedule.ops :as ops]
   [dev.freeformsoftware.security-reminder.schedule.projection :as projection]))

(def test-people
  [{:id "p1" :name "Alice" :phone "+1111" :admin? false}
   {:id "p2" :name "Bob" :phone "+2222" :admin? false}
   {:id "p3" :name "Carol" :phone "+3333" :admin? false}
   {:id "p4" :name "Dave" :phone "+4444" :admin? false}
   {:id "p5" :name "Eve" :phone "+5555" :admin? true}])

(def test-templates
  [{:id "et-1" :label "Wed Evening" :day-of-week 3 :time-label :evening :people-required 2}
   {:id "et-2" :label "Sun Morning" :day-of-week 7 :time-label :morning :people-required 2}
   {:id "et-3" :label "Sun Evening" :day-of-week 7 :time-label :evening :people-required 2}])

(defn make-test-state
  [&
   {:keys [today people notify-at-days-before]
    :or   {today "2026-03-25" people test-people notify-at-days-before [8 1]}}]
  (let [db {:people               people
            :event-templates      test-templates
            :one-off-events       []
            :absences             []
            :sent-notifications   []
            :instance-overrides   []
            :assignment-overrides []
            :sec-tokens           {}}]
    {:schedule-db           db
     :schedule-plan         (projection/project-schedule
                             (:people db)
                             (:event-templates db)
                             (:one-off-events db)
                             (:absences db)
                             today
                             8
                             (:instance-overrides db)
                             (:assignment-overrides db))
     :notify-at-days-before notify-at-days-before
     :actions               []}))

;; =============================================================================
;; Stability tests
;; =============================================================================

(deftest test-scenario-stable-across-days
  (testing "advancing day N times keeps same event assigned to same people"
    (let [state-day1 (make-test-state :today "2026-03-25")
          plan1      (:schedule-plan state-day1)
          ;; Pick an event that exists in both windows
          target-key {:date "2026-04-01" :template-id "et-1"}
          event1     (first (filter #(= target-key (:event-key %)) plan1))

          ;; Advance to next day (simulate inc-day!)
          state-day2 (ops/refresh-plan state-day1 "2026-03-26")
          plan2      (:schedule-plan state-day2)
          event2     (first (filter #(= target-key (:event-key %)) plan2))

          ;; And again
          state-day3 (ops/refresh-plan state-day2 "2026-03-27")
          plan3      (:schedule-plan state-day3)
          event3     (first (filter #(= target-key (:event-key %)) plan3))]
      (is (some? event1) "event should exist in day1 window")
      (is (some? event2) "event should exist in day2 window")
      (is (some? event3) "event should exist in day3 window")
      (is (= (:assigned event1) (:assigned event2))
          "same assignment after 1-day advance")
      (is (= (:assigned event2) (:assigned event3))
          "same assignment after 2-day advance"))))

;; =============================================================================
;; Notification tests
;; =============================================================================

(deftest test-scenario-absence-triggers-correction-action
  (testing "noting absence then computing corrections produces :send-correction action"
    (let [state              (make-test-state :today "2026-03-25")
          plan               (:schedule-plan state)
          ;; Find a future event and its first assigned person
          target             (first (filter #(pos? (compare (:date %) "2026-03-25")) plan))
          person-id          (first (:assigned target))
          event-key          (:event-key target)

          ;; First send a "reminder" notification so the correction algorithm has something to diff against
          state              (update state
                                     :schedule-db
                                     (fn [db]
                                       (update db
                                               :sent-notifications
                                               conj
                                               {:person-id         person-id
                                                :event-date        (:date event-key)
                                                :event-template-id (:template-id event-key)
                                                :type              :reminder
                                                :sent-at           "2026-03-25T08:00:00"})))
          ;; Note absence — person is no longer assigned
          state              (ops/note-absence state person-id event-key "2026-03-25")

          ;; Verify person is no longer assigned
          updated-event      (first (filter #(= event-key (:event-key %)) (:schedule-plan state)))
          _ (is (not (some #{person-id} (:assigned updated-event)))
                "person should no longer be assigned after absence")

          ;; Compute corrections
          state              (ops/compute-pending-corrections state "2026-03-25" "2026-03-25T09:00:00")
          correction-actions (filter #(= :send-correction (:type %)) (:actions state))]
      (is (pos? (count correction-actions)) "should have correction actions")
      (is (some #(and (= person-id (:person-id %))
                      (= :rescinded (:correction-type %)))
                correction-actions)
          "should have a :rescinded correction for the absent person"))))

(deftest test-scenario-reminder-dedup
  (testing "computing reminders twice only produces actions once"
    (let [state    (make-test-state :today "2026-03-25")
          state1   (ops/compute-pending-reminders state "2026-03-25" "2026-03-25T08:00:00")
          actions1 (count (:actions state1))
          ;; Second pass (same state with sent-notifications recorded)
          state2   (ops/compute-pending-reminders state1 "2026-03-25" "2026-03-25T09:00:00")
          actions2 (count (:actions state2))]
      (is (pos? actions1) "first pass should produce actions")
      (is (= actions1 actions2) "second pass should not produce additional actions"))))

(deftest test-exclusive-windows
  (testing "event at day 1 only gets group-1, not group-8"
    (let [;; today = 2026-03-28, so Sun events on 2026-03-29 are 1 day out
          state         (make-test-state :today "2026-03-28")
          state'        (ops/compute-pending-reminders state "2026-03-28" "2026-03-28T08:00:00")
          day-1-actions (filter #(= "2026-03-29" (:event-date %)) (:actions state'))]
      (is (pos? (count day-1-actions)) "should have actions for day+1 events")
      (doseq [a day-1-actions]
        (is (= 1 (:reminder-group a)) "day+1 event should be in group 1")))))

(deftest test-normal-two-phase-flow
  (testing "event at day 8 gets group-8; same event at day 1 gets group-1"
    (let [;; today = 2026-03-21, Sun events on 2026-03-29 are exactly 8 days out
          target-date     "2026-03-29"
          state           (make-test-state :today "2026-03-21")
          ;; At day 8: should get group-8 reminders
          state1          (ops/compute-pending-reminders state "2026-03-21" "2026-03-21T08:00:00")
          group-8-actions (filter #(and (= target-date (:event-date %))
                                        (= 8 (:reminder-group %)))
                                  (:actions state1))
          ;; Advance to day 1 (2026-03-28): same event is now 1 day out
          state2          (ops/refresh-plan state1 "2026-03-28")
          state3          (ops/compute-pending-reminders state2 "2026-03-28" "2026-03-28T08:00:00")
          group-1-actions (filter #(and (= target-date (:event-date %))
                                        (= 1 (:reminder-group %)))
                                  (:actions state3))]
      (is (pos? (count group-8-actions)) "should have group-8 actions at day 8")
      (is (pos? (count group-1-actions)) "should have group-1 actions at day 1"))))

(deftest test-late-substitute-day-4
  (testing "person assigned at day 4 gets group-8 only (falls in (1,8] window)"
    (let [;; today = 2026-03-25, Sun events on 2026-03-29 are 4 days out
          state          (make-test-state :today "2026-03-25")
          state'         (ops/compute-pending-reminders state "2026-03-25" "2026-03-25T08:00:00")
          target-actions (filter #(= "2026-03-29" (:event-date %)) (:actions state'))]
      (is (pos? (count target-actions)) "should have actions for day+4 events")
      (doseq [a target-actions]
        (is (= 8 (:reminder-group a)) "day+4 event should be in group 8")))))

(deftest test-late-substitute-day-1
  (testing "person assigned at day 1 gets group-1 only (no double-ping)"
    (let [;; today = 2026-03-28, Sun events on 2026-03-29 are 1 day out
          state          (make-test-state :today "2026-03-28")
          state'         (ops/compute-pending-reminders state "2026-03-28" "2026-03-28T08:00:00")
          target-actions (filter #(= "2026-03-29" (:event-date %)) (:actions state'))]
      (is (pos? (count target-actions)) "should have actions for day+1 events")
      (doseq [a target-actions]
        (is (= 1 (:reminder-group a)) "day+1 event should be group 1 only"))
      (is (empty? (filter #(and (= "2026-03-29" (:event-date %))
                                (= 8 (:reminder-group %)))
                          (:actions state')))
          "should not have group-8 actions for day+1 event"))))

(deftest test-late-substitute-day-0
  (testing "person assigned day 0 gets group-1 only"
    (let [state        (make-test-state :today "2026-03-25")
          ;; Events happening today (day 0)
          today-events (filterv #(= "2026-03-25" (:date %)) (:schedule-plan state))]
      (when (seq today-events)
        (let [state'        (ops/compute-pending-reminders state "2026-03-25" "2026-03-25T08:00:00")
              today-actions (filter #(= "2026-03-25" (:event-date %)) (:actions state'))]
          (doseq [a today-actions]
            (is (= 1 (:reminder-group a)) "day-0 event should be group 1")))))))

(deftest test-scenario-assignment-override-with-correction
  (testing "setting an override then computing corrections produces rescinded action for displaced person"
    (let [state              (make-test-state :today "2026-03-25")
          plan               (:schedule-plan state)
          ;; Find a future event
          target             (first (filter #(pos? (compare (:date %) "2026-03-25")) plan))
          event-key          (:event-key target)
          original-assigned  (:assigned target)
          displaced-person   (first original-assigned)

          ;; Record a reminder for the displaced person (so correction algorithm has something to diff)
          state              (update state
                                     :schedule-db
                                     (fn [db]
                                       (update db
                                               :sent-notifications
                                               conj
                                               {:person-id         displaced-person
                                                :event-date        (:date event-key)
                                                :event-template-id (:template-id event-key)
                                                :type              :reminder
                                                :sent-at           "2026-03-25T08:00:00"})))

          ;; Override to assign completely different people
          new-people         (vec (remove (set original-assigned) (map :id test-people)))
          state              (ops/set-assignment-override state event-key (take 2 new-people) "2026-03-25")

          ;; Verify override took effect
          updated-event      (first (filter #(= event-key (:event-key %)) (:schedule-plan state)))
          _ (is (not (some #{displaced-person} (:assigned updated-event)))
                "displaced person should not be in new assignment")

          ;; Compute corrections
          state              (ops/compute-pending-corrections state "2026-03-25" "2026-03-25T09:00:00")
          correction-actions (filter #(= :send-correction (:type %)) (:actions state))]
      (is (some #(and (= displaced-person (:person-id %))
                      (= :rescinded (:correction-type %)))
                correction-actions)
          "displaced person should get :rescinded correction"))))

(deftest test-scenario-override-then-absence
  (testing "setting override then noting absence on overridden person triggers substitution"
    (let [state         (make-test-state :today "2026-03-25")
          plan          (:schedule-plan state)
          ;; Find a future event
          target        (first (filter #(pos? (compare (:date %) "2026-03-25")) plan))
          event-key     (:event-key target)

          ;; Override to assign p4 and p5
          state         (ops/set-assignment-override state event-key ["p4" "p5"] "2026-03-25")

          ;; Now mark p4 absent
          state         (ops/note-absence state "p4" event-key "2026-03-25")

          ;; Verify p4 is not assigned (substituted)
          updated-event (first (filter #(= event-key (:event-key %)) (:schedule-plan state)))]
      (is (not (some #{"p4"} (:assigned updated-event)))
          "p4 should be substituted out")
      (is (some #{"p5"} (:assigned updated-event))
          "p5 should still be assigned")
      (is (some #{"p4"} (:absent updated-event))
          "p4 should be in absent list"))))
