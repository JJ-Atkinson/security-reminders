(ns dev.freeformsoftware.security-reminder.schedule.reminders
  "Email message formatting for reminders and corrections.
   Generates {:subject :text :html} maps for garden-email."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [dev.freeformsoftware.security-reminder.schedule.projection :as proj]
   [hiccup2.core :as h]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; CSS (loaded once from compiled Tailwind output)
;; =============================================================================

(def ^:private tailwind-css
  (delay (slurp (io/resource "public/css/output.css"))))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- format-people-names
  [people-list person-ids]
  (let [by-id (into {} (map (juxt :id :name)) people-list)]
    (mapv #(get by-id % %) person-ids)))

(defn- absence-toggle-url
  "Build a GET URL to toggle absence for an event from email."
  [base-url token event-key]
  (str base-url "/" token "/absences/toggle?"
       "event-date=" (:date event-key)
       (when (:template-id event-key)
         (str "&template-id=" (:template-id event-key)))
       (when (:one-off-id event-key)
         (str "&one-off-id=" (:one-off-id event-key)))))

;; =============================================================================
;; Email event cards (mirrors ui/pages event-card-body)
;; =============================================================================

(defn- email-event-card
  "Render a single event card for email, mirroring the schedule page.
   Includes 'I'm out' / 'I'm back' link for the recipient."
  [entry people-list person base-url token]
  (let [{:keys [label date assigned absent understaffed? event-key]} entry
        assigned-names (format-people-names people-list assigned)
        absent-names   (format-people-names people-list absent)
        current-id     (:id person)
        is-assigned?   (some #{current-id} assigned)
        is-absent?     (some #{current-id} absent)
        is-one-off?    (boolean (:one-off-id event-key))
        bg-class       (cond
                         understaffed? "bg-red-100"
                         is-one-off?   "bg-blue-50"
                         is-absent?    "bg-yellow-50"
                         is-assigned?  "bg-green-50"
                         :else         "bg-white")]
    [:div.rounded.border.border-gray-200.p-3.flex.flex-wrap.justify-between.items-end.gap-2
     {:class bg-class}
     [:div
      [:div.flex.flex-wrap.items-center.gap-2.font-bold
       [:span (proj/display-date date)]
       [:span "\u00b7"]
       [:span label]]
      [:div.mt-1.text-sm
       [:span "Assigned: " (str/join ", " assigned-names)]
       (when understaffed?
         [:span.text-red-600.font-bold.ml-2 "UNDERSTAFFED"])]
      (when (seq absent-names)
        [:div.mt-1.text-sm "Absent: " (str/join ", " absent-names)])]
     [:a {:href  (absence-toggle-url base-url token event-key)
          :class (if is-absent?
                   "text-green-700 font-bold"
                   "text-red-700")}
      (if is-absent? "I'm back" "I'm out")]]))

;; =============================================================================
;; Generalized email template
;; =============================================================================

(defn- my-events
  "Filter plan entries to only those where person is assigned or absent."
  [entries person]
  (let [pid (:id person)]
    (filterv (fn [entry]
               (or (some #{pid} (:assigned entry))
                   (some #{pid} (:absent entry))))
             entries)))

(defn email-html
  "Render a complete email HTML document.
   `header-hiccup` - the blue header section (varies per email type)
   `entries`        - plan entries (will be filtered to recipient's events)
   `people-list`    - for name lookups
   `person`         - the recipient
   `base-url`       - app base URL
   `token`          - recipient's auth token"
  [header-hiccup entries people-list person base-url token]
  (let [my-entries (my-events entries person)]
    (str
     (h/html
      (h/raw "<!DOCTYPE html>\n")
      [:html
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:style (h/raw @tailwind-css)]]
       [:body.bg-orange-50.p-4
        ;; Blue header banner
        [:div.bg-blue-600.text-white.font-bold.p-4.rounded.mb-4
         header-hiccup]

        ;; My events
        [:h2.text-xl.font-bold.mb-3 "Your Events"]
        (if (seq my-entries)
          [:div.flex.flex-col.gap-3.mb-6
           (for [entry my-entries]
             (email-event-card entry people-list person base-url token))]
          [:div.text-gray-500.mb-6 "No upcoming events assigned to you."])

        ;; View in browser button
        [:div.text-center.mb-24
         [:a {:href  (str base-url "/" token "/schedule")
              :class "font-bold border-2 py-2 px-4 inline-block"}
          "View in Browser"]]

        ;; garden-email auto-appends unsubscribe + report-spam footer for subscribed users.
        ;; {{subscribe-link}} controls placement of the opt-in confirmation link for new subscribers.
        [:div.text-center.text-xs.text-gray-400.mt-16
         (h/raw "{{subscribe-link}}")]]]))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn format-reminder-email
  "Returns {:subject :text :html} for a reminder.
   reminder-group >= 2: heads-up; <= 1 or nil: final reminder."
  [person event-label event-date link reminder-group plan people-list]
  (let [base-url  (str/replace link #"/[^/]+/schedule$" "")
        token     (second (re-find #"/([^/]+)/schedule$" link))
        heads-up? (and reminder-group (>= reminder-group 2))
        prefix    (if heads-up? "Heads Up" "Reminder")
        display   (proj/display-date event-date)]
    {:subject (str "Security Duty: " event-label " on " display)
     :text    (str "Hi " (:name person)
                   (if heads-up?
                     ", heads up: you're assigned to "
                     ", reminder: you're assigned to ")
                   event-label " on " display
                   ". View schedule & mark absences: " link)
     :html    (email-html
               [:div
                [:div.text-lg (str prefix ": You're assigned to " event-label)]
                [:div.text-sm.mt-1 (str "Date: " display)]]
               plan people-list person base-url token)}))

(defn format-correction-email
  "Returns {:subject :text :html} for a correction."
  [correction-type person event-label event-date link plan people-list]
  (let [base-url (str/replace link #"/[^/]+/schedule$" "")
        token    (second (re-find #"/([^/]+)/schedule$" link))
        display  (proj/display-date event-date)]
    (case correction-type
      :rescinded
      {:subject (str "Schedule Update: No longer assigned " event-label " on " display)
       :text    (str "Hi " (:name person)
                     ", update: you are no longer assigned to "
                     event-label " on " display
                     ". No action needed. View schedule: " link)
       :html    (email-html
                 [:div
                  [:div.text-lg "Update: You're no longer assigned to " event-label]
                  [:div.text-sm.mt-1 (str "Date: " display " \u2014 No action needed")]]
                 plan people-list person base-url token)}

      :assigned
      {:subject (str "Schedule Update: Assigned to " event-label " on " display)
       :text    (str "Hi " (:name person)
                     ", update: you've been assigned to "
                     event-label " on " display
                     ". View schedule & mark absences: " link)
       :html    (email-html
                 [:div
                  [:div.text-lg "Update: You've been assigned to " event-label]
                  [:div.text-sm.mt-1 (str "Date: " display)]]
                 plan people-list person base-url token)})))
