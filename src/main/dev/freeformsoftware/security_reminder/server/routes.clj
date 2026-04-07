(ns dev.freeformsoftware.security-reminder.server.routes
  (:require
   [dev.freeformsoftware.security-reminder.server.route-utils :as server.route-utils]
   [dev.freeformsoftware.security-reminder.server.websocket :as websocket]
   [dev.freeformsoftware.security-reminder.server.auth-middleware :as auth-middleware]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.schedule.reminders :as reminders]
   [dev.freeformsoftware.security-reminder.logging.log-buffer :as log-buffer]
   [dev.freeformsoftware.security-reminder.ui.pages.schedule :as pages.schedule]
   [dev.freeformsoftware.security-reminder.ui.pages.admin.users :as pages.admin.users]
   [dev.freeformsoftware.security-reminder.ui.pages.admin.events :as pages.admin.events]
   [dev.freeformsoftware.security-reminder.ui.pages.admin.info :as pages.admin.info]
   [dev.freeformsoftware.security-reminder.ui.pages.admin.settings :as pages.admin.settings]
   [dev.freeformsoftware.security-reminder.push.routes :as push.routes]
   [hiccup2.core :as h]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; No-auth routes (outside token prefix)
;; =============================================================================

(defn noauth-routes
  [conf]
  {"HEAD /"           (fn [_] {:status 200 :body ""})
   "GET /health"      (fn [_]
                        (let [lb      (:log-buffer (:logging conf))
                              engine  (:engine conf)
                              healthy (:healthy? engine true)
                              status  (cond
                                        (not healthy) "red"
                                        (and lb (log-buffer/errors-in-last-24h? lb)) "yellow"
                                        :else "ok")]
                          {:status  (if healthy 200 503)
                           :headers {"Content-Type" "application/json"}
                           :body    (str "{\"status\":\"" status "\"}")}))
   "GET /favicon.ico" (constantly {:status 404 :body nil})})

;; =============================================================================
;; Dev routes
;; =============================================================================

(defn- dev-login-page
  "Renders a table of all users with links to their schedule pages."
  [engine]
  (let [people (engine/list-people engine)]
    {:status  200
     :headers {"Content-Type" "text/html" "X-Robots-Tag" "noindex"}
     :body    (str
               (h/html
                (h/raw "<!DOCTYPE html>\n")
                [:html
                 [:head
                  [:meta {:charset "UTF-8"}]
                  [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
                  [:link {:rel "stylesheet" :href "/css/output.css"}]
                  [:title "Dev Login"]]
                 [:body.bg-orange-50.p-8
                  [:div.max-w-lg.mx-auto
                   [:h1.text-2xl.font-bold.mb-4 "Dev Login"]
                   [:table.w-full.border-collapse.bg-white.rounded.shadow
                    [:thead
                     [:tr.border-b
                      [:th.text-left.p-3 "Name"]
                      [:th.text-left.p-3 "Role"]
                      [:th.text-left.p-3 "Link"]]]
                    [:tbody
                     (for [person people
                           :let   [token (engine/get-token-for-person engine (:id person))]]
                       [:tr.border-b.hover:bg-orange-100
                        [:td.p-3 (:name person)]
                        [:td.p-3 (if (:admin? person) "Admin" "Member")]
                        [:td.p-3
                         (if token
                           [:a.text-blue-600.underline
                            {:href (str "/" token "/schedule")}
                            "Schedule"]
                           [:span.text-gray-400 "No token"])]])]]]]]))}))

(defn- preview-email
  "Render a sample email using real DB data. Returns HTML response."
  [engine email-type]
  (let [people   (engine/list-people engine)
        person   (first people)
        plan     (engine/view-plan engine)
        token    (engine/get-token-for-person engine (:id person))
        base-url (:base-url engine)
        link     (str base-url "/" token "/schedule")
        ;; Pick the first plan entry for context
        entry    (first plan)
        email    (case email-type
                   :reminder
                   (reminders/format-reminder-email
                    person
                    (or (:label entry) "Wed Evening")
                    (or (:date entry) "2026-04-01")
                    link
                    8
                    plan
                    people)
                   :correction
                   (reminders/format-correction-email
                    :assigned
                    person
                    (or (:label entry) "Wed Evening")
                    (or (:date entry) "2026-04-01")
                    link
                    plan
                    people))]
    {:status  200
     :headers {"Content-Type" "text/html" "X-Robots-Tag" "noindex"}
     :body    (:html email)}))

(defn- preview-welcome-email
  "Render a sample welcome email using real DB data."
  [engine]
  (let [people   (engine/list-people engine)
        person   (first people)
        plan     (engine/view-plan engine)
        token    (engine/get-token-for-person engine (:id person))
        base-url (:base-url engine)
        email    (reminders/format-welcome-email person base-url token plan people)]
    {:status  200
     :headers {"Content-Type" "text/html" "X-Robots-Tag" "noindex"}
     :body    (:html email)}))

(defn dev-routes
  [{:keys [engine]}]
  {"GET /dev/reload-ws"        websocket/reload-handler
   "GET /dev/login"            (fn [_] (dev-login-page engine))
   "GET /dev/reminder-email"   (fn [_] (preview-email engine :reminder))
   "GET /dev/correction-email" (fn [_] (preview-email engine :correction))
   "GET /dev/welcome-email"    (fn [_] (preview-welcome-email engine))})

;; =============================================================================
;; Route assembly
;; =============================================================================

(defn create-routes
  [{:keys [env] :as conf}]
  (let [authed-routes
        (server.route-utils/wrap-routes
         (partial auth-middleware/wrap-require-person conf)
         (server.route-utils/merge-routes
          (pages.schedule/routes conf)
          (pages.admin.settings/routes conf)
          (push.routes/routes conf)
          (server.route-utils/wrap-routes
           (partial auth-middleware/wrap-admin-auth conf)
           (server.route-utils/merge-routes
            (pages.admin.users/routes conf)
            (pages.admin.events/routes conf)
            (pages.admin.info/routes conf)))))

        routes
        (server.route-utils/merge-routes
         authed-routes
         (noauth-routes conf)
         (when (= env :dev) (dev-routes conf)))]
    routes))

^:clj-reload/keep
(defonce !create-routes (atom nil))
(reset! !create-routes create-routes)
