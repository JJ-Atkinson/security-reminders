(ns dev.freeformsoftware.security-reminder.server.routes
  (:require
   [clojure.string :as str]
   [dev.freeformsoftware.security-reminder.server.route-utils :as server.route-utils]
   [dev.freeformsoftware.security-reminder.server.websocket :as websocket]
   [dev.freeformsoftware.security-reminder.server.auth-middleware :as auth-middleware]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.schedule.time-layer :as time-layer]
   [dev.freeformsoftware.security-reminder.logging.log-buffer :as log-buffer]
   [dev.freeformsoftware.security-reminder.ui.pages :as ui.pages]
   [ring.util.response :as resp])
  (:import
    [java.time LocalDate]
    [java.time.format DateTimeParseException]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Input validation helpers
;; =============================================================================

(defn- valid-date? [s]
  (when (string? s)
    (try (LocalDate/parse s) true
         (catch DateTimeParseException _ false))))

(defn- valid-phone? [s]
  (and (string? s)
       (re-matches #"\+[0-9]{7,15}" s)))

(defn- valid-name? [s]
  (and (string? s) (not (str/blank? s))))

(defn- valid-people-required? [n]
  (and (int? n) (<= 1 n 50)))

(def ^:private valid-time-labels #{:morning :afternoon :evening})

(defn- bad-request [msg]
  {:status 400 :headers {"Content-Type" "text/plain"} :body msg})

;; =============================================================================
;; No-auth routes (outside token prefix)
;; =============================================================================

(defn noauth-routes
  [conf]
  {"HEAD /"         (fn [_] {:status 200 :body ""})
   "GET /health"    (fn [_]
                      (let [lb     (:log-buffer (:logging conf))
                            status (if (and lb (log-buffer/errors-in-last-24h? lb))
                                     "yellow" "ok")]
                        {:status  200
                         :headers {"Content-Type" "application/json"}
                         :body    (str "{\"status\":\"" status "\"}")}))
   "GET /favicon.ico" (constantly {:status 404 :body nil})})

;; =============================================================================
;; Member routes (token resolved by middleware, :person on request)
;; =============================================================================

(defn member-routes
  [{:keys [time-layer] :as conf}]
  {"GET /"               (fn [request]
                           (resp/redirect (str "/" (:sec-token request) "/schedule")))
   "GET /schedule"       (fn [request]
                           (ui.pages/schedule-page conf request))
   "POST /absences/toggle" (fn [request]
                              (let [params (:params request)
                                    event-date (:event-date params)]
                                (if-not (valid-date? event-date)
                                  (bad-request "Invalid event date")
                                  (let [env (time-layer/scheduler-env time-layer)
                                        person (:person request)
                                        event-key (cond-> {:date event-date}
                                                    (:template-id params)
                                                    (assoc :template-id (:template-id params))
                                                    (:one-off-id params)
                                                    (assoc :one-off-id (:one-off-id params)))]
                                    (engine/note-absence! env (:id person) event-key)
                                    (ui.pages/schedule-partial conf request)))))})

;; =============================================================================
;; Admin routes
;; =============================================================================

(defn admin-routes
  [{:keys [time-layer] :as conf}]
  {"GET /admin/logs"           (fn [request]
                                  (ui.pages/admin-logs-page conf request))
   "GET /admin/users"          (fn [request]
                                  (ui.pages/admin-users-page conf request))
   "GET /admin/events"         (fn [request]
                                  (ui.pages/admin-events-page conf request))
   "GET /admin/messages"       (fn [request]
                                  (ui.pages/admin-messages-page conf request))
   "POST /admin/users"         (fn [request]
                                  (let [params (:params request)
                                        pname (:name params)
                                        phone (:phone params)
                                        admin? (= "true" (:admin params))]
                                    (cond
                                      (not (valid-name? pname))
                                      (bad-request "Name is required")
                                      (not (valid-phone? phone))
                                      (bad-request "Phone must be E.164 format (e.g. +15551234567)")
                                      :else
                                      (let [env (time-layer/scheduler-env time-layer)]
                                        (engine/add-person! env {:name (str/trim pname) :phone phone :admin? admin?})
                                        (resp/redirect (str "/" (:sec-token request) "/admin/users"))))))
   "POST /admin/users/delete"  (fn [request]
                                  (let [person-id (:person-id (:params request))]
                                    (if (str/blank? person-id)
                                      (bad-request "Person ID is required")
                                      (let [env (time-layer/scheduler-env time-layer)]
                                        (engine/remove-person! env person-id)
                                        (resp/redirect (str "/" (:sec-token request) "/admin/users"))))))
   "POST /admin/events"        (fn [request]
                                  (let [params (:params request)
                                        label (:label params)
                                        date (:date params)
                                        tl (:time-label params)
                                        pr (some-> (:people-required params) parse-long)
                                        tl-kw (keyword tl)]
                                      (cond
                                        (not (valid-name? label))
                                        (bad-request "Label is required")
                                        (not (valid-date? date))
                                        (bad-request "Invalid date format (expected YYYY-MM-DD)")
                                        (not (contains? valid-time-labels tl-kw))
                                        (bad-request "Time label must be morning, afternoon, or evening")
                                        (not (valid-people-required? pr))
                                        (bad-request "People required must be between 1 and 50")
                                        :else
                                        (let [env (time-layer/scheduler-env time-layer)]
                                          (engine/add-one-off! env {:label (str/trim label) :date date
                                                                    :time-label tl-kw :people-required pr})
                                          (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))
   "POST /admin/events/delete" (fn [request]
                                  (let [event-id (:event-id (:params request))]
                                    (if (str/blank? event-id)
                                      (bad-request "Event ID is required")
                                      (let [env (time-layer/scheduler-env time-layer)]
                                        (engine/remove-one-off! env event-id)
                                        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))
   "POST /admin/templates"     (fn [request]
                                  (let [params (:params request)
                                        template-id (:template-id params)
                                        pr (some-> (:people-required params) parse-long)]
                                    (cond
                                      (str/blank? template-id)
                                      (bad-request "Template ID is required")
                                      (not (valid-people-required? pr))
                                      (bad-request "People required must be between 1 and 50")
                                      :else
                                      (let [env (time-layer/scheduler-env time-layer)]
                                        (engine/update-template! env template-id {:people-required pr})
                                        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))
   "POST /admin/overrides"     (fn [request]
                                  (let [params (:params request)
                                        event-date (:event-date params)
                                        template-id (:template-id params)
                                        pr (some-> (:people-required params) parse-long)]
                                    (cond
                                      (not (valid-date? event-date))
                                      (bad-request "Invalid event date")
                                      (str/blank? template-id)
                                      (bad-request "Template ID is required")
                                      (not (valid-people-required? pr))
                                      (bad-request "People required must be between 1 and 50")
                                      :else
                                      (let [env (time-layer/scheduler-env time-layer)]
                                        (engine/set-instance-override! env event-date template-id pr)
                                        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))
   "POST /admin/overrides/delete" (fn [request]
                                     (let [params (:params request)
                                           event-date (:event-date params)
                                           template-id (:template-id params)]
                                       (cond
                                         (not (valid-date? event-date))
                                         (bad-request "Invalid event date")
                                         (str/blank? template-id)
                                         (bad-request "Template ID is required")
                                         :else
                                         (let [env (time-layer/scheduler-env time-layer)]
                                           (engine/remove-instance-override! env event-date template-id)
                                           (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))
   "GET /admin/assignments"        (fn [request]
                                     (let [event-date (:event-date (:params request))]
                                       (if-not (valid-date? event-date)
                                         (bad-request "Invalid event date")
                                         (ui.pages/admin-assignment-page conf request))))
   "POST /admin/assignments"       (fn [request]
                                     (let [params (:params request)
                                           event-date (:event-date params)
                                           template-id (:template-id params)
                                           one-off-id (:one-off-id params)
                                           raw-assigned (:assigned params)
                                           all-assigned (cond
                                                          (nil? raw-assigned) []
                                                          (string? raw-assigned) [raw-assigned]
                                                          :else (vec raw-assigned))
                                           assigned (vec (remove str/blank? all-assigned))
                                           event-key (cond-> {:date event-date}
                                                       (not (str/blank? template-id))
                                                       (assoc :template-id template-id)
                                                       (not (str/blank? one-off-id))
                                                       (assoc :one-off-id one-off-id))
                                           render-error (fn [msg]
                                                          {:status 400
                                                           :headers {"Content-Type" "text/html"
                                                                     "X-Robots-Tag" "noindex"}
                                                           :body (:body (ui.pages/admin-assignment-page conf request :error msg))})]
                                       (cond
                                         (not (valid-date? event-date))
                                         (bad-request "Invalid event date")
                                         (and (str/blank? template-id) (str/blank? one-off-id))
                                         (bad-request "Template ID or One-Off ID is required")
                                         (empty? assigned)
                                         (render-error "At least one person must be assigned.")
                                         (not= (count assigned) (count (distinct assigned)))
                                         (render-error "Each person can only be assigned once.")
                                         :else
                                         (let [env (time-layer/scheduler-env time-layer)]
                                           (engine/set-assignment-override! env event-key assigned)
                                           (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))
   "POST /admin/assignments/delete" (fn [request]
                                      (let [params (:params request)
                                            event-date (:event-date params)
                                            template-id (:template-id params)
                                            one-off-id (:one-off-id params)
                                            event-key (cond-> {:date event-date}
                                                        (not (str/blank? template-id))
                                                        (assoc :template-id template-id)
                                                        (not (str/blank? one-off-id))
                                                        (assoc :one-off-id one-off-id))]
                                        (cond
                                          (not (valid-date? event-date))
                                          (bad-request "Invalid event date")
                                          (and (str/blank? template-id) (str/blank? one-off-id))
                                          (bad-request "Template ID or One-Off ID is required")
                                          :else
                                          (let [env (time-layer/scheduler-env time-layer)]
                                            (engine/remove-assignment-override! env event-key)
                                            (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))})

;; =============================================================================
;; Dev routes
;; =============================================================================

(defn dev-routes
  [{:keys [engine]}]
  {"GET /dev/reload-ws" websocket/reload-handler
   "GET /dev/login"     (fn [_]
                          ;; Find the first person's token and redirect
                          (let [first-person (first (engine/list-people engine))
                                token (engine/get-token-for-person engine (:id first-person))]
                            (if token
                              (resp/redirect (str "/" token "/schedule"))
                              (resp/redirect "/"))))
   "GET /dev/logout"    (fn [_]
                          (resp/redirect "/"))})

;; =============================================================================
;; Route assembly
;; =============================================================================

(defn create-routes
  [{:keys [env] :as conf}]
  (let [authed-routes
        (server.route-utils/wrap-routes
         (partial auth-middleware/wrap-require-person conf)
         (server.route-utils/merge-routes
          (member-routes conf)
          (server.route-utils/wrap-routes
           (partial auth-middleware/wrap-admin-auth conf)
           (admin-routes conf))))

        routes
        (server.route-utils/merge-routes
         authed-routes
         (noauth-routes conf)
         (when (= env :dev) (dev-routes conf)))]
    routes))

^:clj-reload/keep
(defonce !create-routes (atom nil))
(reset! !create-routes create-routes)
