(ns dev.freeformsoftware.security-reminder.ui.pages.admin.events
  (:require
   [clojure.string :as str]
   [dev.freeformsoftware.security-reminder.ui.pages :as ui.pages]
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.schedule.projection :as proj]
   [dev.freeformsoftware.security-reminder.schedule.time-layer :as time-layer]
   [dev.freeformsoftware.security-reminder.server.route-utils :as route-utils]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Rendering helpers
;; =============================================================================

(defn- staff-override-buttons
  "Render +/- staff buttons and optional Reset for a recurring event."
  [sec-token event-key people-required has-override?]
  (let [template-id (:template-id event-key)]
    [:div.flex.flex-wrap.items-center.gap-1
     (when (> people-required 1)
       [:form
        {:method "POST"
         :action (str "/" sec-token "/admin/overrides")}
        (ui.pages/hidden-input "event-date" (:date event-key))
        (ui.pages/hidden-input "template-id" template-id)
        (ui.pages/hidden-input "people-required" (str (dec people-required)))
        [:button
         {:type  "submit"
          :class (into ui.frag/small-button-classes
                       ["bg-orange-100" "border-orange-300" "hover:bg-orange-200" "hover:border-orange-400"])}
         "- Staff"]])
     [:form
      {:method "POST"
       :action (str "/" sec-token "/admin/overrides")}
      (ui.pages/hidden-input "event-date" (:date event-key))
      (ui.pages/hidden-input "template-id" template-id)
      (ui.pages/hidden-input "people-required" (str (inc people-required)))
      [:button
       {:type  "submit"
        :class (into ui.frag/small-button-classes
                     ["hover:border-teal-800" "hover:bg-teal-200"])}
       "+ Staff"]]
     (when has-override?
       [:form
        {:method "POST"
         :action (str "/" sec-token "/admin/overrides/delete")}
        (ui.pages/hidden-input "event-date" (:date event-key))
        (ui.pages/hidden-input "template-id" template-id)
        [:button
         {:type  "submit"
          :class (into ui.frag/small-button-classes
                       ["hover:border-teal-800" "hover:bg-teal-200"])}
         "Reset"]])]))

(defn- admin-event-card
  "Render an event card on the admin events page with override/delete controls."
  [request entry people-list override-set assignment-override-set]
  (let [{:keys [event-key people-required]} entry
        sec-token                           (:sec-token request)
        is-one-off?                         (boolean (:one-off-id event-key))
        template-id                         (:template-id event-key)
        has-override?                       (contains? override-set [(:date event-key) template-id])
        has-assignment-override?            (contains? assignment-override-set
                                                       [(:date event-key) template-id (:one-off-id event-key)])
        assignment-link                     (str "/"
                                                 sec-token
                                                 "/admin/assignments?event-date="
                                                 (:date event-key)
                                                 (if is-one-off?
                                                   (str "&one-off-id=" (:one-off-id event-key))
                                                   (str "&template-id=" template-id)))]
    (ui.pages/card-wrapper
     (if is-one-off? "bg-blue-50" "bg-white")
     (ui.pages/event-card-body entry
                               people-list
                               {:people-required-override? has-override?
                                :assignment-override?      has-assignment-override?})
     [:div.flex.items-center.gap-2
      [:a
       {:href  assignment-link
        :class (into ui.frag/small-button-classes
                     ["bg-indigo-100" "border-indigo-300" "hover:bg-indigo-200" "hover:border-indigo-400"])}
       "Edit Users"]
      (if is-one-off?
        [:form
         {:method   "POST"
          :action   (str "/" sec-token "/admin/events/delete")
          :onsubmit "return confirm('Remove this event?')"}
         (ui.pages/hidden-input "event-id" (:one-off-id event-key))
         [:button {:type "submit" :class (into ui.frag/small-button-classes ui.frag/delete-button-classes)} "Delete"]]
        (staff-override-buttons sec-token event-key people-required has-override?))])))

;; =============================================================================
;; Events page
;; =============================================================================

(defn- admin-events-page
  "Admin page for managing events."
  [{:keys [engine] :as conf} request]
  (let [db                      (engine/view-db engine)
        templates               (:event-templates db)
        plan                    (engine/view-plan engine)
        people                  (:people db)
        override-set            (set (map (fn [o] [(:event-date o) (:event-template-id o)])
                                          (:instance-overrides db)))
        assignment-override-set (ui.pages/build-assignment-override-set db)
        sec-token               (:sec-token request)
        day-name                {1 "Mon" 2 "Tue" 3 "Wed" 4 "Thu" 5 "Fri" 6 "Sat" 7 "Sun"}]
    (ui.pages/admin-page-shell conf
                               request
                               :events
                               [:div.p-4.flex.flex-col.gap-6
                                ;; Recurring templates (read-only)
                                [:details.border-t.border-gray-300.pt-4
                                 {:_ ui.frag/accordion-exclusive-script}
                                 [:summary.cursor-pointer [:h2.text-lg.font-bold.inline "Recurring Templates"]]
                                 [:div.flex.flex-col.gap-2.mt-2
                                  (for [t templates]
                                    [:div.rounded.border.border-gray-200.bg-white.p-3
                                     [:div.flex.gap-3
                                      [:span.font-bold (:label t)]
                                      [:span.text-gray-500 (str (get day-name (:day-of-week t) "?"))]
                                      [:span.text-gray-500 (str (:people-required t) " people")]]])]]

                                ;; Add one-off form
                                [:details.border-t.border-gray-300.pt-4
                                 {:_ ui.frag/accordion-exclusive-script}
                                 [:summary.cursor-pointer [:h2.text-lg.font-bold.inline "Add One-Off Event"]]
                                 [:form
                                  {:method "POST"
                                   :action (str "/" sec-token "/admin/events")
                                   :class  "flex flex-col gap-2 mt-2"}
                                  [:div
                                   [:label.text-sm.font-semibold {:for "label"} "Label"]
                                   [:input
                                    {:type        "text"
                                     :name        "label"
                                     :id          "label"
                                     :placeholder "e.g. Special Duty"
                                     :required    true
                                     :class       ui.frag/input-classes}]]
                                  [:div
                                   [:label.text-sm.font-semibold {:for "date"} "Date"]
                                   [:input
                                    {:type     "date"
                                     :name     "date"
                                     :id       "date"
                                     :required true
                                     :value    (str (java.time.LocalDate/now))
                                     :class    ui.frag/input-classes}]]
                                  [:div
                                   [:label.text-sm.font-semibold {:for "time-label"} "Time"]
                                   [:select {:name "time-label" :id "time-label" :class ui.frag/input-classes}
                                    [:option {:value "morning"} "Morning"]
                                    [:option {:value "afternoon"} "Afternoon"]
                                    [:option {:value "evening" :selected true} "Evening"]]]
                                  [:div
                                   [:label.text-sm.font-semibold {:for "people-required"} "People required"]
                                   [:input
                                    {:type  "number"
                                     :name  "people-required"
                                     :id    "people-required"
                                     :value "2"
                                     :min   "1"
                                     :class ui.frag/input-classes}]]
                                  [:button
                                   {:type  "submit"
                                    :class ["font-bold" "border-2" "py-1" "px-2"
                                            "bg-teal-600" "text-white" "border-teal-600"
                                            "hover:bg-teal-700" "hover:border-teal-700"]}
                                   "Add Event"]]]

                                ;; Projected schedule with admin controls
                                [:details.border-t.border-gray-300.pt-4
                                 {:open true
                                  :_    ui.frag/accordion-exclusive-script}
                                 [:summary.cursor-pointer [:h2.text-lg.font-bold.inline "Projected Schedule"]]
                                 [:p.text-sm.text-gray-500.mb-2.mt-2
                                  "Edit people count per instance. One-off events shown in blue."]
                                 [:div.flex.flex-col.gap-3
                                  (for [entry plan]
                                    (admin-event-card request entry people override-set assignment-override-set))]]])))

;; =============================================================================
;; Assignment page
;; =============================================================================

(defn- find-plan-entry
  "Find a plan entry matching the given event-date and template-id or one-off-id."
  [plan event-date template-id one-off-id]
  (some (fn [entry]
          (let [ek (:event-key entry)]
            (when (and (= (:date ek) event-date)
                       (or (and template-id (= (:template-id ek) template-id))
                           (and one-off-id (= (:one-off-id ek) one-off-id))))
              entry)))
        plan))

(defn- find-assignment-override
  "Find an existing assignment override for the given event-date and template-id or one-off-id."
  [db event-date template-id one-off-id]
  (some (fn [o]
          (when (and (= (:event-date o) event-date)
                     (or (and template-id (= (:event-template-id o) template-id))
                         (and one-off-id (= (:one-off-event-id o) one-off-id))))
            o))
        (:assignment-overrides db)))

(defn- admin-assignment-page
  "Admin page for editing assigned users for a specific event instance."
  [{:keys [engine] :as conf} request & {:keys [error]}]
  (let [params       (:params request)
        event-date   (:event-date params)
        template-id  (:template-id params)
        one-off-id   (:one-off-id params)
        sec-token    (:sec-token request)
        db           (engine/view-db engine)
        plan         (engine/view-plan engine)
        people       (sort-by :name (:people db))
        entry        (find-plan-entry plan event-date template-id one-off-id)
        override     (find-assignment-override db event-date template-id one-off-id)
        assigned-vec (vec (if override (:assigned override) (:assigned entry)))
        n            (or (:people-required entry) (max 1 (count assigned-vec)))
        ;; Pad or trim assigned-vec to length n
        padded       (into (vec (take n assigned-vec))
                           (repeat (max 0 (- n (count assigned-vec))) nil))]
    (ui.pages/admin-page-shell conf
                               request
                               :events
                               [:div.p-4.flex.flex-col.gap-4
                                [:a
                                 {:href  (str "/" sec-token "/admin/events")
                                  :class "text-sm text-teal-700 hover:text-teal-900"}
                                 "\u2190 Back to Events"]
                                [:div
                                 [:h2.text-2xl.font-bold "Edit Assigned Users"]
                                 [:p.text-gray-600.mt-1
                                  (str (proj/display-date event-date) " \u00b7 " (or (:label entry) "(not in plan window)"))
                                  (when override
                                    [:span.text-indigo-600.ml-2 "(currently overridden)"])]]
                                (when error
                                  [:div.rounded.border.border-red-300.bg-red-50.p-3.text-sm.text-red-800
                                   error])
                                (when-not entry
                                  [:div.rounded.border.border-yellow-300.bg-yellow-50.p-3.text-sm.text-yellow-800
                                   "This event is not in the current plan window. Assignment will still be saved."])
                                ;; Save form
                                [:form
                                 {:method "POST"
                                  :action (str "/" sec-token "/admin/assignments")
                                  :class  "flex flex-col gap-2"}
                                 (ui.pages/hidden-input "event-date" event-date)
                                 (when template-id (ui.pages/hidden-input "template-id" template-id))
                                 (when one-off-id (ui.pages/hidden-input "one-off-id" one-off-id))
                                 [:div.flex.flex-col.gap-2
                                  (for [[i selected-id] (map-indexed vector padded)]
                                    [:div.flex.items-center.gap-2
                                     [:span.text-sm.text-gray-500.w-6.text-right (str (inc i) ".")]
                                     [:select
                                      {:name  "assigned"
                                       :class (into ui.frag/input-classes ["flex-1"])}
                                      [:option {:value ""} "-- Select person --"]
                                      (for [person people]
                                        (let [pid (:id person)]
                                          [:option
                                           (cond-> {:value pid}
                                             (= pid selected-id) (assoc :selected true))
                                           (str (:name person)
                                                (when (:admin? person) " \u2605"))]))]])]
                                 [:button
                                  {:type  "submit"
                                   :class ["font-bold" "border-2" "py-2" "px-4"
                                           "bg-teal-600" "text-white" "border-teal-600"
                                           "hover:bg-teal-700" "hover:border-teal-700"]}
                                  "Save Assignments"]]
                                ;; Reset form (only when override exists)
                                (when override
                                  [:form
                                   {:method   "POST"
                                    :action   (str "/" sec-token "/admin/assignments/delete")
                                    :onsubmit "return confirm('Reset to auto-assignment? The manual override will be removed.')"}
                                   (ui.pages/hidden-input "event-date" event-date)
                                   (when template-id (ui.pages/hidden-input "template-id" template-id))
                                   (when one-off-id (ui.pages/hidden-input "one-off-id" one-off-id))
                                   [:button
                                    {:type  "submit"
                                     :class (into ui.frag/button-classes
                                                  ["text-gray-700" "border-gray-400" "hover:bg-gray-100"])}
                                    "Reset to Auto-Assignment"]])])))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn- parse-event-key
  [params]
  (cond-> {:date (:event-date params)}
    (not (str/blank? (:template-id params)))
    (assoc :template-id (:template-id params))
    (not (str/blank? (:one-off-id params)))
    (assoc :one-off-id (:one-off-id params))))

