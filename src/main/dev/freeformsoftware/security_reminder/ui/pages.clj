(ns dev.freeformsoftware.security-reminder.ui.pages
  (:require
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [dev.freeformsoftware.security-reminder.schedule.projection :as proj]
   [hiccup2.core :as h]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Shared helpers
;; =============================================================================

(defn html-response
  [hiccup-body]
  (-> (resp/response (str (h/html hiccup-body)))
      (resp/content-type "text/html")
      (resp/header "X-Robots-Tag" "noindex")))

(defn hidden-input
  [name value]
  [:input {:type "hidden" :name name :value value}])

(defn card-wrapper
  [extra-classes & body]
  (into [:div.rounded.border.border-gray-200.p-3.flex.flex-wrap.justify-between.items-end.gap-2
         {:class extra-classes}]
        body))

(def toggle-filter-mine-script
  "on change toggle .filter-mine on #schedule-wrapper")

(def filter-mine-style
  [:style (h/raw "#schedule-wrapper.filter-mine #schedule-body > div:not(.my-event) { display: none; }")])

;; =============================================================================
;; Shared rendering
;; =============================================================================

(defn format-people-names
  "Look up person names from IDs."
  [people-list person-ids]
  (let [by-id (into {} (map (juxt :id :name)) people-list)]
    (mapv #(get by-id % %) person-ids)))

(defn event-card-body
  "Shared left side of an event card: date, label, assigned, absent.
   `overrides` is a map with optional keys:
     :people-required-override? - people count was overridden
     :assignment-override?      - assigned users were overridden"
  [entry people-list overrides]
  (let [{:keys [label date assigned absent understaffed? people-required]} entry
        assigned-names (format-people-names people-list assigned)
        absent-names   (format-people-names people-list absent)]
    [:div
     [:div.flex.flex-wrap.items-center.gap-2.font-bold
      [:span (proj/display-date date)]
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

(defn build-assignment-override-set
  "Build a set of [date template-id-or-nil one-off-id-or-nil] tuples that have assignment overrides."
  [db]
  (set (map (fn [o] [(:event-date o) (:event-template-id o) (:one-off-event-id o)])
            (:assignment-overrides db))))

;; =============================================================================
;; Admin shell
;; =============================================================================

(defn admin-sidebar
  "Sidebar navigation for admin pages."
  [request active-page]
  (let [sec-token (:sec-token request)
        link      (fn [href label page-key]
                    [:a
                     {:href  href
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

(defn admin-page-shell
  "Wrap admin content in sidebar layout."
  [conf request active-page content]
  (html-response
   (ui.frag/html-body
    (assoc conf :sec-token (:sec-token request))
    (ui.frag/page-with-sidebar
     [:h1.text-xl.font-bold.p-4 "Admin"]
     (admin-sidebar request active-page)
     content))))
