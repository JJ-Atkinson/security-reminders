(ns dev.freeformsoftware.security-reminder.ui.pages.admin.users
  (:require
   [clojure.string :as str]
   [dev.freeformsoftware.security-reminder.ui.pages :as ui.pages]
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.schedule.time-layer :as time-layer]
   [dev.freeformsoftware.security-reminder.server.route-utils :as route-utils]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Users page rendering
;; =============================================================================

(defn- admin-users-page
  "Admin page for managing people."
  [{:keys [engine] :as conf} request]
  (let [people    (engine/list-people engine)
        sec-token (:sec-token request)
        base-url  (:base-url engine)]
    (ui.pages/admin-page-shell conf
                               request
                               :users
                               [:div.p-4.flex.flex-col.gap-4
                                [:h2.text-2xl.font-bold (str "People (" (count people) ")")]
                                ;; Add person form
                                [:div.border-b.border-gray-300.pb-4
                                 [:h3.text-lg.font-bold.mb-2 "Add Person"]
                                 [:form
                                  {:method "POST"
                                   :action (str "/" sec-token "/admin/users")
                                   :class  "flex flex-col gap-2"}
                                  [:input
                                   {:type        "text"
                                    :name        "name"
                                    :placeholder "Name"
                                    :required    true
                                    :class       ui.frag/input-classes}]
                                  [:input
                                   {:type        "email"
                                    :name        "email"
                                    :placeholder "Email"
                                    :required    true
                                    :class       ui.frag/input-classes}]
                                  [:label.flex.items-center.gap-2
                                   [:input
                                    {:type  "checkbox"
                                     :name  "admin"
                                     :value "true"
                                     :class ui.frag/checkbox-classes}]
                                   "Admin"]
                                  [:button {:type "submit" :class ui.frag/button-classes} "Add Person"]]]
                                [:div.flex.flex-col.gap-2
                                 (for [person (sort-by :name people)
                                       :let [token (engine/get-token-for-person engine (:id person))
                                             link  (when token (str base-url "/" token "/schedule"))]]
                                   [:div.rounded.border.border-gray-200.bg-white.p-3
                                    [:div
                                     [:span.font-bold (:name person)]
                                     (when (:admin? person)
                                       [:span.ml-2.text-yellow-600 "\u2605"])
                                     [:div.text-gray-500.text-sm (:email person)]]
                                    [:div.flex.flex-wrap.items-center.gap-2.mt-2
                                     (when link
                                       [:a.text-blue-600.text-sm.underline
                                        {:href link}
                                        (str "View " (:name person) " schedule")])
                                     [:div.flex.items-center.gap-2.ml-auto
                                      (when link
                                        [:button
                                         {:class (into ["text-sm" "whitespace-nowrap"] ui.frag/button-classes)
                                          :_     (str "on click writeText('" link "') into navigator.clipboard"
                                                      " then put 'Copied!' into me"
                                                      " wait 1.5s"
                                                      " then put 'Copy Link' into me")}
                                         "Copy Link"])
                                      [:form
                                       {:method   "POST"
                                        :action   (str "/" sec-token "/admin/users/delete")
                                        :onsubmit "return confirm('Remove this person?')"}
                                       (ui.pages/hidden-input "person-id" (:id person))
                                       [:button {:type "submit" :class ui.frag/delete-button-classes} "Delete"]]]]])]])))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn- handle-add-user
  [{:keys [time-layer] :as _conf} request]
  (let [params (:params request)
        pname  (:name params)
        email  (:email params)
        admin? (= "true" (:admin params))]
    (cond
      (not (route-utils/valid-name? pname))
      (route-utils/bad-request "Name is required")
      (not (route-utils/valid-email? email))
      (route-utils/bad-request "Valid email is required")
      :else
      (let [env (time-layer/scheduler-env time-layer)]
        (engine/add-person! env {:name (str/trim pname) :email email :admin? admin?})
        (resp/redirect (str "/" (:sec-token request) "/admin/users"))))))

(defn- handle-delete-user
  [{:keys [time-layer] :as _conf} request]
  (let [person-id (:person-id (:params request))]
    (if (str/blank? person-id)
      (route-utils/bad-request "Person ID is required")
      (let [env (time-layer/scheduler-env time-layer)]
        (engine/remove-person! env person-id)
        (resp/redirect (str "/" (:sec-token request) "/admin/users"))))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  [conf]
  {"GET /admin/users"         (fn [request] (admin-users-page conf request))
   "POST /admin/users"        (fn [request] (handle-add-user conf request))
   "POST /admin/users/delete" (fn [request] (handle-delete-user conf request))})
