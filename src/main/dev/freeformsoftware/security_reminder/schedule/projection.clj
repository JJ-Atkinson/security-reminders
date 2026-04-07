(ns dev.freeformsoftware.security-reminder.schedule.projection
  "Pure schedule projection algorithm. No side effects, no randomness.
   All functions are deterministic — same inputs always produce same outputs."
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [dev.freeformsoftware.security-reminder.db.schema :as schema])
  (:import
    [java.time LocalDate DayOfWeek]
    [java.time.format DateTimeFormatter]
    [java.time.temporal ChronoUnit]))

(set! *warn-on-reflection* true)

(def ^:private time-label-order
  {:morning 0 :afternoon 1 :evening 2})

(def ^:private date-formatter DateTimeFormatter/ISO_LOCAL_DATE)
(def ^:private display-formatter (DateTimeFormatter/ofPattern "MMM d, ''yy"))

(defn- parse-date
  ^LocalDate [^String s]
  (LocalDate/parse s date-formatter))

(defn- format-date
  ^String [^LocalDate d]
  (.format d date-formatter))

(defn display-date
  "Format an ISO date string (YYYY-MM-DD) as 'Jul 3, '26' for user display."
  ^String [^String iso-date-str]
  (.format (parse-date iso-date-str) display-formatter))

(defn- day-of-week-to-java
  "Convert 1=Mon..7=Sun to java.time.DayOfWeek"
  ^DayOfWeek [dow]
  (DayOfWeek/of (int dow)))

