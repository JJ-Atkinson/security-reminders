(ns dev.freeformsoftware.security-reminder.ui.pages
  (:require
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [dev.freeformsoftware.security-reminder.ui.pages.home :as home]
   [dev.freeformsoftware.security-reminder.server.route-utils :as server.route-utils]
   [hiccup2.core :as h]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

(def pages
  [{:id        :home
    :route     "/pages/home"
    :title     "Home"
    :body-fn   #'home/home-page}])

(def page-by-id
  (-> (group-by :id pages)
      (update-vals first)))

(defn root-page
  [conf req page]
  (let [{:keys [route title body-fn]} (get page-by-id page)]
    (ui.frag/html-body
     conf
     (ui.frag/page-with-sidebar
      [:h1.text-2xl.p-4.text-center.font-bold (:app-title conf)]
      [:div.flex.flex-col.font-bold.h-full.gap-2
       (ui.frag/sidebar
        [:div.flex.flex-col
         (for [{:keys [route title id]} pages]
           [:a
            {:class (if (= page id)
                      ui.frag/selected-link-classes
                      ui.frag/clickable-link-classes)
             :href  route}
            title])]
        nil)]
      (when body-fn (body-fn conf req))))))

(defn page-routes
  [conf]
  {"GET /"              (fn [request]
                          (resp/redirect "/pages/home"))
   "GET /pages/home/**" (fn [request]
                          (-> (resp/response (str (h/html (root-page conf request :home))))
                              (resp/content-type "text/html")))})
