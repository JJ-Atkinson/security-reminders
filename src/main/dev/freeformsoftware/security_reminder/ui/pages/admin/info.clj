(ns dev.freeformsoftware.security-reminder.ui.pages.admin.info
  (:require
   [dev.freeformsoftware.security-reminder.ui.pages :as ui.pages]
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.schedule.projection :as proj]
   [dev.freeformsoftware.security-reminder.logging.log-buffer :as log-buffer]
   [zprint.core :as zprint]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Messages page
;; =============================================================================

(defn- type-badge
  "Color-coded badge for notification type."
  [type]
  (let [[bg text label] (case type
                          :reminder  ["bg-blue-100" "text-blue-800" "reminder"]
                          :assigned  ["bg-green-100" "text-green-800" "assigned"]
                          :rescinded ["bg-red-100" "text-red-800" "rescinded"]
                          :welcome   ["bg-purple-100" "text-purple-800" "welcome"]
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

(defn- admin-messages-page
  "Admin page showing all sent messages."
  [{:keys [engine] :as conf} request]
  (let [db            (engine/view-db engine)
        notifications (engine/list-sent-notifications engine)
        people-by-id  (into {} (map (juxt :id :name)) (:people db))]
    (ui.pages/admin-page-shell conf
                               request
                               :messages
                               [:div.p-4.flex.flex-col.gap-4
                                [:h2.text-2xl.font-bold (str "Sent Messages (" (count notifications) ")")]
                                (if (empty? notifications)
                                  [:p.text-gray-500 "No messages sent yet."]
                                  [:div.flex.flex-col.gap-2
                                   (for [n notifications]
                                     (let [person-name (get people-by-id (:person-id n) "(removed)")
                                           event-label (lookup-event-label db n)]
                                       (ui.pages/card-wrapper "bg-white"
                                                              [:div.flex-1
                                                               [:div.flex.flex-wrap.items-center.gap-2
                                                                [:span.font-bold person-name]
                                                                [:span "\u00b7"]
                                                                (type-badge (:type n))
                                                                (when (:event-date n)
                                                                  (list
                                                                   [:span "\u00b7"]
                                                                   [:span event-label]
                                                                   [:span "\u00b7"]
                                                                   [:span (proj/display-date (:event-date n))]))]
                                                               [:div.text-xs.text-gray-400 (str "Sent: " (:sent-at n))]])))])])))

;; =============================================================================
;; Logs page
;; =============================================================================

(defn- level-badge
  "Color-coded badge for log level."
  [level]
  (let [[bg text] (case level
                    (:error :fatal) ["bg-red-100" "text-red-800"]
                    :warn           ["bg-yellow-100" "text-yellow-800"]
                    :info           ["bg-blue-100" "text-blue-800"]
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

(defn- admin-logs-page
  "Admin page showing recent log entries."
  [conf request]
  (let [lb      (:log-buffer (:logging conf))
        entries (when lb (log-buffer/recent-logs lb))
        errors  (when lb (log-buffer/recent-errors lb))]
    (ui.pages/admin-page-shell conf
                               request
                               :logs
                               [:div.p-4.flex.flex-col.gap-4.min-w-0
                                [:div.flex.items-center.justify-between
                                 [:h2.text-2xl.font-bold "Logs"]
                                 [:button
                                  {:type  "button"
                                   :class ui.frag/button-classes
                                   :_     toggle-log-view-script}
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
                                   :_    ui.frag/accordion-exclusive-script}
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

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  [conf]
  {"GET /admin/messages" (fn [request] (admin-messages-page conf request))
   "GET /admin/logs"     (fn [request] (admin-logs-page conf request))})
