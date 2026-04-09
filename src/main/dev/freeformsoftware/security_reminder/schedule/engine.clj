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
   [dev.freeformsoftware.security-reminder.push.send :as push.send]
   [dev.freeformsoftware.security-reminder.schedule.ops :as ops]
   [dev.freeformsoftware.security-reminder.schedule.projection :as proj]
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
               (not (contains? db :push-subscriptions))
               (assoc :push-subscriptions [])
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
       [{:keys [db-folder notify-at-days-before today-str]}]
       [[:map [:db-folder :string]] => map?]
       {:schedule-db           (or (read-edn (db-path db-folder)) {})
        :schedule-plan         (or (read-edn (plan-path db-folder)) [])
        :notify-at-days-before (or notify-at-days-before [1])
        :today-str             today-str
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

(defn- try-send-push
  "Attempt to send a push notification to a person. Returns updated state.
   Swallows errors so push failures don't block email delivery."
  [state env person-id payload-map]
  (try
    (let [push-conf (select-keys env [:vapid-public-key :vapid-private-key :vapid-subject])]
      (if (and (:vapid-public-key push-conf)
               (:vapid-private-key push-conf)
               (seq (:vapid-public-key push-conf))
               (seq (:vapid-private-key push-conf)))
        (push.send/send-push-to-person! state push-conf person-id payload-map)
        state))
    (catch Exception e
      (tel/log! {:level :warn :data {:person-id person-id :error (ex-message e)}}
                "Push notification failed")
      state)))

(defn- send-reminder-for-event
  "Send a reminder email + push notification for an event assignment. Returns updated state."
  [state env person action]
  (let [base-url       (:base-url env)
        person-id      (:id person)
        token          (ops/get-token-for-person state person-id)
        link           (str base-url "/" token "/schedule")
        reminder-group (:reminder-group action)
        people-list    (get-in state [:schedule-db :people])
        send-email?    (get person :notifications/send-via-email? true)
        send-push?     (get person :notifications/send-via-push? true)]
    (when send-email?
      (let [email-msg (reminders/format-reminder-email
                       person
                       (:event-label action)
                       (:event-date action)
                       link
                       reminder-group
                       (:schedule-plan state)
                       people-list)]
        (send-email! person email-msg)
        (tel/log! {:level :info
                   :data  {:person (:name person) :event (:event-label action) :group reminder-group}}
                  "Reminder email sent")))
    (cond-> state
      send-push? (try-send-push env
                                person-id
                                {:title "Security Reminder"
                                 :body  (str "You're assigned to " (:event-label action)
                                             " on "                (proj/display-date (:event-date action)))
                                 :icon  "/icons/icon-192.png"
                                 :url   link}))))

(defn- send-correction-for-event
  "Send a correction email + push notification (assigned/rescinded). Returns updated state."
  [state env person action]
  (let [base-url        (:base-url env)
        person-id       (:id person)
        token           (ops/get-token-for-person state person-id)
        link            (str base-url "/" token "/schedule")
        correction-type (:correction-type action)
        people-list     (get-in state [:schedule-db :people])
        send-email?     (get person :notifications/send-via-email? true)
        send-push?      (get person :notifications/send-via-push? true)]
    (when send-email?
      (let [email-msg (reminders/format-correction-email
                       correction-type
                       person
                       (:event-label action)
                       (:event-date action)
                       link
                       (:schedule-plan state)
                       people-list)]
        (send-email! person email-msg)
        (tel/log! {:level :info
                   :data  {:person (:name person) :action correction-type :event (:event-label action)}}
                  "Correction email sent")))
    (cond-> state
      send-push? (try-send-push
                  env
                  person-id
                  {:title "Schedule Update"
                   :body  (str (if (= correction-type :assigned) "You've been assigned to " "No longer assigned to ")
                               (:event-label action)
                               " on "
                               (proj/display-date (:event-date action)))
                   :icon  "/icons/icon-192.png"
                   :url   link}))))

(defn- send-welcome-for-person
  "Send a welcome email + push notification and record the notification. Returns updated state."
  [state env person]
  (let [base-url    (:base-url env)
        person-id   (:id person)
        token       (ops/get-token-for-person state person-id)
        people-list (get-in state [:schedule-db :people])
        send-email? (get person :notifications/send-via-email? true)
        send-push?  (get person :notifications/send-via-push? true)]
    (when send-email?
      (let [email-msg (reminders/format-welcome-email
                       person
                       base-url
                       token
                       (:schedule-plan state)
                       people-list)]
        (send-email! person email-msg)
        (tel/log! {:level :info :data {:person (:name person)}} "Welcome email sent")))
    (cond-> state
      true       (update :schedule-db ops/record-welcome-notification person-id (now-str))
      send-push? (try-send-push env
                                person-id
                                {:title "Welcome"
                                 :body  "You've been added to the security rotation"
                                 :icon  "/icons/icon-192.png"
                                 :url   (str base-url "/" token "/schedule")}))))

(>defn resolve-state!
       "Process accumulated actions (email sending) and write final state to disk.
   Returns nil."
       [{:keys [db-folder on-assignment-change] :as env} state]
       [map? map? => any?]
       (let [actions (:actions state)
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
                        (do
                          (tel/log! {:level :warn :data {:person-id person-id}} "Skipping reminder: person not found")
                          state)
                        (send-reminder-for-event state env person action))

                      :send-correction
                      (if-not person
                        (do
                          (tel/log! {:level :warn :data {:person-id person-id}} "Skipping correction: person not found")
                          state)
                        (send-correction-for-event state env person action))

                      :send-welcome
                      (if-not person
                        (do
                          (tel/log! {:level :warn :data {:person-id person-id}} "Skipping welcome: person not found")
                          state)
                        (send-welcome-for-person state env person))

                 ;; Unknown action type — skip
                      (do
                        (tel/log! {:level :warn :data {:action action}} "Unknown action type")
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
         (some
          (fn [[tok pid]] (when (= pid person-id) tok))
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
         (some
          (fn [r]
            (and
             (= :reminder (:type r))
             (= (:person-id r) person-id)
             (= (:event-date r) (:date event-key))
             (or
              (and
               (:template-id event-key)
               (= (:event-template-id r) (:template-id event-key)))
              (and
               (:one-off-id event-key)
               (= (:one-off-event-id r) (:one-off-id event-key))))))
          (:sent-notifications db))))

(>defn get-push-subscriptions-for-person
       "Returns push subscriptions for a person-id."
       [{:keys [db-folder]} person-id]
       [[:map [:db-folder :string]] :string => any?]
       (let [db (read-edn (db-path db-folder))]
         (vec
          (filter
           #(= (:person-id %) person-id)
           (:push-subscriptions db)))))

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(defmethod ig/init-key ::engine
  [_
   {:keys [db-folder db-path people base-url notify-at-days-before
           vapid-public-key vapid-private-key vapid-subject]}]
  (let [db-folder (or db-folder (apply fs/path (remove nil? db-path)))
        _ (tel/log! {:level :info :data {:db-folder db-folder}} "Initializing schedule engine")
        engine {:db-folder             db-folder
                :lock                  (Object.)
                :base-url              base-url
                :notify-at-days-before (or notify-at-days-before [1])
                :today-str             (str (LocalDate/now))
                :vapid-public-key      vapid-public-key
                :vapid-private-key     vapid-private-key
                :vapid-subject         (or vapid-subject "mailto:noreply@example.com")}]
    (ensure-db! db-folder people)
    (try
      (let [token-map (into {} (map (fn [p] [(:id p) (generate-token)])) people)]
        (with-state!-> engine
          (ops/ensure-tokens token-map)
          (ops/refresh-plan)))
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
  (with-state!-> e
    (ops/note-absence "p1" {:date "2026-03-25" :template-id "et-1"}))
  (view-plan e)
  (with-state!-> e
    (ops/set-assignment-override {:date "2026-04-01" :template-id "et-1"} ["p1" "p5"]))
  (view-plan e)
  (ig/halt-key! ::engine e))
