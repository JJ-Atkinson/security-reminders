(ns dev.freeformsoftware.security-reminder.schedule.engine
  "Impure shell for schedule state management.
   Owns disk I/O, email sending, and token generation.
   All business logic lives in schedule.ops (pure).
   Callers use `with-state!->` to compose pure ops into transactions."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [dev.freeformsoftware.security-reminder.db.schema :as schema]
   [dev.freeformsoftware.security-reminder.patches.huff-malli] ;; patch huff 0.1.x for malli 0.19.x compat
   [dev.freeformsoftware.security-reminder.schedule.ops :as ops]
   [dev.freeformsoftware.security-reminder.schedule.reminders :as reminders]
   [integrant.core :as ig]
   [nextjournal.garden-email :as garden-email]
   [taoensso.telemere :as tel]
   [zprint.core :as zp])
  (:import
   [java.time LocalDate LocalDateTime]
   [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; File I/O helpers
;; =============================================================================

(defn- db-path
  ^String [db-folder]
  (str (fs/path db-folder "schedule-db.edn")))

(defn- plan-path
  ^String [db-folder]
  (str (fs/path db-folder "schedule-plan.edn")))

(defn- backup-path
  ^String [path]
  (str path ".bak"))

(defn- write-edn!
  "Atomically write EDN data to a file with zprint formatting and backup."
  [path data]
  (let [path-str  (str path)
        tmp-path  (str path-str ".tmp")
        formatted (zp/zprint-str data
                                 {:map   {:comma? false}
                                  :width 100})]
    (spit tmp-path formatted)
    (when (fs/exists? path-str)
      (fs/copy path-str (backup-path path-str) {:replace-existing true}))
    (fs/move tmp-path path-str {:replace-existing true})))

(defn- read-edn
  "Read and parse an EDN file. Returns nil if file doesn't exist."
  [path]
  (let [path-str (str path)]
    (when (fs/exists? path-str)
      (edn/read-string (slurp path-str)))))

(defn- migrate-time-labels
  "Convert any string time-labels to keywords in the loaded DB data (one-time fixup)."
  [db]
  (let [convert-tl (fn [m]
                     (if (string? (:time-label m))
                       (update m :time-label keyword)
                       m))]
    (-> db
        (update :event-templates #(mapv convert-tl %))
        (update :one-off-events #(mapv convert-tl %)))))

(defn- ensure-db!
  "Ensure the schedule-db.edn exists, copying from default if needed.
   Seeds :people from config if the DB doesn't have them yet."
  [db-folder people-seed]
  (let [db-file (db-path db-folder)]
    (when-not (fs/exists? db-file)
      (fs/create-dirs db-folder)
      (let [default (edn/read-string (slurp (io/resource "config/default-schedule-db.edn")))]
        (write-edn! db-file default)))
    (let [db (read-edn db-file)
          db (cond-> db
               (or (not (contains? db :people)) (empty? (:people db)))
               (assoc :people (vec people-seed))
               (not (contains? db :instance-overrides))
               (assoc :instance-overrides [])
               (not (contains? db :sent-notifications))
               (assoc :sent-notifications [])
               (not (contains? db :assignment-overrides))
               (assoc :assignment-overrides [])
               (contains? db :sent-reminders)
               (dissoc :sent-reminders))
          db (migrate-time-labels db)]
      (write-edn! db-file db)
      db)))

;; =============================================================================
;; Token generation
;; =============================================================================

(def ^:private base46-chars
  "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz")

(>defn generate-token
       "Generate a random base46 token (12 chars).
   If existing-tokens set is provided, retries on collision."
       ([]
        [=> :string]
        (generate-token nil))
       ([existing-tokens]
        [[:maybe set?] => :string]
        (let [rng (java.security.SecureRandom.)
              n   (count base46-chars)]
          (loop [attempts 0]
            (let [token (apply str (repeatedly 12 #(nth base46-chars (.nextInt rng n))))]
              (if (and existing-tokens (contains? existing-tokens token))
                (if (< attempts 10)
                  (recur (inc attempts))
                  (throw (ex-info "Failed to generate unique token after 10 attempts" {})))
                token))))))

(>defn now-str
       "Current datetime as ISO string."
       []
       [=> ::schema/iso-datetime]
       (.format (LocalDateTime/now) DateTimeFormatter/ISO_LOCAL_DATE_TIME))

;; =============================================================================
;; State I/O boundary
;; =============================================================================

(>defn fetch-state
       "Read db + plan from disk → state map. Includes config needed by pure ops."
       [{:keys [db-folder notify-at-days-before]}]
       [[:map [:db-folder :string]] => map?]
       {:schedule-db           (or (read-edn (db-path db-folder)) {})
        :schedule-plan         (or (read-edn (plan-path db-folder)) [])
        :notify-at-days-before (or notify-at-days-before [1])
        :actions               []})

(defn- send-email!
  "Send an email via garden-email. Returns the result map."
  [person email-msg]
  (garden-email/send-email!
   {:to      {:email (:email person) :name (:name person)}
    :from    {:email garden-email/my-email-address :name "Security Reminder"}
    :subject (:subject email-msg)
    :text    (:text email-msg)
    :html    (:html email-msg)}))

(defn- send-reminder-for-event
  "Send a reminder email for an event assignment. Returns updated state."
  [state base-url person action]
  (let [person-id      (:id person)
        token          (ops/get-token-for-person state person-id)
        link           (str base-url "/" token "/schedule")
        reminder-group (:reminder-group action)
        people-list    (get-in state [:schedule-db :people])
        email-msg      (reminders/format-reminder-email
                        person (:event-label action) (:event-date action)
                        link reminder-group
                        (:schedule-plan state) people-list)]
    (send-email! person email-msg)
    (tel/log! {:level :info
               :data  {:person (:name person) :event (:event-label action) :group reminder-group}}
              "Reminder email sent")
    state))

(defn- send-correction-for-event
  "Send a correction email (assigned/rescinded). Returns updated state."
  [state base-url person action]
  (let [person-id       (:id person)
        token           (ops/get-token-for-person state person-id)
        link            (str base-url "/" token "/schedule")
        correction-type (:correction-type action)
        people-list     (get-in state [:schedule-db :people])
        email-msg       (reminders/format-correction-email
                         correction-type person (:event-label action) (:event-date action)
                         link
                         (:schedule-plan state) people-list)]
    (send-email! person email-msg)
    (tel/log! {:level :info
               :data  {:person (:name person) :action correction-type :event (:event-label action)}}
              "Correction email sent")
    state))

(defn- send-welcome-for-person
  "Send a welcome email and record the notification. Returns updated state."
  [state base-url person]
  (let [person-id   (:id person)
        token       (ops/get-token-for-person state person-id)
        people-list (get-in state [:schedule-db :people])
        email-msg   (reminders/format-welcome-email
                     person base-url token
                     (:schedule-plan state) people-list)]
    (send-email! person email-msg)
    (tel/log! {:level :info :data {:person (:name person)}} "Welcome email sent")
    (update state :schedule-db ops/record-welcome-notification person-id (now-str))))

(>defn resolve-state!
       "Process accumulated actions (email sending) and write final state to disk.
   Returns nil."
       [{:keys [db-folder base-url on-assignment-change] :as _env} state]
       [map? map? => any?]
       (let [actions      (:actions state)
             people-by-id (into {} (map (juxt :id identity)) (get-in state [:schedule-db :people]))
             final-state
             (reduce
              (fn [state action]
                (try
                  (let [person-id (:person-id action)
                        person    (get people-by-id person-id)]
                    (case (:type action)
                      :send-reminder
                      (if-not person
                        (do (tel/log! {:level :warn :data {:person-id person-id}} "Skipping reminder: person not found")
                            state)
                        (send-reminder-for-event state base-url person action))

                      :send-correction
                      (if-not person
                        (do (tel/log! {:level :warn :data {:person-id person-id}} "Skipping correction: person not found")
                            state)
                        (send-correction-for-event state base-url person action))

                      :send-welcome
                      (if-not person
                        (do (tel/log! {:level :warn :data {:person-id person-id}} "Skipping welcome: person not found")
                            state)
                        (send-welcome-for-person state base-url person))

                 ;; Unknown action type — skip
                      (do (tel/log! {:level :warn :data {:action action}} "Unknown action type")
                          state)))
                  (catch Exception e
                    (tel/error! {:data {:action action} :msg "Failed to process action"} e)
                    state)))
              state
              actions)]
    ;; Validate and write
         (schema/validate-schedule-db! (:schedule-db final-state))
         (write-edn! (db-path db-folder) (:schedule-db final-state))
         (write-edn! (plan-path db-folder) (:schedule-plan final-state))
    ;; Trigger debounce if there were plan-affecting actions
         (when on-assignment-change
           (on-assignment-change))
         nil))

(defmacro with-state!->
  "Fetch state, thread through pure ops, resolve (email + write).
   All ops run under the engine lock for atomicity."
  [env & body]
  `(let [env# ~env]
     (locking (:lock env#)
       (->> (-> (fetch-state env#)
                ~@body)
            (resolve-state! env#)))))

;; =============================================================================
;; Query functions (thin reads, no locking needed)
;; =============================================================================

(>defn view-plan
       "Returns the materialized plan cache."
       [{:keys [db-folder]}]
       [[:map [:db-folder :string]] => any?]
       (or (read-edn (plan-path db-folder)) []))

(>defn list-people
       "Returns the current people vector from the DB."
       [{:keys [db-folder]}]
       [[:map [:db-folder :string]] => any?]
       (:people (read-edn (db-path db-folder))))

(>defn view-db
       "Returns the full schedule-db."
       [{:keys [db-folder]}]
       [[:map [:db-folder :string]] => any?]
       (read-edn (db-path db-folder)))

(>defn lookup-person-by-token
       "Returns person map if token is valid, nil otherwise."
       [{:keys [db-folder]} token]
       [[:map [:db-folder :string]] :string => any?]
       (let [db        (read-edn (db-path db-folder))
             person-id (get (:sec-tokens db) token)]
         (when person-id
           (some #(when (= (:id %) person-id) %) (:people db)))))

(>defn get-token-for-person
       "Look up the current token for a person-id."
       [{:keys [db-folder]} person-id]
       [[:map [:db-folder :string]] :string => any?]
       (let [db (read-edn (db-path db-folder))]
         (some (fn [[tok pid]] (when (= pid person-id) tok))
               (:sec-tokens db))))

(>defn list-sent-notifications
       "Returns all sent-notifications reverse-sorted by :sent-at (newest first)."
       [{:keys [db-folder]}]
       [[:map [:db-folder :string]] => vector?]
       (let [db (read-edn (db-path db-folder))]
         (vec (sort-by :sent-at #(compare %2 %1) (:sent-notifications db)))))

(>defn reminder-already-sent?
       "Check if a reminder was already sent for this person+event."
       [{:keys [db-folder]} person-id event-key]
       [[:map [:db-folder :string]] :string ::schema/event-key => any?]
       (let [db (read-edn (db-path db-folder))]
         (some (fn [r]
                 (and (= :reminder (:type r))
                      (= (:person-id r) person-id)
                      (= (:event-date r) (:date event-key))
                      (or (and (:template-id event-key)
                               (= (:event-template-id r) (:template-id event-key)))
                          (and (:one-off-id event-key)
                               (= (:one-off-event-id r) (:one-off-id event-key))))))
               (:sent-notifications db))))

;; =============================================================================
;; Mutation convenience functions (use with-state!-> internally)
;; These maintain backward compatibility for callers.
;; =============================================================================

(>defn note-absence!
       "Toggle absence for a person on an event."
       [env person-id event-key]
       [map? :string ::schema/event-key => any?]
       (with-state!-> env
         (ops/note-absence person-id event-key (:today-str env))))

(>defn add-person!
       "Add a new person to the DB. Generates a token for them."
       [env {:keys [name email admin?]}]
       [map? [:map [:name :string] [:email ::schema/email] [:admin? :boolean]] => :string]
       (let [id       (str "p-" (System/currentTimeMillis))
             existing (set (keys (:sec-tokens (read-edn (db-path (:db-folder env))))))
             token    (generate-token existing)]
         (with-state!-> env
           (ops/add-person {:id id :name name :email email :admin? (boolean admin?)} (:today-str env))
           (ops/rotate-token id token))
         id))

(>defn remove-person!
       "Remove a person from the DB."
       [env person-id]
       [map? :string => any?]
       (with-state!-> env
         (ops/remove-person person-id (:today-str env))))

(>defn add-one-off!
       "Add a one-off event."
       [env {:keys [label date time-label people-required]}]
       [map? [:map [:label :string] [:date ::schema/date-str] [:time-label ::schema/time-label]] => any?]
       (let [id (str "oo-" (System/currentTimeMillis))]
         (with-state!-> env
           (ops/add-one-off {:id              id
                             :label           label
                             :date            date
                             :time-label      time-label
                             :people-required (or people-required 2)}
                            (:today-str env)))))

(>defn remove-one-off!
       "Remove a one-off event by ID."
       [env event-id]
       [map? :string => any?]
       (with-state!-> env
         (ops/remove-one-off event-id (:today-str env))))

(>defn update-template!
       "Modify an event template."
       [env template-id updates]
       [map? :string map? => any?]
       (with-state!-> env
         (ops/update-template template-id updates (:today-str env))))

(>defn set-instance-override!
       "Upsert a per-instance people-required override."
       [env event-date template-id people-required]
       [map? ::schema/date-str :string :int => any?]
       (with-state!-> env
         (ops/set-instance-override event-date template-id people-required (:today-str env))))

(>defn remove-instance-override!
       "Remove a per-instance override."
       [env event-date template-id]
       [map? ::schema/date-str :string => any?]
       (with-state!-> env
         (ops/remove-instance-override event-date template-id (:today-str env))))

(>defn set-assignment-override!
       "Set a manual assignment override for a specific event instance."
       [env event-key assigned-ids]
       [map? ::schema/event-key sequential? => any?]
       (with-state!-> env
         (ops/set-assignment-override event-key assigned-ids (:today-str env))))

(>defn remove-assignment-override!
       "Remove a manual assignment override."
       [env event-key]
       [map? ::schema/event-key => any?]
       (with-state!-> env
         (ops/remove-assignment-override event-key (:today-str env))))

(>defn refresh-plan!
       "Called by daily cron to roll the 8-week window forward."
       [env]
       [map? => any?]
       (with-state!-> env
         (ops/refresh-plan (:today-str env))))

(>defn record-notification-sent!
       "Record a sent notification. Used by legacy callers."
       [env person-id event-key type]
       [map? :string ::schema/event-key keyword? => any?]
       (with-state!-> env
         (update
          :schedule-db
          (fn [db]
            (let [entry         (cond-> {:person-id  person-id
                                         :event-date (:date event-key)
                                         :type       type
                                         :sent-at    (now-str)}
                                  (:template-id event-key) (assoc :event-template-id (:template-id event-key))
                                  (:one-off-id event-key)  (assoc :one-off-event-id (:one-off-id event-key)))
                  notifications (conj (or (:sent-notifications db) []) entry)
                  capped        (vec (take-last 500 notifications))]
              (assoc db :sent-notifications capped))))))

(>defn rotate-token!
       "Generate a new sec-token for a person. Returns the new token."
       [env person-id]
       [map? :string => :string]
       (let [existing  (set (keys (:sec-tokens (read-edn (db-path (:db-folder env))))))
             new-token (generate-token existing)]
         (with-state!-> env
           (ops/rotate-token person-id new-token))
         new-token))

(>defn ensure-tokens!
       "Ensure every person has a sec-token."
       [{:keys [db-folder] :as env}]
       [[:map [:db-folder :string]] => any?]
       (let [db                  (read-edn (db-path db-folder))
             existing-person-ids (set (vals (:sec-tokens db)))
             missing             (remove #(existing-person-ids (:id %)) (:people db))
             existing            (set (keys (:sec-tokens db)))
             token-map           (into {} (map (fn [p] [(:id p) (generate-token existing)])) missing)]
         (when (seq token-map)
           (with-state!-> env
             (ops/ensure-tokens token-map)))))

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(defmethod ig/init-key ::engine
  [_ {:keys [db-folder people base-url notify-at-days-before]}]
  (tel/log! {:level :info :data {:db-folder db-folder}} "Initializing schedule engine")
  (let [engine {:db-folder             db-folder
                :lock                  (Object.)
                :base-url              base-url
                :notify-at-days-before (or notify-at-days-before [1])
                :today-str             (str (LocalDate/now))}]
    (ensure-db! db-folder people)
    (try
      (ensure-tokens! engine)
      (refresh-plan! engine)
      (assoc engine :healthy? true)
      (catch Exception e
        (tel/log! {:level :error :data {:error (ex-message e)}} "Engine init failed — unhealthy")
        (assoc engine :healthy? false :init-error (ex-message e))))))

(defmethod ig/halt-key! ::engine
  [_ _engine]
  (tel/log! :info "Shutting down schedule engine"))

(comment
  (def e
    (ig/init-key ::engine
                 {:db-folder "/tmp/sr-test"
                  :people    [{:id "p1" :name "Alice" :email "alice@test.local" :admin? false}
                              {:id "p2" :name "Bob" :email "bob@test.local" :admin? false}
                              {:id "p3" :name "Carol" :email "carol@test.local" :admin? true}]
                  :base-url  "http://localhost:3000"
                  :today-str "2026-03-25"}))
  (view-plan e)
  (list-people e)
  (note-absence! e "p1" {:date "2026-03-25" :template-id "et-1"})
  (view-plan e)
  (set-assignment-override! e {:date "2026-04-01" :template-id "et-1"} ["p1" "p5"])
  (view-plan e)
  (ig/halt-key! ::engine e))
