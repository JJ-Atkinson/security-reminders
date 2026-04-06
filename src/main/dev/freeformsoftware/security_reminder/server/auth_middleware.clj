(ns dev.freeformsoftware.security-reminder.server.auth-middleware
  "URL-path token authentication middleware.
   Every user-facing route is prefixed with /{sec-token}/...
   The middleware extracts the token, looks up the person, and rewrites the URI."
  (:require
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [dev.freeformsoftware.security-reminder.ui.html-fragments :as ui.frag]
   [hiccup2.core :as h]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn unauthorized-response
  [conf]
  {:status  401
   :headers {"Content-Type" "text/html"
             "X-Robots-Tag" "noindex"}
   :body    (str (h/html (ui.frag/unauthorized-landing-page conf)))})

(defn- forbidden-response
  [conf]
  {:status  403
   :headers {"Content-Type" "text/html"
             "X-Robots-Tag" "noindex"}
   :body    (str (h/html (ui.frag/unauthorized-landing-page conf)))})

(defn- extract-token-and-path
  "Extract the sec-token (first path segment) and remaining path from a URI.
   Returns [token remaining-path] or nil if URI has no token segment."
  [uri]
  (let [;; Remove leading slash
        path (if (str/starts-with? uri "/") (subs uri 1) uri)
        ;; Split on first slash
        idx  (str/index-of path "/")]
    (if idx
      [(subs path 0 idx) (str "/" (subs path (inc idx)))]
      ;; No trailing path — token only
      (when (seq path)
        [path "/"]))))

(defn wrap-token-auth
  "Middleware that extracts the sec-token from the first path segment,
   looks up the person via the engine, and attaches :person and :sec-token
   to the request. Rewrites :uri to strip the token prefix.

   If the first segment is not a valid token, passes through unchanged
   (allows no-auth routes like /health, /dev/* to work)."
  [handler eng]
  (fn [request]
    (if-let [[token remaining-path] (extract-token-and-path (:uri request))]
      (if-let [person (engine/lookup-person-by-token eng token)]
        (handler (assoc request
                        :person    person
                        :sec-token token
                        :uri       remaining-path))
        ;; Not a valid token — pass through unchanged (may be a no-auth route)
        (handler request))
      (handler request))))

(defn wrap-require-person
  "Middleware that requires :person on the request (set by wrap-token-auth).
   Returns 401 if missing."
  [conf handler]
  (fn [request]
    (if (:person request)
      (handler request)
      (unauthorized-response conf))))

(defn wrap-admin-auth
  "Middleware that checks if the authenticated person is an admin.
   Requires wrap-token-auth to be applied first."
  [conf handler]
  (fn [request]
    (if (:admin? (:person request))
      (handler request)
      (forbidden-response conf))))
