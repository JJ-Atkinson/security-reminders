(ns dev.freeformsoftware.security-reminder.ui.pages.admin.settings
  (:require
   [dev.freeformsoftware.security-reminder.ui.pages :as ui.pages]
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Settings page rendering
;; =============================================================================

(defn- settings-page
  [{:keys [vapid-public-key] :as conf} request]
  (let [sec-token (:sec-token request)]
    (ui.pages/admin-page-shell
     conf
     request
     :settings
     [:div.p-4.flex.flex-col.gap-4
      [:h2.text-2xl.font-bold "Settings"]
      ;; Push notification section
      [:div#push-section
       [:h3.text-lg.font-semibold "Push Notifications"]
       [:p.text-sm.text-gray-600 "Enable push notifications to get reminders on this device."]
       [:div.mt-2
        [:button#enable-push
         {:class (into ["hidden"] ui.frag/button-classes)
          :data-vapid-key   vapid-public-key
          :data-subscribe-url (str "/" sec-token "/push/subscribe")
          :_ (str "init call shouldShowPushButton() then if it remove .hidden from me end "
                  "on click call subscribePush(my.dataset.vapidKey, my.dataset.subscribeUrl) "
                  "then add .hidden to me")}
         "Enable Push Notifications"]]]])))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  [conf]
  {"GET /admin/settings" (fn [request] (settings-page conf request))})
