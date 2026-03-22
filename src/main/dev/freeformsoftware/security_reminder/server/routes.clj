(ns dev.freeformsoftware.security-reminder.server.routes
  (:require
   [dev.freeformsoftware.security-reminder.server.route-utils :as server.route-utils]
   [dev.freeformsoftware.security-reminder.server.websocket :as websocket]
   [dev.freeformsoftware.security-reminder.server.auth-middleware :as auth-middleware]
   [dev.freeformsoftware.security-reminder.auth.jwt :as jwt]
   [dev.freeformsoftware.security-reminder.ui.pages :as ui.pages]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

(defn admin-routes
  [conf]
  (ui.pages/page-routes conf))

(defn noauth-routes
  [conf]
  {"GET /health"      (fn [_request]
                        {:status  200
                         :headers {"Content-Type" "application/json"}
                         :body    "{\"status\":\"ok\"}"})
   "GET /favicon.ico" (constantly {:status 404
                                    :body   nil})
   ;; HTMX endpoint for the time demo
   "GET /pages/home/time" (fn [_request]
                            {:status  200
                             :headers {"Content-Type" "text/html"}
                             :body    (str "<span class='font-mono'>"
                                          (java.time.LocalDateTime/now)
                                          "</span>")})})

(defn prod-routes
  [conf]
  (server.route-utils/wrap-routes
   (partial auth-middleware/wrap-admin-auth conf)
   (admin-routes conf)))

(defn dev-routes
  [conf]
  {"GET /dev/reload-ws" websocket/reload-handler
   "GET /dev/login"     (fn [_]
                          (let [admin-jwt (jwt/create-jwt
                                           {:secret   (:jwt-secret conf)
                                            :audience (:management-portal-url-base conf)}
                                           {:role :admin
                                            :user "dev-admin"}
                                           :duration-hours
                                           24)]
                            (resp/redirect (str "/?jwt=" admin-jwt))))
   "GET /dev/logout"    (fn [_]
                          (-> (resp/redirect "/")
                              (resp/set-cookie "jwt" "" {:max-age 0 :path "/"})))})

(defn create-routes
  [{:keys [env] :as conf}]
  (let [routes
        (server.route-utils/merge-routes
         (server.route-utils/wrap-routes
          (partial auth-middleware/wrap-jwt-auth conf)
          (prod-routes conf))
         (noauth-routes conf)
         (when (= env :dev) (dev-routes conf)))]
    routes))

^:clj-reload/keep
(defonce !create-routes (atom nil))
(reset! !create-routes create-routes)
