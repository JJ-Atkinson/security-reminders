(ns dev.freeformsoftware.security-reminder.ui.pages
  (:require
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.logging.log-buffer :as log-buffer]
   [hiccup2.core :as h]
   [ring.util.response :as resp]
   [zprint.core :as zprint]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- html-response [hiccup-body]
  (-> (resp/response (str (h/html hiccup-body)))
      (resp/content-type "text/html")
      (resp/header "X-Robots-Tag" "noindex")))

(defn- hidden-input [name value]
  [:input {:type "hidden" :name name :value value}])

(defn- card-wrapper [extra-classes & body]
  (into [:div.rounded.border.border-gray-200.p-3.flex.flex-wrap.justify-between.items-end.gap-2
         {:class extra-classes}]
        body))

(def ^:private toggle-filter-mine-script
  "on change toggle .filter-mine on #schedule-wrapper")

(def ^:private filter-mine-style
  [:style (h/raw "#schedule-wrapper.filter-mine #schedule-body > div:not(.my-event) { display: none; }")])

;; =============================================================================
;; Schedule rendering
;; =============================================================================

(defn- format-people-names
  "Look up person names from IDs."
  [people-list person-ids]
  (let [by-id (into {} (map (juxt :id :name)) people-list)]
    (mapv #(get by-id % %) person-ids)))

(defn- event-card-body
  "Shared left side of an event card: date, label, assigned, absent.
   `overrides` is a map with optional keys:
     :people-required-override? - people count was overridden
     :assignment-override?      - assigned users were overridden"
  [entry people-list overrides]
  (let [{:keys [label date assigned absent understaffed? people-required]} entry
        assigned-names (format-people-names people-list assigned)
        absent-names (format-people-names people-list absent)]
    [:div
     [:div.flex.flex-wrap.items-center.gap-2.font-bold
      [:span date]
      [:span "\u00b7"]
      [:span label]
      (when (:people-required-override? overrides)
        [:span.text-xs.text-purple-600.font-normal (str "(overridden: " people-required " people)")])
      (when (:assignment-override? overrides)
        [:span.text-xs.text-indigo-600.font-normal "(users overridden)"])]
     [:div.mt-1.text-sm
      [:span "Assigned: " (interpose ", " assigned-names)]
      (when understaffed?
        [:span.text-red-600.font-bold.ml-2 "UNDERSTAFFED"])]
     (when (seq absent-names)
       [:div.mt-1.text-sm "Absent: " (interpose ", " absent-names)])]))

(defn- event-card-member-action
  "Right side of event card for members: absence toggle."
  [request entry]
  (let [{:keys [event-key]} entry
        sec-token (:sec-token request)
        current-person (:person request)
        current-id (:id current-person)
        is-absent? (some #{current-id} (:absent entry))]
    [:form {:method "POST"
            :action (str "/" sec-token "/absences/toggle")
            :hx-post (str "/" sec-token "/absences/toggle")
            :hx-target "#schedule-body"
            :hx-swap "innerHTML"}
     (hidden-input "event-date" (:date event-key))
     (when (:template-id event-key)
       (hidden-input "template-id" (:template-id event-key)))
     (when (:one-off-id event-key)
       (hidden-input "one-off-id" (:one-off-id event-key)))
     [:button {:type "submit"
               :class (if is-absent?
                        "text-green-700 hover:text-green-900 font-bold"
                        "text-red-700 hover:text-red-900")}
      (if is-absent? "I'm back" "I'm out")]]))

(defn- staff-override-buttons
  "Render +/- staff buttons and optional Reset for a recurring event."
  [sec-token event-key people-required has-override?]
  (let [template-id (:template-id event-key)]
    [:div.flex.flex-wrap.items-center.gap-1
     (when (> people-required 1)
       [:form {:method "POST"
               :action (str "/" sec-token "/admin/overrides")}
        (hidden-input "event-date" (:date event-key))
        (hidden-input "template-id" template-id)
        (hidden-input "people-required" (str (dec people-required)))
        [:button {:type "submit"
                  :class (into ui.frag/small-button-classes
                               ["bg-orange-100" "border-orange-300" "hover:bg-orange-200" "hover:border-orange-400"])}
         "- Staff"]])
     [:form {:method "POST"
             :action (str "/" sec-token "/admin/overrides")}
      (hidden-input "event-date" (:date event-key))
      (hidden-input "template-id" template-id)
      (hidden-input "people-required" (str (inc people-required)))
      [:button {:type "submit"
                :class (into ui.frag/small-button-classes
                             ["hover:border-teal-800" "hover:bg-teal-200"])}
       "+ Staff"]]
     (when has-override?
       [:form {:method "POST"
               :action (str "/" sec-token "/admin/overrides/delete")}
        (hidden-input "event-date" (:date event-key))
        (hidden-input "template-id" template-id)
        [:button {:type "submit"
                  :class (into ui.frag/small-button-classes
                               ["hover:border-teal-800" "hover:bg-teal-200"])}
         "Reset"]])]))


(defn- event-card
  "Render a single event as a card on the schedule page."
  [request entry people-list override-set]
  (let [{:keys [event-key assigned absent understaffed?]} entry
        current-person (:person request)
        current-id (:id current-person)
        is-assigned? (some #{current-id} assigned)
        is-absent? (some #{current-id} absent)
        is-one-off? (boolean (:one-off-id event-key))
        has-override? (contains? override-set [(:date event-key) (:template-id event-key)])]
    (card-wrapper
     (str (cond
            understaffed? "bg-red-100"
            is-one-off? "bg-blue-50"
            is-absent? "bg-yellow-50"
            is-assigned? "bg-green-50"
            :else "bg-white")
          (when (or is-assigned? is-absent?) " my-event"))
     (event-card-body entry people-list {:people-required-override? has-override?})
     (event-card-member-action request entry))))

(defn- build-override-set
  "Build a set of [date template-id] pairs that have instance overrides."
  [engine]
  (let [db (engine/view-db engine)]
    (set (map (fn [o] [(:event-date o) (:event-template-id o)])
              (:instance-overrides db)))))

(defn- build-assignment-override-set
  "Build a set of [date template-id-or-nil one-off-id-or-nil] tuples that have assignment overrides."
  [db]
  (set (map (fn [o] [(:event-date o) (:event-template-id o) (:one-off-event-id o)])
            (:assignment-overrides db))))

(defn schedule-card-list
  "Render the list of event cards (for HTMX partial swap)."
  [conf request]
  (let [{:keys [engine]} conf
        plan (engine/view-plan engine)
        people (engine/list-people engine)
        override-set (build-override-set engine)]
    (for [entry plan]
      (event-card request entry people override-set))))

(defn schedule-cards
  "Render the full schedule card layout."
  [conf request]
  [:div#schedule-body.flex.flex-col.gap-3
   (schedule-card-list conf request)])

(defn schedule-content
  "Main schedule page content."
  [conf request]
  (let [person (:person request)
        sec-token (:sec-token request)]
    [:div.p-4.flex.flex-col.gap-4
     [:div.flex.items-center.justify-between
      [:div
       [:h2.text-2xl.font-bold "Security Schedule"]
       [:p (str "Viewing as: " (:name person))]]
      (when (:admin? person)
        [:a {:href (str "/" sec-token "/admin/users")
             :class (into ["text-sm"] ui.frag/button-classes)
             :title "Admin"}
         "Admin"])]
     [:div#schedule-wrapper.filter-mine
      [:label.flex.items-center.gap-2.mb-3.cursor-pointer
       [:input#my-events-toggle
        {:type "checkbox"
         :checked true
         :class ui.frag/checkbox-classes
         :_ toggle-filter-mine-script}]
       [:span.text-sm.font-medium "My events only"]]
      (schedule-cards conf request)]]))

(defn schedule-page
  "Full HTML schedule page."
  [conf request]
  (html-response
   (ui.frag/html-body
    conf
    (list
     filter-mine-style
     (ui.frag/background
      [:div.flex-1.overflow-y-auto
       (schedule-content conf request)])))))

(defn schedule-partial
  "HTMX partial: just the event cards."
  [conf request]
  {:status 200
   :headers {"Content-Type" "text/html"
             "X-Robots-Tag" "noindex"}
   :body (str (h/html (schedule-card-list conf request)))})

;; =============================================================================
;; Admin pages
;; =============================================================================

(defn- admin-sidebar
  "Sidebar navigation for admin pages."
  [request active-page]
  (let [sec-token (:sec-token request)
        link (fn [href label page-key]
               [:a {:href href
                    :class (if (= active-page page-key)
                             ui.frag/selected-link-classes
                             ui.frag/clickable-link-classes)}
                label])]
    [:div.flex.flex-col
     (link (str "/" sec-token "/schedule") "\u2190 Schedule" nil)
     (link (str "/" sec-token "/admin/users") "Users" :users)
     (link (str "/" sec-token "/admin/events") "Events" :events)
     (link (str "/" sec-token "/admin/messages") "Sent Messages" :messages)
     (link (str "/" sec-token "/admin/logs") "Logs" :logs)]))

(defn- admin-page-shell
  "Wrap admin content in sidebar layout."
  [conf request active-page content]
  (html-response
   (ui.frag/html-body
    conf
    (ui.frag/page-with-sidebar
     [:h1.text-xl.font-bold.p-4 "Admin"]
     (admin-sidebar request active-page)
     content))))

(defn admin-users-page
  "Admin page for managing people."
  [{:keys [engine] :as conf} request]
  (let [people (engine/list-people engine)
        sec-token (:sec-token request)]
    (admin-page-shell conf request :users
      [:div.p-4.flex.flex-col.gap-4
       [:h2.text-2xl.font-bold (str "People (" (count people) ")")]
       ;; Add person form
       [:div.border-b.border-gray-300.pb-4
        [:h3.text-lg.font-bold.mb-2 "Add Person"]
        [:form {:method "POST"
                :action (str "/" sec-token "/admin/users")
                :class "flex flex-col gap-2"}
         [:input {:type "text" :name "name" :placeholder "Name"
                  :required true :class ui.frag/input-classes}]
         [:input {:type "tel" :name "phone" :placeholder "Phone (e.g. +15551234567)"
                  :required true :class ui.frag/input-classes}]
         [:label.flex.items-center.gap-2
          [:input {:type "checkbox" :name "admin" :value "true"
                   :class ui.frag/checkbox-classes}]
          "Admin"]
         [:button {:type "submit" :class ui.frag/button-classes} "Add Person"]]]
       [:div.flex.flex-col.gap-2
        (for [person (sort-by :name people)]
          [:div.rounded.border.border-gray-200.bg-white.p-3
           [:div.flex.justify-between.items-start
            [:div
             [:span.font-bold (:name person)]
             (when (:admin? person)
               [:span.ml-2.text-yellow-600 "\u2605"])
             [:span.ml-3.text-gray-500.text-sm (:phone person)]]
            [:form {:method "POST"
                    :action (str "/" sec-token "/admin/users/delete")
                    :onsubmit "return confirm('Remove this person?')"}
             (hidden-input "person-id" (:id person))
             [:button {:type "submit" :class ui.frag/delete-button-classes} "Delete"]]]])]])))

(defn- admin-event-card
  "Render an event card on the admin events page with override/delete controls."
  [request entry people-list override-set assignment-override-set]
  (let [{:keys [event-key people-required]} entry
        sec-token (:sec-token request)
        is-one-off? (boolean (:one-off-id event-key))
        template-id (:template-id event-key)
        has-override? (contains? override-set [(:date event-key) template-id])
        has-assignment-override? (contains? assignment-override-set
                                            [(:date event-key) template-id (:one-off-id event-key)])
        assignment-link (str "/" sec-token "/admin/assignments?event-date=" (:date event-key)
                             (if is-one-off?
                               (str "&one-off-id=" (:one-off-id event-key))
                               (str "&template-id=" template-id)))]
    (card-wrapper
     (if is-one-off? "bg-blue-50" "bg-white")
     (event-card-body entry people-list
                      {:people-required-override? has-override?
                       :assignment-override? has-assignment-override?})
     [:div.flex.items-center.gap-2
      [:a {:href assignment-link
           :class (into ui.frag/small-button-classes
                        ["bg-indigo-100" "border-indigo-300" "hover:bg-indigo-200" "hover:border-indigo-400"])}
       "Edit Users"]
      (if is-one-off?
        [:form {:method "POST"
                :action (str "/" sec-token "/admin/events/delete")
                :onsubmit "return confirm('Remove this event?')"}
         (hidden-input "event-id" (:one-off-id event-key))
         [:button {:type "submit" :class ui.frag/delete-button-classes} "Delete"]]
        (staff-override-buttons sec-token event-key people-required has-override?))])))

(defn admin-events-page
  "Admin page for managing events."
  [{:keys [engine] :as conf} request]
  (let [db (engine/view-db engine)
        templates (:event-templates db)
        plan (engine/view-plan engine)
        people (:people db)
        override-set (set (map (fn [o] [(:event-date o) (:event-template-id o)])
                               (:instance-overrides db)))
        assignment-override-set (build-assignment-override-set db)
        sec-token (:sec-token request)
        day-name {1 "Mon" 2 "Tue" 3 "Wed" 4 "Thu" 5 "Fri" 6 "Sat" 7 "Sun"}]
    (admin-page-shell conf request :events
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
        [:form {:method "POST"
                :action (str "/" sec-token "/admin/events")
                :class "flex flex-col gap-2 mt-2"}
         [:div
          [:label.text-sm.font-semibold {:for "label"} "Label"]
          [:input {:type "text" :name "label" :id "label" :placeholder "e.g. Special Duty"
                   :required true :class ui.frag/input-classes}]]
         [:div
          [:label.text-sm.font-semibold {:for "date"} "Date"]
          [:input {:type "date" :name "date" :id "date" :required true
                   :value (str (java.time.LocalDate/now))
                   :class ui.frag/input-classes}]]
         [:div
          [:label.text-sm.font-semibold {:for "time-label"} "Time"]
          [:select {:name "time-label" :id "time-label" :class ui.frag/input-classes}
           [:option {:value "morning"} "Morning"]
           [:option {:value "afternoon"} "Afternoon"]
           [:option {:value "evening" :selected true} "Evening"]]]
         [:div
          [:label.text-sm.font-semibold {:for "people-required"} "People required"]
          [:input {:type "number" :name "people-required" :id "people-required" :value "2" :min "1"
                   :class ui.frag/input-classes}]]
         [:button {:type "submit"
                   :class ["font-bold" "border-2" "py-1" "px-2"
                           "bg-teal-600" "text-white" "border-teal-600"
                           "hover:bg-teal-700" "hover:border-teal-700"]}
          "Add Event"]]]

       ;; Projected schedule with admin controls
       [:details.border-t.border-gray-300.pt-4
        {:open true
         :_ ui.frag/accordion-exclusive-script}
        [:summary.cursor-pointer [:h2.text-lg.font-bold.inline "Projected Schedule"]]
        [:p.text-sm.text-gray-500.mb-2.mt-2 "Edit people count per instance. One-off events shown in blue."]
        [:div.flex.flex-col.gap-3
         (for [entry plan]
           (admin-event-card request entry people override-set assignment-override-set))]]])))

(defn- type-badge
  "Color-coded badge for notification type."
  [type]
  (let [[bg text label] (case type
                          :reminder  ["bg-blue-100"  "text-blue-800"  "reminder"]
                          :assigned  ["bg-green-100" "text-green-800" "assigned"]
                          :rescinded ["bg-red-100"   "text-red-800"   "rescinded"]
                          ["bg-gray-100" "text-gray-800" (name type)])]
    [:span.text-xs.font-semibold.px-2.py-0.5.rounded {:class (str bg " " text)} label]))

(defn- lookup-event-label
  "Look up event label from templates or one-off events."
  [db notification]
  (or (when-let [tid (:event-template-id notification)]
        (:label (some #(when (= (:id %) tid) %) (:event-templates db))))
      (when-let [oid (:one-off-event-id notification)]
        (:label (some #(when (= (:id %) oid) %) (:one-off-events db))))
      "(unknown event)"))

(defn admin-messages-page
  "Admin page showing all sent messages."
  [{:keys [engine] :as conf} request]
  (let [db (engine/view-db engine)
        notifications (engine/list-sent-notifications engine)
        people-by-id (into {} (map (juxt :id :name)) (:people db))]
    (admin-page-shell conf request :messages
      [:div.p-4.flex.flex-col.gap-4
       [:h2.text-2xl.font-bold (str "Sent Messages (" (count notifications) ")")]
       (if (empty? notifications)
         [:p.text-gray-500 "No messages sent yet."]
         [:div.flex.flex-col.gap-2
          (for [n notifications]
            (let [person-name (get people-by-id (:person-id n) "(removed)")
                  event-label (lookup-event-label db n)]
              (card-wrapper "bg-white"
                [:div.flex-1
                 [:div.flex.flex-wrap.items-center.gap-2
                  [:span.font-bold person-name]
                  [:span "\u00b7"]
                  (type-badge (:type n))
                  [:span "\u00b7"]
                  [:span event-label]
                  [:span "\u00b7"]
                  [:span (:event-date n)]]
                 [:div.text-xs.text-gray-400 (str "Sent: " (:sent-at n))]])))])])))

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

(defn admin-assignment-page
  "Admin page for editing assigned users for a specific event instance."
  [{:keys [engine] :as conf} request & {:keys [error]}]
  (let [params (:params request)
        event-date (:event-date params)
        template-id (:template-id params)
        one-off-id (:one-off-id params)
        sec-token (:sec-token request)
        db (engine/view-db engine)
        plan (engine/view-plan engine)
        people (sort-by :name (:people db))
        entry (find-plan-entry plan event-date template-id one-off-id)
        override (find-assignment-override db event-date template-id one-off-id)
        assigned-vec (vec (if override (:assigned override) (:assigned entry)))
        n (or (:people-required entry) (max 1 (count assigned-vec)))
        ;; Pad or trim assigned-vec to length n
        padded (into (vec (take n assigned-vec))
                     (repeat (max 0 (- n (count assigned-vec))) nil))]
    (admin-page-shell conf request :events
      [:div.p-4.flex.flex-col.gap-4
       [:a {:href (str "/" sec-token "/admin/events")
            :class "text-sm text-teal-700 hover:text-teal-900"}
        "\u2190 Back to Events"]
       [:div
        [:h2.text-2xl.font-bold "Edit Assigned Users"]
        [:p.text-gray-600.mt-1
         (str event-date " \u00b7 " (or (:label entry) "(not in plan window)"))
         (when override
           [:span.text-indigo-600.ml-2 "(currently overridden)"])]]
       (when error
         [:div.rounded.border.border-red-300.bg-red-50.p-3.text-sm.text-red-800
          error])
       (when-not entry
         [:div.rounded.border.border-yellow-300.bg-yellow-50.p-3.text-sm.text-yellow-800
          "This event is not in the current plan window. Assignment will still be saved."])
       ;; Save form
       [:form {:method "POST"
               :action (str "/" sec-token "/admin/assignments")
               :class "flex flex-col gap-2"}
        (hidden-input "event-date" event-date)
        (when template-id (hidden-input "template-id" template-id))
        (when one-off-id (hidden-input "one-off-id" one-off-id))
        [:div.flex.flex-col.gap-2
         (for [[i selected-id] (map-indexed vector padded)]
           [:div.flex.items-center.gap-2
            [:span.text-sm.text-gray-500.w-6.text-right (str (inc i) ".")]
            [:select {:name "assigned"
                      :class (into ui.frag/input-classes ["flex-1"])}
             [:option {:value ""} "-- Select person --"]
             (for [person people]
               (let [pid (:id person)]
                 [:option (cond-> {:value pid}
                            (= pid selected-id) (assoc :selected true))
                  (str (:name person)
                       (when (:admin? person) " \u2605"))]))]])]
        [:button {:type "submit"
                  :class ["font-bold" "border-2" "py-2" "px-4"
                          "bg-teal-600" "text-white" "border-teal-600"
                          "hover:bg-teal-700" "hover:border-teal-700"]}
         "Save Assignments"]]
       ;; Reset form (only when override exists)
       (when override
         [:form {:method "POST"
                 :action (str "/" sec-token "/admin/assignments/delete")
                 :onsubmit "return confirm('Reset to auto-assignment? The manual override will be removed.')"}
          (hidden-input "event-date" event-date)
          (when template-id (hidden-input "template-id" template-id))
          (when one-off-id (hidden-input "one-off-id" one-off-id))
          [:button {:type "submit"
                    :class (into ui.frag/button-classes
                                 ["text-gray-700" "border-gray-400" "hover:bg-gray-100"])}
           "Reset to Auto-Assignment"]])])))

;; =============================================================================
;; Admin logs page
;; =============================================================================

(defn- level-badge
  "Color-coded badge for log level."
  [level]
  (let [[bg text] (case level
                    (:error :fatal) ["bg-red-100"    "text-red-800"]
                    :warn           ["bg-yellow-100" "text-yellow-800"]
                    :info           ["bg-blue-100"   "text-blue-800"]
                    ["bg-gray-100" "text-gray-800"])]
    [:span.text-xs.font-semibold.px-2.py-0.5.rounded.uppercase
     {:class (str bg " " text)}
     (name level)]))

(defn- log-entry-card
  "Render a single log entry as a styled card."
  [entry]
  (let [{:keys [inst level ns msg data error]} entry
        row-bg (case level
                 (:error :fatal) "bg-red-50"
                 :warn           "bg-yellow-50"
                 "bg-white")]
    [:div.rounded.border.border-gray-200.p-3
     {:class row-bg}
     [:div.flex.flex-wrap.items-center.gap-2
      (level-badge level)
      [:span.text-xs.text-gray-500 (str inst)]
      [:span.text-xs.text-gray-400 ns]]
     [:div.mt-1.text-sm msg]
     (when data
       [:pre.mt-1.text-xs.text-gray-700.bg-gray-100.p-2.rounded.overflow-x-auto
        (zprint/zprint-str data)])
     (when error
       [:pre.mt-1.text-xs.text-red-700.bg-red-100.p-2.rounded.overflow-x-auto
        error])]))

(def ^:private toggle-log-view-script
  "on click toggle .hidden on #styled-logs
    then toggle .hidden on #plain-logs
    then if my.innerText is 'Plain Text' set my.innerText to 'Styled' else set my.innerText to 'Plain Text'")

(defn admin-logs-page
  "Admin page showing recent log entries."
  [conf request]
  (let [lb      (:log-buffer (:logging conf))
        entries (when lb (log-buffer/recent-logs lb))
        errors  (when lb (log-buffer/recent-errors lb))]
    (admin-page-shell conf request :logs
      [:div.p-4.flex.flex-col.gap-4.min-w-0
       [:div.flex.items-center.justify-between
        [:h2.text-2xl.font-bold "Logs"]
        [:button {:type "button"
                  :class ui.frag/button-classes
                  :_ toggle-log-view-script}
         "Plain Text"]]
       ;; Styled view (default)
       [:div#styled-logs.flex.flex-col.gap-4
        [:details.border-t.border-gray-300.pt-4
         {:_ ui.frag/accordion-exclusive-script}
         [:summary.cursor-pointer
          [:h2.text-lg.font-bold.inline (str "Errors (" (count errors) ")")]]
         [:div.flex.flex-col.gap-2.mt-2
          (if (seq errors)
            (for [entry errors]
              (log-entry-card entry))
            [:p.text-gray-500 "No errors."])]]
        [:details.border-t.border-gray-300.pt-4
         {:open true
          :_ ui.frag/accordion-exclusive-script}
         [:summary.cursor-pointer
          [:h2.text-lg.font-bold.inline (str "Recent (" (count entries) ")")]]
         [:div.flex.flex-col.gap-2.mt-2
          (if (seq entries)
            (for [entry entries]
              (log-entry-card entry))
            [:p.text-gray-500 "No log entries yet."])]]]
       ;; Plain text view (hidden by default)
       [:div#plain-logs.hidden.min-w-0
        [:pre.text-xs.bg-gray-900.text-green-400.p-4.rounded.overflow-x-auto
         (apply str (interpose "\n" (map :formatted entries)))]]])))
