(ns dev.freeformsoftware.security-reminder.schedule.projection-test
  (:require
   [clojure.set]
   [clojure.test :refer [deftest is testing]]
   [dev.freeformsoftware.security-reminder.schedule.projection :as proj]))

(def test-people
  [{:id "p1" :name "Alice"}
   {:id "p2" :name "Bob"}
   {:id "p3" :name "Carol"}
   {:id "p4" :name "Dave"}
   {:id "p5" :name "Eve"}])

(def test-templates
  [{:id "et-1" :label "Wed Evening" :day-of-week 3 :time-label :evening :people-required 2}
   {:id "et-2" :label "Sun Morning" :day-of-week 7 :time-label :morning :people-required 2}
   {:id "et-3" :label "Sun Evening" :day-of-week 7 :time-label :evening :people-required 2}])

;; =============================================================================
;; generate-events tests
;; =============================================================================

(deftest test-generate-events
  (testing "templates expand to correct dates within range"
    (let [events (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 2)]
      ;; 2026-03-22 is a Sunday
      ;; Week 1: Sun 3/22 (morning + evening), Wed 3/25 (evening), Sun 3/29 (morning + evening)
      ;; Week 2: Wed 4/1 (evening), Sun 4/5 (morning + evening) — but 4/5 is at exactly 2 weeks out
      (is (pos? (count events)))
      ;; All events should have required keys
      (doseq [e events]
        (is (:event-key e))
        (is (:label e))
        (is (:date e))
        (is (:time-label e))
        (is (:people-required e)))))

  (testing "events are sorted by date then time-label"
    (let [events (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 2)]
      (is (= events (sort-by (juxt :date #({:morning 0 :afternoon 1 :evening 2} (:time-label %))) events)))))

  (testing "one-off events are included"
    (let [one-offs [{:id "oo-1" :label "Special" :date "2026-03-27" :time-label :afternoon :people-required 2}]
          events   (proj/generate-events test-templates one-offs (java.time.LocalDate/parse "2026-03-22") 2)]
      (is (some #(= "Special" (:label %)) events))))

  (testing "one-off events outside range are excluded"
    (let [one-offs [{:id "oo-1" :label "TooEarly" :date "2026-03-21" :time-label :afternoon :people-required 2}
                    {:id "oo-2" :label "TooLate" :date "2027-01-01" :time-label :afternoon :people-required 2}]
          events   (proj/generate-events test-templates one-offs (java.time.LocalDate/parse "2026-03-22") 2)]
      (is (not (some #(= "TooEarly" (:label %)) events)))
      (is (not (some #(= "TooLate" (:label %)) events))))))

;; =============================================================================
;; base-assignments tests
;; =============================================================================

(deftest test-base-assignments-round-robin
  (testing "deterministic assignment covers all people"
    (let [events   (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 8)
          assigned (proj/base-assignments test-people events test-templates)]
      ;; Every event should have exactly people-required assignments
      (doseq [e assigned]
        (is (= (:people-required e) (count (:assigned e)))))
      ;; Over enough events, all people should appear
      (let [all-assigned (set (mapcat :assigned assigned))]
        (is (= (set (map :id test-people)) all-assigned))))))

(deftest test-base-assignments-determinism
  (testing "same inputs produce same outputs"
    (let [events  (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 8)
          result1 (proj/base-assignments test-people events test-templates)
          result2 (proj/base-assignments test-people events test-templates)]
      (is (= result1 result2)))))

;; =============================================================================
;; apply-absences tests
;; =============================================================================

(deftest test-apply-absences-minimal-disruption
  (testing "one absence only changes the affected event"
    (let [events        (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 4)
          assigned      (proj/base-assignments test-people events test-templates)
          ;; Pick the 3rd event and mark first assigned person absent
          target-event  (nth assigned 2)
          absent-person (first (:assigned target-event))
          absence       {:person-id         absent-person
                         :event-date        (:date (:event-key target-event))
                         :event-template-id (:template-id (:event-key target-event))}
          result        (proj/apply-absences test-people assigned [absence])]
      ;; All events except the target should have same assignments
      (doseq [i     (range (count result))
              :when (not= i 2)]
        (is (= (:assigned (nth assigned i))
               (:assigned (nth result i)))
            (str "Event " i " should be unchanged"))))))

(deftest test-apply-absences-substitute-order
  (testing "substitute is deterministic and not already assigned"
    (let [events        (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 4)
          assigned      (proj/base-assignments test-people events test-templates)
          target-event  (nth assigned 0)
          absent-person (first (:assigned target-event))
          absence       {:person-id         absent-person
                         :event-date        (:date (:event-key target-event))
                         :event-template-id (:template-id (:event-key target-event))}
          result        (proj/apply-absences test-people assigned [absence])
          result-event  (nth result 0)]
      ;; The absent person should be in :absent
      (is (some #{absent-person} (:absent result-event)))
      ;; The substitute should not be the same as the absent person
      (is (not (some #{absent-person} (:assigned result-event))))
      ;; The substitute should not duplicate another assigned person
      (is (= (count (set (:assigned result-event)))
             (count (:assigned result-event)))))))

(deftest test-understaffed
  (testing "too many absences marks event as understaffed"
    ;; Use only 3 people, 2 required per event
    (let [small-people    [{:id "p1" :name "A"} {:id "p2" :name "B"} {:id "p3" :name "C"}]
          small-templates [{:id              "et-1"
                            :label           "Test"
                            :day-of-week     3
                            :time-label      :evening
                            :people-required 2}]
          events          (proj/generate-events small-templates
                                                []
                                                (java.time.LocalDate/parse "2026-03-22")
                                                2)
          assigned        (proj/base-assignments small-people events small-templates)
          ;; Mark all 3 people absent for the first event
          first-event     (first assigned)
          absences        (mapv (fn [pid]
                                  {:person-id         pid
                                   :event-date        (:date (:event-key first-event))
                                   :event-template-id (:template-id (:event-key first-event))})
                                ["p1" "p2" "p3"])
          result          (proj/apply-absences small-people assigned absences)
          result-first    (first result)]
      (is (:understaffed? result-first)))))

(deftest test-absence-does-not-cascade
  (testing "adding absence for one event doesn't change other events"
    (let [events   (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 8)
          assigned (proj/base-assignments test-people events test-templates)
          ;; Add absence at event index 5
          target   (nth assigned 5)
          absence  {:person-id         (first (:assigned target))
                    :event-date        (:date (:event-key target))
                    :event-template-id (:template-id (:event-key target))}
          result   (proj/apply-absences test-people assigned [absence])]
      ;; Check events 0-4 and 6+ are unchanged
      (doseq [i     (range (count result))
              :when (not= i 5)]
        (is (= (:assigned (nth assigned i))
               (:assigned (nth result i)))
            (str "Event " i " should not change due to absence at event 5"))))))

(deftest test-absence-avoids-consecutive-assignment
  (testing "substitute avoids person already assigned to adjacent event"
    ;; Use 10 people so there are plenty of non-adjacent candidates
    (let [ten-people           (mapv (fn [i]
                                       {:id   (str "p" (inc i))
                                        :name (str "Person" (inc i))})
                                     (range 10))
          ;; Sun Morning (et-2) and Sun Evening (et-3) are adjacent in sorted order
          events               (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 2)
          assigned             (proj/base-assignments ten-people events test-templates)
          ;; Find the first Sun Morning event
          sun-morning-idx      (first (keep-indexed
                                       (fn [i e] (when (= "et-2" (get-in e [:event-key :template-id])) i))
                                       assigned))
          sun-morning          (nth assigned sun-morning-idx)
          ;; The adjacent Sun Evening event
          sun-evening-idx      (inc sun-morning-idx)
          sun-evening          (nth assigned sun-evening-idx)
          sun-evening-assigned (set (:assigned sun-evening))
          ;; Mark first assigned person on Sun Morning as absent
          absent-person        (first (:assigned sun-morning))
          absence              {:person-id         absent-person
                                :event-date        (:date (:event-key sun-morning))
                                :event-template-id (:template-id (:event-key sun-morning))}
          result               (proj/apply-absences ten-people assigned [absence])
          result-morning       (nth result sun-morning-idx)
          substitute           (first (remove (set (:assigned sun-morning)) (:assigned result-morning)))]
      ;; The substitute should NOT be someone already on Sun Evening
      (is (not (contains? sun-evening-assigned substitute))
          (str "Substitute " substitute
               " should not be on adjacent Sun Evening "
               sun-evening-assigned)))))

(deftest test-absence-consecutive-fallback
  (testing "falls back to adjacent person when no non-adjacent candidate exists"
    ;; 3 people, 2 required per event — only 1 candidate, who will be on adjacent event
    (let [small-people  [{:id "p1" :name "A"} {:id "p2" :name "B"} {:id "p3" :name "C"}]
          ;; Two events on same day so they're adjacent
          two-templates [{:id              "et-2"
                          :label           "Sun Morning"
                          :day-of-week     7
                          :time-label      :morning
                          :people-required 2}
                         {:id              "et-3"
                          :label           "Sun Evening"
                          :day-of-week     7
                          :time-label      :evening
                          :people-required 2}]
          events        (proj/generate-events two-templates [] (java.time.LocalDate/parse "2026-03-22") 2)
          assigned      (proj/base-assignments small-people events two-templates)
          ;; Mark someone absent from the first event
          first-event   (first assigned)
          absent-person (first (:assigned first-event))
          absence       {:person-id         absent-person
                         :event-date        (:date (:event-key first-event))
                         :event-template-id (:template-id (:event-key first-event))}
          result        (proj/apply-absences small-people assigned [absence])
          result-first  (first result)]
      ;; Should still have 2 people assigned (not understaffed)
      (is (= 2 (count (:assigned result-first)))
          "Event should still be fully staffed even if substitute is on adjacent event")
      (is (not (:understaffed? result-first))))))

(deftest test-absence-at-boundary-events
  (testing "absence at first event works (no previous event)"
    (let [events        (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 2)
          assigned      (proj/base-assignments test-people events test-templates)
          first-event   (first assigned)
          absent-person (first (:assigned first-event))
          absence       {:person-id         absent-person
                         :event-date        (:date (:event-key first-event))
                         :event-template-id (:template-id (:event-key first-event))}
          result        (proj/apply-absences test-people assigned [absence])]
      (is (not (some #{absent-person} (:assigned (first result)))))
      (is (= (count (:assigned first-event)) (count (:assigned (first result)))))))

  (testing "absence at last event works (no next event)"
    (let [events        (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 2)
          assigned      (proj/base-assignments test-people events test-templates)
          last-event    (peek assigned)
          absent-person (first (:assigned last-event))
          absence       {:person-id         absent-person
                         :event-date        (:date (:event-key last-event))
                         :event-template-id (:template-id (:event-key last-event))}
          result        (proj/apply-absences test-people assigned [absence])]
      (is (not (some #{absent-person} (:assigned (peek result)))))
      (is (= (count (:assigned last-event)) (count (:assigned (peek result))))))))

;; =============================================================================
;; instance-overrides tests
;; =============================================================================

(deftest test-instance-overrides
  (testing "override changes people-required for a specific instance"
    (let [overrides  [{:event-date "2026-03-25" :event-template-id "et-1" :people-required 4}]
          events     (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 2 overrides)
          wed-mar-25 (first (filter #(and (= "2026-03-25" (:date %))
                                          (= "et-1" (get-in % [:event-key :template-id])))
                                    events))]
      (is (= 4 (:people-required wed-mar-25)) "overridden instance should have 4 people")))

  (testing "non-overridden instances keep template default"
    (let [overrides    [{:event-date "2026-03-25" :event-template-id "et-1" :people-required 4}]
          events       (proj/generate-events test-templates [] (java.time.LocalDate/parse "2026-03-22") 2 overrides)
          other-events (remove #(and (= "2026-03-25" (:date %))
                                     (= "et-1" (get-in % [:event-key :template-id])))
                               events)]
      (doseq [e other-events]
        (is (= 2 (:people-required e)) (str "Event on " (:date e) " " (:label e) " should keep default")))))

  (testing "override flows through project-schedule"
    (let [overrides  [{:event-date "2026-03-25" :event-template-id "et-1" :people-required 3}]
          result     (proj/project-schedule test-people test-templates [] [] "2026-03-22" 2 overrides)
          wed-mar-25 (first (filter #(and (= "2026-03-25" (:date %))
                                          (= "et-1" (get-in % [:event-key :template-id])))
                                    result))]
      (is (= 3 (:people-required wed-mar-25)))
      (is (= 3 (count (:assigned wed-mar-25))) "should assign 3 people"))))

;; =============================================================================
;; Stability tests
;; =============================================================================

(deftest test-base-assignments-stability-across-windows
  (testing "shifting anchor by 1 day keeps overlapping events assigned the same"
    (let [anchor-a    (java.time.LocalDate/parse "2026-03-22")
          anchor-b    (.plusDays anchor-a 1)
          events-a    (proj/generate-events test-templates [] anchor-a 8)
          events-b    (proj/generate-events test-templates [] anchor-b 8)
          assigned-a  (proj/base-assignments test-people events-a test-templates)
          assigned-b  (proj/base-assignments test-people events-b test-templates)
          by-key-a    (into {} (map (juxt :event-key :assigned)) assigned-a)
          by-key-b    (into {} (map (juxt :event-key :assigned)) assigned-b)
          common-keys (clojure.set/intersection (set (keys by-key-a)) (set (keys by-key-b)))]
      (is (pos? (count common-keys)) "should have overlapping events")
      (doseq [k common-keys]
        (is (= (get by-key-a k) (get by-key-b k))
            (str "Event " k " should have same assignment in both windows"))))))

(deftest test-base-assignments-stability-across-weeks
  (testing "shifting anchor by 7 days keeps overlapping events assigned the same"
    (let [anchor-a    (java.time.LocalDate/parse "2026-03-22")
          anchor-b    (.plusDays anchor-a 7)
          events-a    (proj/generate-events test-templates [] anchor-a 8)
          events-b    (proj/generate-events test-templates [] anchor-b 8)
          assigned-a  (proj/base-assignments test-people events-a test-templates)
          assigned-b  (proj/base-assignments test-people events-b test-templates)
          by-key-a    (into {} (map (juxt :event-key :assigned)) assigned-a)
          by-key-b    (into {} (map (juxt :event-key :assigned)) assigned-b)
          common-keys (clojure.set/intersection (set (keys by-key-a)) (set (keys by-key-b)))]
      (is (pos? (count common-keys)) "should have overlapping events")
      (doseq [k common-keys]
        (is (= (get by-key-a k) (get by-key-b k))
            (str "Event " k " should have same assignment in both windows"))))))

;; =============================================================================
;; Assignment override tests
;; =============================================================================

(deftest test-assignment-overrides
  (testing "manual override replaces auto-assigned people"
    (let [overrides  [{:event-date "2026-03-25" :event-template-id "et-1" :assigned ["p4" "p5"]}]
          result     (proj/project-schedule test-people test-templates [] [] "2026-03-22" 4 [] overrides)
          wed-mar-25 (first (filter #(and (= "2026-03-25" (:date %))
                                          (= "et-1" (get-in % [:event-key :template-id])))
                                    result))]
      (is (= ["p4" "p5"] (:assigned wed-mar-25))))))

(deftest test-assignment-overrides-with-absences
  (testing "absence triggers substitution on an overridden event"
    (let [overrides  [{:event-date "2026-03-25" :event-template-id "et-1" :assigned ["p4" "p5"]}]
          absences   [{:person-id "p4" :event-date "2026-03-25" :event-template-id "et-1"}]
          result     (proj/project-schedule test-people test-templates [] absences "2026-03-22" 4 [] overrides)
          wed-mar-25 (first (filter #(and (= "2026-03-25" (:date %))
                                          (= "et-1" (get-in % [:event-key :template-id])))
                                    result))]
      ;; p4 should have been replaced by a substitute
      (is (not (some #{"p4"} (:assigned wed-mar-25))))
      ;; p5 should still be assigned
      (is (some #{"p5"} (:assigned wed-mar-25)))
      ;; p4 should appear in :absent
      (is (some #{"p4"} (:absent wed-mar-25))))))

;; =============================================================================
;; project-schedule integration
;; =============================================================================

(deftest test-project-schedule-integration
  (testing "full pipeline produces valid output"
    (let [result (proj/project-schedule test-people test-templates [] [] "2026-03-22" 4)]
      (is (vector? result))
      (is (pos? (count result)))
      (doseq [entry result]
        (is (:event-key entry))
        (is (:assigned entry))
        (is (contains? entry :absent))
        (is (contains? entry :understaffed?))))))
