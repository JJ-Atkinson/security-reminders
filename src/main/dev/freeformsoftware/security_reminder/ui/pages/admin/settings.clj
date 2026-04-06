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
       (when-not (seq vapid-public-key)
         [:p.text-sm.text-red-600.font-bold "VAPID keys not configured. Run bin/generate-vapid-keys and set the Garden secrets."])
       [:div.mt-2
        [:button#enable-push
         {:class (into ["hidden"] ui.frag/button-classes)
          :data-vapid-key   vapid-public-key
          :data-subscribe-url (str "/" sec-token "/push/subscribe")
          :_ (str "init call shouldShowPushButton() then if it remove .hidden from me end "
                  "on click "
                  "set my.innerText to 'Enabling...' "
                  "call subscribePush(my.dataset.vapidKey, my.dataset.subscribeUrl) "
                  "then if it "
                  "set my.innerText to 'Enabled!' "
                  "wait 1s then add .hidden to me "
                  "else "
                  "set my.innerText to 'Failed' "
                  "remove .hidden from #push-status "
                  "end")}
         "Enable Push Notifications"]
        [:p#push-status.hidden.text-sm.text-red-600.mt-2
         "Push notifications were denied or failed. Check your device notification settings for this app."]]]])))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  [conf]
  {"GET /admin/settings" (fn [request] (settings-page conf request))})
