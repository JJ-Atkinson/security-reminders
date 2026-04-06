(ns dev.freeformsoftware.security-reminder.ui.pages.admin.settings
  (:require
   [cheshire.core :as json]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.schedule.ops :as ops]
   [dev.freeformsoftware.security-reminder.schedule.time-layer :as time-layer]
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [dev.freeformsoftware.security-reminder.ui.pages :as ui.pages]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Settings page rendering
;; =============================================================================

(defn- push-notification-section
  [{:keys [vapid-public-key] :as _conf} request]
  (let [sec-token (:sec-token request)
        person    (:person request)
        push?     (get person :notifications/send-via-push? true)
        toggle-url (str "/" sec-token "/admin/settings/toggle-preference")]
    [:div#push-section
     {:_ "init call initPushSection()"}
     [:h3.text-lg.font-semibold "Push Notifications"]
     [:p.text-sm.text-gray-600 "Get push notifications on this device when installed as an app."]
     (when-not (seq vapid-public-key)
       [:p.text-sm.text-red-600.font-bold "VAPID keys not configured. Run bin/generate-vapid-keys and set the Garden secrets."])

     ;; State A: Cannot enable (JS picks which sub-message to show)
     [:div#push-state-cannot.hidden.mt-2
      [:p#push-denied.hidden.text-sm.text-amber-700
       "You have denied notification permission for this app. To re-enable, update your device's notification settings."]
      [:p#push-not-installed.hidden.text-sm.text-gray-600
       "Push notifications require installing this app to your home screen. On iOS, tap Share then \"Add to Home Screen\"."]]

     ;; State B: Can enable
     [:div#push-state-can-enable.hidden.mt-2
      [:button#enable-push-btn
       {:class             ui.frag/button-classes
        :data-vapid-key    vapid-public-key
        :data-subscribe-url (str "/" sec-token "/push/subscribe")
        :data-toggle-url   toggle-url
        :_ "on click call handleEnablePush(me)"}
       "Enable Push Notifications"]
      [:p#push-enable-failed.hidden.text-sm.text-red-600.mt-2
       "Push subscription failed. Please contact your admin."]]

     ;; State C: Already enabled
     [:div#push-state-enabled.hidden.mt-2
      [:label.flex.items-center.gap-2
       [:input#push-checkbox
        {:type     "checkbox"
         :class    ui.frag/checkbox-classes
         :checked  push?
         :hx-post  toggle-url
         :hx-vals  (json/generate-string {"field" "notifications/send-via-push?"})
         :hx-swap  "none"}]
       "Send push notifications to this device"]]]))

(defn- settings-page
  [conf request]
  (let [sec-token  (:sec-token request)
        person     (:person request)
        email?     (get person :notifications/send-via-email? true)
        toggle-url (str "/" sec-token "/admin/settings/toggle-preference")]
    (ui.pages/admin-page-shell
     conf
     request
     :settings
     [:div.p-4.flex.flex-col.gap-4
      [:h2.text-2xl.font-bold "Settings"]

      ;; Email preference (always visible)
      [:div
       [:h3.text-lg.font-semibold "Email Notifications"]
       [:p.text-sm.text-gray-600 "Receive assignment reminders and schedule changes via email."]
       [:label.flex.items-center.gap-2.mt-2
        [:input
         {:type     "checkbox"
          :class    ui.frag/checkbox-classes
          :checked  email?
          :hx-post  toggle-url
          :hx-vals  (json/generate-string {"field" "notifications/send-via-email?"})
          :hx-swap  "none"}]
        "Send email notifications"]]

      ;; Push preference (3-state, client-side detection)
      (push-notification-section conf request)])))

;; =============================================================================
;; Toggle preference handler
;; =============================================================================

(defn- handle-toggle-preference
  [{:keys [time-layer] :as _conf} request]
  (let [params    (:params request)
        field-str (:field params)
        person    (:person request)
        field-kw  (keyword field-str)]
    (if-not (#{:notifications/send-via-email? :notifications/send-via-push?} field-kw)
      {:status 400 :body "Invalid field"}
      (let [env       (time-layer/scheduler-env time-layer)
            new-value (if-let [v (:value params)]
                        (= "true" v)
                        (not (get person field-kw true)))]
        (engine/with-state!-> env
          (ops/update-person-field (:id person) field-kw new-value))
        {:status 204}))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  [conf]
  {"GET /admin/settings"                    (fn [request] (settings-page conf request))
   "POST /admin/settings/toggle-preference" (fn [request] (handle-toggle-preference conf request))})
