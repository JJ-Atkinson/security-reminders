(ns dev.freeformsoftware.security-reminder.server.core
  (:require
   [clj-simple-router.core :as router]
   [integrant.core :as ig]
   [ring.adapter.jetty :as ring-jetty]
   [dev.freeformsoftware.security-reminder.server.routes :as routes]
   [dev.freeformsoftware.security-reminder.server.auth-middleware :as auth-middleware]
   [taoensso.telemere :as tel]
   [ring.middleware.defaults :as ring-defaults]
   [nextjournal.garden-email :as garden-email]
   [clojure.string :as str])
  (:import
    [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

(defn wrap-tap>-exception
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (tel/error! "Handler error" e)
        (tap> e)
        (throw e)))))

(defn wrap-static-cache
  "Add Cache-Control headers for static assets."
  [handler]
  (fn [request]
    (let [response (handler request)
          uri      (:uri request)]
      (if (and response
               (or (str/starts-with? uri "/js/")
                   (str/starts-with? uri "/css/")
                   (str/starts-with? uri "/icons/")
                   (= uri "/sw.js")))
        (assoc-in response [:headers "Cache-Control"] "public, max-age=31536000, immutable")
        response))))

(defn wrap-robots-header
  "Add X-Robots-Tag and Referrer-Policy headers to all responses."
  [handler]
  (fn [request]
    (when-let [response (handler request)]
      (-> response
          (assoc-in [:headers "X-Robots-Tag"] "noindex")
          (assoc-in [:headers "Referrer-Policy"] "no-referrer")))))

(defn handler
  [{:keys [env time-layer] :as config}]
  (let [config        (assoc config :engine (:engine time-layer))
        ;; Memoize the router constructor so route compilation happens once per
        ;; handler lifetime (fresh closure created on each resume).
        create-router (memoize router/router)]
    (-> (fn [req]
          (let [router (wrap-tap>-exception (create-router (@routes/!create-routes config)))]
            (try
              (or (router req)
                  (auth-middleware/unauthorized-response config))
              (catch Exception e
                (tel/error! "Handler error" e)
                {:status  500
                 :headers {"Content-Type" "text/plain"}
                 :body    "Internal Server Error"}))))
        (auth-middleware/wrap-token-auth (:engine time-layer))
        (ring-defaults/wrap-defaults
         (-> (case env
                   (:dev :test)
                   ring-defaults/site-defaults
                   (:prod)
                   ring-defaults/secure-site-defaults)
             (assoc-in [:security :anti-forgery] false)
             (assoc-in [:security :frame-options] :deny)
             (assoc-in [:security :ssl-redirect] false)))
        (garden-email/wrap-with-email)
        (wrap-static-cache)
        (wrap-robots-header))))

(defmethod ig/init-key ::server
  [_ {:keys [jetty] :as config}]
  (let [options  (merge {:port  3001
                         :host  "0.0.0.0"
                         :join? false}
                        jetty)
        !handler (atom (handler config))]
    (tel/log! {:level :info :data {:options options}} "Starting server")
    {:!handler !handler
     :server   (ring-jetty/run-jetty (fn [req] (@!handler req))
                                     options)}))

(defmethod ig/suspend-key! ::server
  [_ _])

(defmethod ig/resume-key ::server
  [_ config _ {:keys [!handler] :as inst}]
  (reset! !handler (handler config))
  inst)

(defmethod ig/halt-key! ::server
  [_ {:keys [server]}]
  (.stop ^Server server))
