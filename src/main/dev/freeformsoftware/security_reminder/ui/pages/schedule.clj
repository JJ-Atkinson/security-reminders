(ns dev.freeformsoftware.security-reminder.ui.pages.schedule
  (:require
   [dev.freeformsoftware.security-reminder.ui.pages :as ui.pages]
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.schedule.time-layer :as time-layer]
   [dev.freeformsoftware.security-reminder.server.route-utils :as route-utils]
   [cheshire.core :as json]
   [hiccup2.core :as h]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Schedule rendering
;; =============================================================================

(defn- event-card-member-action
  "Right side of event card for members: absence toggle."
  [request entry]
  (let [{:keys [event-key]} entry
        sec-token           (:sec-token request)
        current-person      (:person request)
        current-id          (:id current-person)
        is-absent?          (some #{current-id} (:absent entry))]
    [:form
     {:method    "POST"
      :action    (str "/" sec-token "/absences/toggle")
      :hx-post   (str "/" sec-token "/absences/toggle")
      :hx-target "#schedule-body"
      :hx-swap   "innerHTML"}
     (ui.pages/hidden-input "event-date" (:date event-key))
     (when (:template-id event-key)
       (ui.pages/hidden-input "template-id" (:template-id event-key)))
     (when (:one-off-id event-key)
       (ui.pages/hidden-input "one-off-id" (:one-off-id event-key)))
     [:button
      {:type  "submit"
       :class (if is-absent?
                "text-green-700 hover:text-green-900 font-bold"
                "text-red-700 hover:text-red-900")}
      (if is-absent? "I'm back" "I'm out")]]))

(defn- build-override-set
  "Build a set of [date template-id] pairs that have instance overrides."
  [engine]
  (let [db (engine/view-db engine)]
    (set (map (fn [o] [(:event-date o) (:event-template-id o)])
              (:instance-overrides db)))))

(defn- event-card
  "Render a single event as a card on the schedule page."
  [request entry people-list override-set]
  (let [{:keys [event-key assigned absent understaffed?]} entry
        current-person (:person request)
        current-id     (:id current-person)
        is-assigned?   (some #{current-id} assigned)
        is-absent?     (some #{current-id} absent)
        is-one-off?    (boolean (:one-off-id event-key))
        has-override?  (contains? override-set [(:date event-key) (:template-id event-key)])]
    (ui.pages/card-wrapper
     (str (cond
            understaffed? "bg-red-100"
            is-one-off?   "bg-blue-50"
            is-absent?    "bg-yellow-50"
            is-assigned?  "bg-green-50"
            :else         "bg-white")
          (when (or is-assigned? is-absent?) " my-event"))
     (ui.pages/event-card-body entry people-list {:people-required-override? has-override?})
     (event-card-member-action request entry))))

(defn schedule-card-list
  "Render the list of event cards (for HTMX partial swap)."
  [conf request]
  (let [{:keys [engine]} conf
        plan             (engine/view-plan engine)
        people           (engine/list-people engine)
        override-set     (build-override-set engine)]
    (for [entry plan]
      (event-card request entry people override-set))))

(defn- schedule-cards
  "Render the full schedule card layout."
  [conf request]
  [:div#schedule-body.flex.flex-col.gap-3
   (schedule-card-list conf request)])

(defn- schedule-content
  "Main schedule page content."
  [conf request]
  (let [person    (:person request)
        sec-token (:sec-token request)]
    [:div.p-4.flex.flex-col.gap-4
     [:div.flex.items-center.justify-between
      [:div
       [:h2.text-2xl.font-bold "Security Schedule"]
       [:p (str "Viewing as: " (:name person))]]
      (when (:admin? person)
        [:a
         {:href  (str "/" sec-token "/admin/users")
          :class (into ["text-sm"] ui.frag/button-classes)
          :title "Admin"}
         "Admin"])]
     [:div#schedule-wrapper.filter-mine
      [:label.flex.items-center.gap-2.mb-3.cursor-pointer
       [:input#my-events-toggle
        {:type    "checkbox"
         :checked true
         :class   ui.frag/checkbox-classes
         :_       ui.pages/toggle-filter-mine-script}]
       [:span.text-sm.font-medium "My events only"]]
      (schedule-cards conf request)]]))

(defn- schedule-page
  "Full HTML schedule page."
  [conf request]
  (ui.pages/html-response
   (ui.frag/html-body
    (assoc conf :sec-token (:sec-token request))
    (list
     ui.pages/filter-mine-style
     (ui.frag/background
      [:div.flex-1.overflow-y-auto
       (schedule-content conf request)])))))

(defn- schedule-partial
  "HTMX partial: just the event cards."
  [conf request]
  {:status  200
   :headers {"Content-Type" "text/html"
             "X-Robots-Tag" "noindex"}
   :body    (str (h/html (schedule-card-list conf request)))})

;; =============================================================================
;; Absence toggle handlers
;; =============================================================================

(defn- parse-event-key
  [params]
  (cond-> {:date (:event-date params)}
    (:template-id params) (assoc :template-id (:template-id params))
    (:one-off-id params)  (assoc :one-off-id (:one-off-id params))))

(defn- handle-absence-toggle
  [conf request]
  (let [params     (:params request)
        event-date (:event-date params)]
    (if-not (route-utils/valid-date? event-date)
      (route-utils/bad-request "Invalid event date")
      (let [env       (time-layer/scheduler-env (:time-layer conf))
            person    (:person request)
            event-key (parse-event-key params)]
        (engine/note-absence! env (:id person) event-key)
        (schedule-partial conf request)))))

(defn- handle-absence-toggle-redirect
  [conf request]
  (let [params     (:params request)
        event-date (:event-date params)]
    (if-not (route-utils/valid-date? event-date)
      (route-utils/bad-request "Invalid event date")
      (let [env       (time-layer/scheduler-env (:time-layer conf))
            person    (:person request)
            event-key (parse-event-key params)]
        (engine/note-absence! env (:id person) event-key)
        (resp/redirect (str "/" (:sec-token request) "/schedule"))))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn- manifest-response
  "Dynamic PWA manifest with per-user start_url."
  [request]
  {:status  200
   :headers {"Content-Type" "application/manifest+json"}
   :body    (json/generate-string
             {:name             "Security Reminder"
              :short_name       "Security"
              :start_url        (str "/" (:sec-token request) "/schedule")
              :scope            "/"
              :display          "standalone"
              :background_color "#fff7ed"
              :theme_color      "#fff7ed"
              :icons            [{:src "/icons/icon-192.png" :sizes "192x192" :type "image/png"}
                                 {:src "/icons/icon-512.png" :sizes "512x512" :type "image/png"}]})})

(defn routes
  [conf]
  {"GET /"                 (fn [request]
                             (resp/redirect (str "/" (:sec-token request) "/schedule")))
   "GET /schedule"         (fn [request] (schedule-page conf request))
   "GET /manifest.json"    (fn [request] (manifest-response request))
   "POST /absences/toggle" (fn [request] (handle-absence-toggle conf request))
   "GET /absences/toggle"  (fn [request] (handle-absence-toggle-redirect conf request))})
