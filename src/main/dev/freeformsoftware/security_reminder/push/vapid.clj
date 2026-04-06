(ns dev.freeformsoftware.security-reminder.push.vapid
  "VAPID (Voluntary Application Server Identification) for Web Push.
   Handles JWT creation with ES256 signing and key loading."
  (:import
   [java.security KeyFactory Signature]
   [java.security.spec ECPrivateKeySpec ECPublicKeySpec ECPoint]
   [java.security.interfaces ECPrivateKey]
   [java.math BigInteger]
   [java.util Base64]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Base64url helpers
;; =============================================================================

(defn b64url-encode
  ^String [^bytes bs]
  (.encodeToString (Base64/getUrlEncoder) bs))

(defn b64url-encode-nopad
  ^String [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn b64url-decode
  ^bytes [^String s]
  (.decode (Base64/getUrlDecoder) s))

;; =============================================================================
;; Key loading
;; =============================================================================

(def ^:private ec-params
  "The P-256 (secp256r1) curve parameters, derived from a generated key."
  (let [kpg (java.security.KeyPairGenerator/getInstance "EC")]
    (.initialize kpg (java.security.spec.ECGenParameterSpec. "secp256r1"))
    (-> (.generateKeyPair kpg) .getPrivate (.getParams))))

(defn load-private-key
  "Load an EC private key from a base64url-encoded 32-byte raw scalar."
  ^ECPrivateKey [^String b64url-key]
  (let [key-bytes (b64url-decode b64url-key)
        s         (BigInteger. 1 key-bytes)
        spec      (ECPrivateKeySpec. s ec-params)
        kf        (KeyFactory/getInstance "EC")]
    (.generatePrivate kf spec)))

(defn load-public-key-bytes
  "Decode a base64url-encoded 65-byte uncompressed EC public key to bytes."
  ^bytes [^String b64url-key]
  (b64url-decode b64url-key))

(defn public-key-bytes->ec-public-key
  "Convert 65-byte uncompressed point to an ECPublicKey."
  [^bytes pub-bytes]
  (let [x (BigInteger. 1 (java.util.Arrays/copyOfRange pub-bytes 1 33))
        y (BigInteger. 1 (java.util.Arrays/copyOfRange pub-bytes 33 65))
        point (ECPoint. x y)
        spec  (ECPublicKeySpec. point ec-params)
        kf    (KeyFactory/getInstance "EC")]
    (.generatePublic kf spec)))

;; =============================================================================
;; DER to JWS signature conversion
;; =============================================================================

(defn- der-to-jws
  "Convert a DER-encoded ECDSA signature to the 64-byte R||S format used by JWS/JWT."
  ^bytes [^bytes der-sig]
  (let [result (byte-array 64)
        ;; Parse DER: 0x30 <len> 0x02 <r-len> <r-bytes> 0x02 <s-len> <s-bytes>
        r-len  (long (bit-and (aget der-sig 3) 0xFF))
        r-off  4
        s-off  (+ r-off r-len 2)
        s-len  (long (bit-and (aget der-sig (- s-off 1)) 0xFF))]
    ;; Copy R (skip leading zero if 33 bytes)
    (if (= r-len 33)
      (System/arraycopy der-sig (+ r-off 1) result 0 32)
      (System/arraycopy der-sig r-off result (- 32 r-len) r-len))
    ;; Copy S (skip leading zero if 33 bytes)
    (if (= s-len 33)
      (System/arraycopy der-sig (+ s-off 1) result 32 32)
      (System/arraycopy der-sig s-off result (+ 32 (- 32 s-len)) s-len))
    result))

;; =============================================================================
;; JWT creation
;; =============================================================================

(defn create-vapid-jwt
  "Create a signed VAPID JWT for the given audience (push service origin).
   Returns the compact JWT string (header.payload.signature)."
  ^String [^ECPrivateKey private-key ^String audience ^String subject ^long expire-seconds]
  (let [header    "{\"typ\":\"JWT\",\"alg\":\"ES256\"}"
        now       (quot (System/currentTimeMillis) 1000)
        payload   (str "{\"aud\":\"" audience
                       "\",\"exp\":" (+ now expire-seconds)
                       ",\"sub\":\"" subject "\"}")
        encode    (fn [^String s] (b64url-encode-nopad (.getBytes s "UTF-8")))
        signing-input (str (encode header) "." (encode payload))
        sig       (doto (Signature/getInstance "SHA256withECDSA")
                    (.initSign private-key)
                    (.update (.getBytes signing-input "UTF-8")))
        der-sig   (.sign sig)
        jws-sig   (der-to-jws der-sig)]
    (str signing-input "." (b64url-encode-nopad jws-sig))))

(defn vapid-authorization-header
  "Build the VAPID Authorization header value.
   Returns \"vapid t=<jwt>, k=<public-key-b64url>\"."
  ^String [^ECPrivateKey private-key ^String public-key-b64url
           ^String audience ^String subject]
  (let [jwt (create-vapid-jwt private-key audience subject 86400)]
    (str "vapid t=" jwt ", k=" public-key-b64url)))
