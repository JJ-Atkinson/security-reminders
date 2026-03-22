(ns dev.freeformsoftware.security-reminder.auth.jwt
  (:require
   [buddy.sign.jwt :as jwt]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def internal-config
  {:issuer  "dev.freeformsoftware.security-reminder"
   :subject "dev.freeformsoftware.security-reminder.ui-portal"})

;; =============================================================================
;; Core JWT Functions
;; =============================================================================

(defn create-jwt
  "Creates an internal JWT token for application use.

  conf: map containing :secret and :audience (typically management-portal-url-base)
  claims: map of JWT claims (will be merged with iss/sub/aud/iat/exp)
  opts: optional map with :duration-hours (default 24)"
  [{:keys [secret audience]} claims & {:keys [duration-hours] :or {duration-hours 24}}]
  (let [now     (quot (System/currentTimeMillis) 1000)
        exp     (+ now (* duration-hours 60 60))
        payload (merge
                 {:iss (:issuer internal-config)
                  :sub (:subject internal-config)
                  :aud audience
                  :iat now
                  :exp exp}
                 claims)]
    (jwt/sign payload secret {:header {:typ "JWT"}})))

(defn unsign-jwt
  "Verifies and decodes an internal JWT token.

  conf: map containing :secret and :expected-audience
  token: JWT string to verify

  Returns the decoded claims if valid, throws exception otherwise."
  [{:keys [secret expected-audience]} token]
  (let [claims (jwt/unsign token secret)]
    ;; Verify issuer matches internal config
    (when-not (= (:iss claims) (:issuer internal-config))
      (throw (ex-info "Invalid issuer"
                      {:expected (:issuer internal-config)
                       :actual   (:iss claims)})))
    ;; Verify subject matches internal config
    (when-not (= (:sub claims) (:subject internal-config))
      (throw (ex-info "Invalid subject"
                      {:expected (:subject internal-config)
                       :actual   (:sub claims)})))
    ;; Verify audience matches expected value
    (when expected-audience
      (when-not (= (:aud claims) expected-audience)
        (throw (ex-info "Invalid audience"
                        {:expected expected-audience
                         :actual   (:aud claims)}))))
    claims))
