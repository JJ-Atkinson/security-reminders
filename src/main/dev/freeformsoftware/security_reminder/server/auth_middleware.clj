(ns dev.freeformsoftware.security-reminder.server.auth-middleware
  (:require
   [dev.freeformsoftware.security-reminder.auth.jwt :as jwt]
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [hiccup2.core :as h]
   [ring.util.response :as resp]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- get-jwt-from-request
  "Extract JWT from either cookie or query parameter"
  [request]
  (or
   ;; Try query parameter first
   (get-in request [:params :jwt])
   ;; Then try cookie
   (get-in request [:cookies "jwt" :value])))

(defn- set-jwt-cookie
  "Add Set-Cookie header to response with JWT"
  [response jwt-token]
  (resp/set-cookie response
                   "jwt"
                   jwt-token
                   {:http-only true
                    :path      "/"
                    :max-age   (* 24 60 60)})) ; 24 hours

(defn- redirect-without-jwt-param
  "Redirect to the same URL but without the jwt query parameter"
  [request jwt-token]
  (let [uri       (:uri request)
        query     (:query-string request)
        new-query (when query
                    (str/join "&"
                              (remove #(str/starts-with? % "jwt=")
                                      (str/split query #"&"))))
        new-uri   (if (and new-query (not (str/blank? new-query)))
                    (str uri "?" new-query)
                    uri)
        redirect  (resp/redirect new-uri)]
    (set-jwt-cookie redirect jwt-token)))

(defn unauthorized-response
  [conf]
  {:status  401
   :headers {"Content-Type" "text/html"}
   :body    (str (h/html (ui.frag/no-jwt-landing-page conf)))})

(defn- forbidden-response
  [conf]
  {:status  403
   :headers {"Content-Type" "text/html"}
   :body    (str (h/html (ui.frag/no-jwt-landing-page conf)))})

(defn wrap-jwt-auth
  "Middleware that checks for JWT authentication via cookie or query param.

  If JWT is in query param:
    - Validates JWT
    - Sets cookie
    - Redirects without query param

  If JWT is in cookie:
    - Validates JWT
    - Adds :jwt-claims to request
    - Passes to handler

  If no valid JWT:
    - Returns unauthorized page"
  [conf handler]
  (fn [request]
    (if-let [jwt-token (get-jwt-from-request request)]
      (let [claims (try (jwt/unsign-jwt {:secret            (:jwt-secret conf)
                                         :expected-audience (:management-portal-url-base conf)}
                                        jwt-token)
                        (catch Exception e ::no-jwt-claims!))]
        (cond
          (= claims ::no-jwt-claims!)
          (unauthorized-response conf)

          ;; If JWT came from query param, redirect to clean URL
          (get-in request [:params :jwt])
          (redirect-without-jwt-param request jwt-token)

          ;; Otherwise, add claims to request and continue
          :else
          (handler (assoc request :jwt-claims claims))))
      ;; No JWT found - show unauthorized page
      (unauthorized-response conf))))

(defn wrap-admin-auth
  "Middleware that checks if the user has admin role.
  Requires wrap-jwt-auth to be applied first.

  Returns forbidden page if user is not an admin."
  [conf handler]
  (fn [request]
    (if (= "admin" (get-in request [:jwt-claims :role]))
      (handler request)
      (forbidden-response conf))))