(>defn generate-events
  "Expand event templates and one-off events into concrete dated events.
   Returns sorted vector of {:event-key {:date :template-id/:one-off-id}
                             :label :date :time-label :people-required}
   instance-overrides: vector of {:event-date :event-template-id :people-required}"
  ([event-templates one-off-events anchor-date weeks]
   [sequential? sequential? any? :int => vector?]
   (generate-events event-templates one-off-events anchor-date weeks []))
  ([event-templates one-off-events anchor-date weeks instance-overrides]
   [sequential? sequential? any? :int sequential? => vector?]
   (let [^LocalDate anchor-date anchor-date
         end-date (.plusWeeks anchor-date (long weeks))
         override-lookup (into
                          {}
                          (map
                           (fn [o]
                             [[(str (:event-date o)) (str (:event-template-id o))]
                              (:people-required o)]))
                          instance-overrides)
         template-events
         (for [template event-templates
               :let     [dow       (day-of-week-to-java (:day-of-week template))
                         ;; Find first matching day on or after anchor
                         first-day (let [anchor-dow (.getDayOfWeek anchor-date)
                                         days-ahead (mod (- (.getValue dow) (.getValue anchor-dow)) 7)
                                         candidate  (.plusDays anchor-date days-ahead)]
                                     (if (.isBefore candidate anchor-date)
                                       (.plusWeeks candidate 1)
                                       candidate))]
               date     (->>
                         (iterate #(.plusWeeks ^LocalDate % 1) first-day)
                         (take-while #(.isBefore ^LocalDate % end-date)))
               :let     [date-str    (format-date date)
                         override-pr (get override-lookup [date-str (:id template)])]]
           {:event-key       {:date        date-str
                              :template-id (:id template)}
            :label           (:label template)
            :date            date-str
            :time-label      (:time-label template)
            :people-required (or override-pr (:people-required template))})

         oneoff-events
         (for [oo    one-off-events
               :let  [d (parse-date (:date oo))]
               :when (and
                      (not (.isBefore d anchor-date))
                      (.isBefore d end-date))]
           {:event-key       {:date       (:date oo)
                              :one-off-id (:id oo)}
            :label           (:label oo)
            :date            (:date oo)
            :time-label      (:time-label oo)
            :people-required (:people-required oo)})]

     (->>
      (concat template-events oneoff-events)
      (sort-by (juxt :date #(get time-label-order (:time-label %) 99) :label))
      vec))))

;; Fixed Monday epoch for stable ordinal computation.
;; All weekly ordinals are relative to this date so adding/removing weeks
;; from the projection window doesn't shift assignments.
(def ^:private ^long ref-monday-epoch
  (.toEpochDay (LocalDate/parse "2026-01-05")))

(defn- weekly-slot-index
  "Build a lookup from [day-of-week time-label-sort-key label] → 0-based position
   across all event templates. Used to give each weekly slot a stable intra-week index."
  [event-templates]
  (let [tuples (->> event-templates
                    (mapv (fn [t] [(:day-of-week t)
                                   (get time-label-order (:time-label t) 99)
                                   (:label t)]))
                    distinct
                    (sort-by identity)
                    vec)]
    (into {} (map-indexed (fn [i t] [t i]) tuples))))

(defn- stable-ordinal
  "Compute a stable integer ordinal for an event that doesn't change when the
   projection window slides. Template events use week-number * slots-per-week + slot-index.
   One-off events use the absolute hash of their event-key."
  [event slot-lookup events-per-week]
  (if (:template-id (:event-key event))
    ;; Template event: deterministic from calendar position
    (let [^LocalDate d (parse-date (:date event))
          day-offset   (.until (LocalDate/ofEpochDay ref-monday-epoch) d ChronoUnit/DAYS)
          week-number  (quot day-offset 7)
          tuple        [(.getValue (.getDayOfWeek d))
                        (get time-label-order (:time-label event) 99)
                        (:label event)]
          slot         (get slot-lookup tuple 0)]
      (+ (* week-number (long events-per-week)) slot))
    ;; One-off: stable hash of event-key
    (Math/abs (long (hash (:event-key event))))))

(>defn base-assignments
  "Deterministic modular round-robin assignment using stable ordinals.
   people: sorted vector of person maps (sorted by :id).
   events: sorted vector from generate-events.
   event-templates: the original templates (needed for slot-index computation).
   Returns events with :assigned [person-id ...] added."
  [people events event-templates]
  [vector? vector? sequential? => vector?]
  (let [n               (count people)
        max-required    (apply max 1 (map :people-required events))
        slot-lookup     (weekly-slot-index event-templates)
        events-per-week (max 1 (count slot-lookup))]
    (mapv
     (fn [event]
       (let [ordinal  (stable-ordinal event slot-lookup events-per-week)
             required (:people-required event)
             assigned (vec
                       (for [j (range required)]
                         (:id
                          (nth
                           people
                           (mod (+ (* ordinal max-required) j) n)))))]
         (assoc event :assigned assigned)))
     events)))

(>defn apply-assignment-overrides
  "Apply manual assignment overrides to events. An override specifies exactly
   which people are assigned to a specific event instance.
   overrides: vector of {:event-date :event-template-id/:one-off-event-id :assigned [person-ids]}"
  [events overrides]
  [vector? sequential? => vector?]
  (if (empty? overrides)
    events
    (let [override-lookup
          (into
           {}
           (map
            (fn [o]
              [(cond-> {:date (:event-date o)}
                 (:event-template-id o) (assoc :template-id (:event-template-id o))
                 (:one-off-event-id o)  (assoc :one-off-id (:one-off-event-id o)))
               (:assigned o)]))
           overrides)]
      (mapv
       (fn [event]
         (if-let [assigned (get override-lookup (:event-key event))]
           (assoc event :assigned assigned)
           event))
       events))))

(>defn apply-absences
  "Overlay absences onto assigned events with minimal disruption.
   For each absent person in an event, find a deterministic substitute.
   Only the affected event slot changes — all other events are untouched."
  [people events absences]
  [vector? vector? sequential? => vector?]
  (let [n              (count people)
        people-ids     (mapv :id people)
        ;; Index absences by [person-id event-key] for fast lookup
        absence-set    (set
                        (map
                         (fn [a]
                           {:person-id (:person-id a)
                            :event-key (schema/flat-keys->event-key a)})
                         absences))
        absence-lookup (fn [person-id event-key]
                         (or
                          (contains? absence-set {:person-id person-id :event-key event-key})
                          (some
                           (fn [a]
                             (and
                              (= person-id (:person-id a))
                              (schema/flat-record-matches-event-key? a event-key)))
                           absences)))]
    (first
     (reduce
      (fn [[result-events prev-assigned] idx]
        (let [event              (nth events idx)
              event-key          (:event-key event)
              assigned           (:assigned event)
              ;; Find which assigned people are absent
              absent-ids         (filterv #(absence-lookup % event-key) assigned)
              ;; All people who declared absent for this event (assigned or not)
              all-event-absent   (set
                                  (keep
                                   (fn [a]
                                     (when (schema/flat-record-matches-event-key? a event-key)
                                       (:person-id a)))
                                   absences))
              ;; Adjacent event assignments for avoiding consecutive substitution
              next-base-assigned (if (< (inc idx) (count events))
                                   (set (:assigned (nth events (inc idx))))
                                   #{})
              adjacent-ids       (into (or prev-assigned #{}) next-base-assigned)]
          (if (empty? absent-ids)
            [(conj result-events (assoc event :absent (vec all-event-absent) :understaffed? false))
             (set assigned)]
            ;; Replace absent people with substitutes
            (let [new-assigned
                  (reduce
                   (fn [current-assigned absent-id]
                     (let [absent-idx  (.indexOf ^java.util.List people-ids absent-id)
                           current-set (set current-assigned)
                           candidates  (->>
                                        (range 1 n)
                                        (map #(nth people-ids (mod (+ absent-idx %) n)))
                                        (remove current-set)
                                        (remove all-event-absent))
                           preferred   (remove adjacent-ids candidates)
                           substitute  (or (first preferred) (first candidates))]
                       (if substitute
                         (mapv #(if (= % absent-id) substitute %) current-assigned)
                         current-assigned)))
                   assigned
                   absent-ids)
                  still-absent (filterv #(contains? all-event-absent %) new-assigned)
                  understaffed? (boolean (seq still-absent))]
              [(conj
                result-events
                (assoc
                 event
                 :assigned      new-assigned
                 :absent        (vec all-event-absent)
                 :understaffed? understaffed?))
               (set new-assigned)]))))
      [[] nil]
      (range (count events))))))

(>defn project-schedule
  "Generate the full projected schedule.
   people: vector of person maps, will be sorted by :id
   event-templates: vector of event template maps
   one-off-events: vector of one-off event maps
   absences: vector of absence maps
   anchor-date: LocalDate or date string for the start of the projection window
   weeks: number of weeks to project (default 8)
   instance-overrides: vector of per-instance people-required overrides
   assignment-overrides: vector of per-instance assignment overrides"
  ([people event-templates one-off-events absences anchor-date]
   [sequential? sequential? sequential? sequential? any? => vector?]
   (project-schedule people event-templates one-off-events absences anchor-date 8))
  ([people event-templates one-off-events absences anchor-date weeks]
   [sequential? sequential? sequential? sequential? any? :int => vector?]
   (project-schedule people event-templates one-off-events absences anchor-date weeks [] []))
  ([people event-templates one-off-events absences anchor-date weeks instance-overrides]
   [sequential? sequential? sequential? sequential? any? :int sequential? => vector?]
   (project-schedule people event-templates one-off-events absences anchor-date weeks instance-overrides []))
  ([people event-templates one-off-events absences anchor-date weeks instance-overrides assignment-overrides]
   [sequential? sequential? sequential? sequential? any? :int sequential? sequential? => vector?]
   (let [anchor            (if (string? anchor-date) (parse-date anchor-date) anchor-date)
         sorted-people     (vec (sort-by :id people))
         events            (generate-events event-templates one-off-events anchor weeks instance-overrides)
         assigned-events   (base-assignments sorted-people events event-templates)
         overridden-events (apply-assignment-overrides assigned-events assignment-overrides)]
     (apply-absences sorted-people overridden-events absences))))

(comment
  (def test-people
    [{:id "p1" :name "Alice"}
     {:id "p2" :name "Bob"}
     {:id "p3" :name "Carol"}])
  (def test-templates [{:id "et-1" :label "Wed Evening" :day-of-week 3 :time-label :evening :people-required 2}])
  (project-schedule test-people test-templates [] [] "2026-03-22")
  (project-schedule test-people test-templates [] [] "2026-03-22" 8 [] []))
