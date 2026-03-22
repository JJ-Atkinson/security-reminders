(ns dev.freeformsoftware.security-reminder.ui.pages.home
  (:require
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]))

(set! *warn-on-reflection* true)

(defn home-page
  [conf req]
  [:div.p-6.flex.flex-col.gap-4
   [:h2.text-3xl.font-bold "Welcome to Security Reminder"]
   [:p "This is a sample page using the sidebar layout with HTMX + Tailwind."]

   ;; HTMX demo
   [:div.border-2.p-4
    [:h3.text-xl.font-semibold.mb-2 "HTMX Demo"]
    [:button.btn
     {:hx-get    "/pages/home/time"
      :hx-target "#time-display"
      :hx-swap   "innerHTML"}
     "Get Server Time"]
    [:div#time-display.mt-2.p-2.bg-white
     "Click the button to fetch the current server time."]]])