(defn- handle-add-event
  [{:keys [time-layer] :as _conf} request]
  (let [params (:params request)
        label  (:label params)
        date   (:date params)
        tl     (:time-label params)
        pr     (some-> (:people-required params) parse-long)
        tl-kw  (keyword tl)]
    (cond
      (not (route-utils/valid-name? label))
      (route-utils/bad-request "Label is required")
      (not (route-utils/valid-date? date))
      (route-utils/bad-request "Invalid date format (expected YYYY-MM-DD)")
      (not (contains? route-utils/valid-time-labels tl-kw))
      (route-utils/bad-request "Time label must be morning, afternoon, or evening")
      (not (route-utils/valid-people-required? pr))
      (route-utils/bad-request "People required must be between 1 and 50")
      :else
      (let [env (time-layer/scheduler-env time-layer)]
        (engine/add-one-off! env
                             {:label           (str/trim label)
                              :date            date
                              :time-label      tl-kw
                              :people-required pr})
        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))

(defn- handle-delete-event
  [{:keys [time-layer] :as _conf} request]
  (let [event-id (:event-id (:params request))]
    (if (str/blank? event-id)
      (route-utils/bad-request "Event ID is required")
      (let [env (time-layer/scheduler-env time-layer)]
        (engine/remove-one-off! env event-id)
        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))

(defn- handle-update-template
  [{:keys [time-layer] :as _conf} request]
  (let [params      (:params request)
        template-id (:template-id params)
        pr          (some-> (:people-required params) parse-long)]
    (cond
      (str/blank? template-id)
      (route-utils/bad-request "Template ID is required")
      (not (route-utils/valid-people-required? pr))
      (route-utils/bad-request "People required must be between 1 and 50")
      :else
      (let [env (time-layer/scheduler-env time-layer)]
        (engine/update-template! env template-id {:people-required pr})
        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))

(defn- handle-set-override
  [{:keys [time-layer] :as _conf} request]
  (let [params      (:params request)
        event-date  (:event-date params)
        template-id (:template-id params)
        pr          (some-> (:people-required params) parse-long)]
    (cond
      (not (route-utils/valid-date? event-date))
      (route-utils/bad-request "Invalid event date")
      (str/blank? template-id)
      (route-utils/bad-request "Template ID is required")
      (not (route-utils/valid-people-required? pr))
      (route-utils/bad-request "People required must be between 1 and 50")
      :else
      (let [env (time-layer/scheduler-env time-layer)]
        (engine/set-instance-override! env event-date template-id pr)
        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))

(defn- handle-delete-override
  [{:keys [time-layer] :as _conf} request]
  (let [params      (:params request)
        event-date  (:event-date params)
        template-id (:template-id params)]
    (cond
      (not (route-utils/valid-date? event-date))
      (route-utils/bad-request "Invalid event date")
      (str/blank? template-id)
      (route-utils/bad-request "Template ID is required")
      :else
      (let [env (time-layer/scheduler-env time-layer)]
        (engine/remove-instance-override! env event-date template-id)
        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))

(defn- handle-set-assignments
  [conf request]
  (let [params       (:params request)
        event-date   (:event-date params)
        template-id  (:template-id params)
        one-off-id   (:one-off-id params)
        raw-assigned (:assigned params)
        all-assigned (cond
                       (nil? raw-assigned)    []
                       (string? raw-assigned) [raw-assigned]
                       :else                  (vec raw-assigned))
        assigned     (vec (remove str/blank? all-assigned))
        event-key    (parse-event-key params)
        render-error (fn [msg]
                       {:status  400
                        :headers {"Content-Type" "text/html"
                                  "X-Robots-Tag" "noindex"}
                        :body    (:body (admin-assignment-page conf request :error msg))})]
    (cond
      (not (route-utils/valid-date? event-date))
      (route-utils/bad-request "Invalid event date")
      (and (str/blank? template-id) (str/blank? one-off-id))
      (route-utils/bad-request "Template ID or One-Off ID is required")
      (empty? assigned)
      (render-error "At least one person must be assigned.")
      (not= (count assigned) (count (distinct assigned)))
      (render-error "Each person can only be assigned once.")
      :else
      (let [env (time-layer/scheduler-env (:time-layer conf))]
        (engine/set-assignment-override! env event-key assigned)
        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))

(defn- handle-delete-assignments
  [{:keys [time-layer] :as _conf} request]
  (let [params      (:params request)
        event-date  (:event-date params)
        template-id (:template-id params)
        one-off-id  (:one-off-id params)
        event-key   (parse-event-key params)]
    (cond
      (not (route-utils/valid-date? event-date))
      (route-utils/bad-request "Invalid event date")
      (and (str/blank? template-id) (str/blank? one-off-id))
      (route-utils/bad-request "Template ID or One-Off ID is required")
      :else
      (let [env (time-layer/scheduler-env time-layer)]
        (engine/remove-assignment-override! env event-key)
        (resp/redirect (str "/" (:sec-token request) "/admin/events"))))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  [conf]
  {"GET /admin/events"             (fn [request] (admin-events-page conf request))
   "POST /admin/events"            (fn [request] (handle-add-event conf request))
   "POST /admin/events/delete"     (fn [request] (handle-delete-event conf request))
   "POST /admin/templates"         (fn [request] (handle-update-template conf request))
   "POST /admin/overrides"         (fn [request] (handle-set-override conf request))
   "POST /admin/overrides/delete"  (fn [request] (handle-delete-override conf request))
   "GET /admin/assignments"        (fn [request]
                                     (let [event-date (:event-date (:params request))]
                                       (if-not (route-utils/valid-date? event-date)
                                         (route-utils/bad-request "Invalid event date")
                                         (admin-assignment-page conf request))))
   "POST /admin/assignments"       (fn [request] (handle-set-assignments conf request))
   "POST /admin/assignments/delete" (fn [request] (handle-delete-assignments conf request))})
