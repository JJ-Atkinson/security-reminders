(ns dev.freeformsoftware.security-reminder.schedule.ops
  "Pure state operations for the schedule engine. All functions take a state map
   and return a (possibly updated) state map. No I/O, no side effects.
   State shape: {:schedule-db {...} :schedule-plan [...] :actions [...]}"
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [dev.freeformsoftware.security-reminder.db.schema :as schema]
   [dev.freeformsoftware.security-reminder.schedule.notifications :as notifications]
   [dev.freeformsoftware.security-reminder.schedule.projection :as projection])
  (:import
   [java.time LocalDate]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- regen-plan
  "Recompute :schedule-plan from :schedule-db."
  [state today-str]
  (let [db (:schedule-db state)]
    (assoc state
           :schedule-plan
           (projection/project-schedule
            (:people db)
            (:event-templates db)
            (:one-off-events db)
            (:absences db)
            today-str
            8
            (:instance-overrides db [])
            (:assignment-overrides db [])))))

;; =============================================================================
;; DB mutation ops (state → state, regen plan)
;; =============================================================================

(>defn refresh-plan
       "Recompute the plan from current db state."
       [state today-str]
       [::schema/state ::schema/date-str => map?]
       (regen-plan state today-str))

(>defn note-absence
       "Toggle absence for a person on an event. Add if missing, remove if present."
       [state person-id event-key today-str]
       [::schema/state :string ::schema/event-key ::schema/date-str => map?]
       (let [match-keys (schema/event-key->flat-keys event-key)
             absence    (assoc match-keys :person-id person-id)]
         (-> state
             (update :schedule-db
                     (fn [db]
                       (let [existing (some #(when (= (select-keys % (keys absence))
                                                      absence)
                                               %)
                                            (:absences db))]
                         (if existing
                           (update db :absences (fn [abs] (vec (remove #(= % existing) abs))))
                           (update db :absences conj absence)))))
             (regen-plan today-str))))

(>defn add-person
       "Add a new person to the DB. Caller provides the full person map including :id."
       [state person-map today-str]
       [::schema/state ::schema/person ::schema/date-str => map?]
       (-> state
           (update-in [:schedule-db :people] conj person-map)
           (regen-plan today-str)))

(>defn remove-person
       "Remove a person from the DB. Also removes their token, absences, and notifications."
       [state person-id today-str]
       [::schema/state :string ::schema/date-str => map?]
       (-> state
           (update :schedule-db
                   (fn [db]
                     (-> db
                         (update :people (fn [ps] (vec (remove #(= (:id %) person-id) ps))))
                         (update :sec-tokens (fn [tokens] (into {} (remove (fn [[_tok pid]] (= pid person-id))) tokens)))
                         (update :absences (fn [abs] (vec (remove #(= (:person-id %) person-id) abs))))
                         (update :sent-notifications (fn [ns] (vec (remove #(= (:person-id %) person-id) ns)))))))
           (regen-plan today-str)))

(>defn add-one-off
       "Add a one-off event. Caller provides the full event map including :id."
       [state event-map today-str]
       [::schema/state ::schema/one-off-event ::schema/date-str => map?]
       (-> state
           (update-in [:schedule-db :one-off-events] conj event-map)
           (regen-plan today-str)))

(>defn remove-one-off
       "Remove a one-off event by ID. Also removes related absences."
       [state event-id today-str]
       [::schema/state :string ::schema/date-str => map?]
       (-> state
           (update :schedule-db
                   (fn [db]
                     (-> db
                         (update :one-off-events (fn [evts] (vec (remove #(= (:id %) event-id) evts))))
                         (update :absences (fn [abs] (vec (remove #(= (:one-off-event-id %) event-id) abs)))))))
           (regen-plan today-str)))

(>defn update-template
       "Modify an event template (e.g. people-required)."
       [state template-id updates today-str]
       [::schema/state :string map? ::schema/date-str => map?]
       (-> state
           (update-in [:schedule-db :event-templates]
                      (fn [templates]
                        (mapv (fn [t]
                                (if (= (:id t) template-id)
                                  (merge t updates)
                                  t))
                              templates)))
           (regen-plan today-str)))

(>defn set-instance-override
       "Upsert a per-instance people-required override."
       [state event-date template-id people-required today-str]
       [::schema/state ::schema/date-str :string :int ::schema/date-str => map?]
       (-> state
           (update :schedule-db
                   (fn [db]
                     (let [overrides (or (:instance-overrides db) [])
                           without   (vec (remove #(and (= (:event-date %) event-date)
                                                        (= (:event-template-id %) template-id))
                                                  overrides))]
                       (assoc db
                              :instance-overrides
                              (conj without
                                    {:event-date        event-date
                                     :event-template-id template-id
                                     :people-required   people-required})))))
           (regen-plan today-str)))

(>defn remove-instance-override
       "Remove a per-instance override."
       [state event-date template-id today-str]
       [::schema/state ::schema/date-str :string ::schema/date-str => map?]
       (-> state
           (update-in [:schedule-db :instance-overrides]
                      (fn [overrides]
                        (vec (remove #(and (= (:event-date %) event-date)
                                           (= (:event-template-id %) template-id))
                                     (or overrides [])))))
           (regen-plan today-str)))

(>defn set-assignment-override
       "Set a manual assignment override for a specific event instance."
       [state event-key assigned-ids today-str]
       [::schema/state ::schema/event-key sequential? ::schema/date-str => map?]
       (let [match-keys (schema/event-key->flat-keys event-key)]
         (-> state
             (update :schedule-db
                     (fn [db]
                       (let [overrides (or (:assignment-overrides db) [])
                             without   (vec (remove #(= (select-keys % (keys match-keys))
                                                        match-keys)
                                                    overrides))]
                         (assoc db
                                :assignment-overrides
                                (conj without (assoc match-keys :assigned (vec assigned-ids)))))))
             (regen-plan today-str))))

(>defn remove-assignment-override
       "Remove a manual assignment override for a specific event instance."
       [state event-key today-str]
       [::schema/state ::schema/event-key ::schema/date-str => map?]
       (let [match-keys (schema/event-key->flat-keys event-key)]
         (-> state
             (update-in [:schedule-db :assignment-overrides]
                        (fn [overrides]
                          (vec (remove #(= (select-keys % (keys match-keys))
                                           match-keys)
                                       (or overrides [])))))
             (regen-plan today-str))))

;; =============================================================================
;; Token ops
;; =============================================================================

(>defn rotate-token
       "Replace a person's token. Caller provides the new token."
       [state person-id new-token]
       [::schema/state :string :string => map?]
       (update state
               :schedule-db
               (fn [db]
                 (let [cleaned (into {}
                                     (remove (fn [[_tok pid]] (= pid person-id)))
                                     (:sec-tokens db))]
                   (assoc db :sec-tokens (assoc cleaned new-token person-id))))))

(defn update-person-field
  "Update a single field on a person in the schedule-db. Returns updated state."
  [state person-id field value]
  (update-in state [:schedule-db :people]
             (fn [people]
               (mapv (fn [p] (if (= (:id p) person-id) (assoc p field value) p))
                     people))))

(>defn ensure-tokens
       "Set tokens for people who don't have one. Caller provides {person-id -> token} map."
       [state token-map]
       [::schema/state map? => map?]
       (update state
               :schedule-db
               (fn [db]
                 (let [existing-person-ids (set (vals (:sec-tokens db)))
                       missing-people      (remove #(existing-person-ids (:id %)) (:people db))]
                   (reduce (fn [db person]
                             (if-let [token (get token-map (:id person))]
                               (update db :sec-tokens assoc token (:id person))
                               db))
                           db
                           missing-people)))))

;; =============================================================================
;; Notification ops (state → state + accreted actions)
;; =============================================================================

(defn- notification-matches?
  "Check if a notification matches a person+event combination for a given type."
  [notification person-id event-key type]
  (and (= type (:type notification))
       (= person-id (:person-id notification))
       (schema/flat-record-matches-event-key? notification event-key)))

(defn- reminder-sent-for-group?
  "Check if a reminder notification was already sent for person+event+group."
  [notification person-id event-key group]
  (and (= :reminder (:type notification))
       (= group (:sent-for-reminder-group notification))
       (= person-id (:person-id notification))
       (schema/flat-record-matches-event-key? notification event-key)))

(def ^:private notification-cap 500)

(defn- record-notification
  "Add a notification entry to :sent-notifications, capping at `notification-cap`."
  [db person-id event-key type now-str & {:keys [sent-for-reminder-group]}]
  (let [entry         (cond-> (merge (schema/event-key->flat-keys event-key)
                                     {:person-id person-id :type type :sent-at now-str})
                        sent-for-reminder-group (assoc :sent-for-reminder-group sent-for-reminder-group))
        notifications (conj (or (:sent-notifications db) []) entry)
        capped        (vec (take-last notification-cap notifications))]
    (assoc db :sent-notifications capped)))

(defn record-welcome-notification
  "Add a welcome notification entry (no event-key) to :sent-notifications."
  [db person-id now-str]
  (let [entry         {:person-id person-id :type :welcome :sent-at now-str}
        notifications (conj (or (:sent-notifications db) []) entry)
        capped        (vec (take-last notification-cap notifications))]
    (assoc db :sent-notifications capped)))

(defn- build-exclusive-windows
  "Given notify-days sorted descending (e.g. [8 1]), build exclusive windows.
   Each window is [group lower-bound-exclusive? lower-bound upper-bound].
   Last (smallest) group has inclusive lower bound of 0.
   Others have exclusive lower bound = next-smaller group."
  [notify-days]
  (let [sorted (vec (sort > notify-days))]
    (map-indexed
     (fn [i group]
       (let [last? (= i (dec (count sorted)))
             lower (if last? 0 (nth sorted (inc i)))]
         {:group            group
          :lower            lower
          :lower-inclusive? last?
          :upper            group}))
     sorted)))

(>defn compute-pending-reminders
       "Check plan events within exclusive windows derived from :notify-at-days-before
   in state and produce :send-reminder actions. Each person+event gets at most one
   notification per group. Handles catch-up naturally — anyone in the window without
   a notification for that group gets one."
       [state today-str now-str]
       [::schema/state ::schema/date-str ::schema/iso-datetime => map?]
       (let [plan    (:schedule-plan state)
             today   (LocalDate/parse today-str)
             windows (build-exclusive-windows (:notify-at-days-before state))]
         (reduce
          (fn [state {:keys [group lower lower-inclusive? upper]}]
            (let [events (filterv
                          (fn [entry]
                            (let [event-date (LocalDate/parse (:date entry))
                                  days-until (.until today event-date java.time.temporal.ChronoUnit/DAYS)]
                              (and (<= days-until upper)
                                   (if lower-inclusive?
                                     (>= days-until lower)
                                     (> days-until lower)))))
                          plan)]
              (reduce
               (fn [state event]
                 (reduce
                  (fn [state person-id]
                    (let [event-key     (:event-key event)
                          already-sent? (some #(reminder-sent-for-group? % person-id event-key group)
                                              (get-in state [:schedule-db :sent-notifications]))]
                      (if already-sent?
                        state
                        (-> state
                            (update :schedule-db
                                    record-notification
                                    person-id
                                    event-key
                                    :reminder                now-str
                                    :sent-for-reminder-group group)
                            (update :actions
                                    conj
                                    {:type           :send-reminder
                                     :person-id      person-id
                                     :event-key      event-key
                                     :event-label    (:label event)
                                     :event-date     (:date event)
                                     :reminder-group group})))))
                  state
                  (:assigned event)))
               state
               events)))
          state
          windows)))

(>defn compute-pending-corrections
       "Diff plan vs sent notifications and produce :send-correction actions.
   Records notification entries."
       [state today-str now-str]
       [::schema/state ::schema/date-str ::schema/iso-datetime => map?]
       (let [plan        (:schedule-plan state)
             db          (:schedule-db state)
             corrections (notifications/compute-corrections plan (:sent-notifications db) today-str)]
         (reduce
          (fn [state {:keys [person-id event-key action plan-entry]}]
            (-> state
                (update :schedule-db record-notification person-id event-key action now-str)
                (update :actions
                        conj
                        {:type            :send-correction
                         :person-id       person-id
                         :event-key       event-key
                         :correction-type action
                         :event-label     (:label plan-entry)
                         :event-date      (:date event-key)})))
          state
          corrections)))

;; =============================================================================
;; Query helpers
;; =============================================================================

(>defn get-token-for-person
       [state person-id]
       [::schema/state :string => any?]
       (some (fn [[tok pid]] (when (= pid person-id) tok))
             (get-in state [:schedule-db :sec-tokens])))
