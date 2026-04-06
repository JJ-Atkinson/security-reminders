(ns dev.freeformsoftware.security-reminder.push.encryption
  "RFC 8291 Message Encryption for Web Push.
   Implements ECDH key agreement + HKDF + AES-128-GCM payload encryption."
  (:require
   [dev.freeformsoftware.security-reminder.push.vapid :as vapid])
  (:import
   [java.security KeyPairGenerator SecureRandom]
   [java.security.spec ECGenParameterSpec]
   [javax.crypto Cipher KeyAgreement Mac]
   [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; HKDF-SHA256 (RFC 5869)
;; =============================================================================

(defn- hmac-sha256
  ^bytes [^bytes key-bytes ^bytes data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key-bytes "HmacSHA256"))
    (.doFinal mac data)))

(defn- hkdf-extract
  "HKDF-Extract: PRK = HMAC-Hash(salt, IKM)"
  ^bytes [^bytes salt ^bytes ikm]
  (hmac-sha256 salt ikm))

(defn- hkdf-expand
  "HKDF-Expand: OKM = T(1) truncated to length bytes.
   info is the context/application-specific info string."
  ^bytes [^bytes prk ^bytes info ^long length]
  (let [;; T(1) = HMAC-Hash(PRK, info || 0x01)
        input (byte-array (+ (alength info) 1))]
    (System/arraycopy info 0 input 0 (alength info))
    (aset-byte input (alength info) (byte 1))
    (let [t1 (hmac-sha256 prk input)]
      (java.util.Arrays/copyOf t1 (int length)))))

;; =============================================================================
;; RFC 8291 info string builders
;; =============================================================================

(defn- build-info
  "Build the info parameter for HKDF as per RFC 8188 / RFC 8291.
   Format: \"Content-Encoding: <type>\\0\""
  ^bytes [^String content-type]
  (let [prefix (.getBytes (str "Content-Encoding: " content-type "\0") "UTF-8")]
    prefix))

(defn- build-key-info
  "Build the key info for IKM derivation per RFC 8291.
   Format: \"WebPush: info\\0\" || client-pub (65) || server-pub (65)"
  ^bytes [^bytes client-pub ^bytes server-pub]
  (let [prefix (.getBytes "WebPush: info\0" "UTF-8")
        result (byte-array (+ (alength prefix) 65 65))]
    (System/arraycopy prefix 0 result 0 (alength prefix))
    (System/arraycopy client-pub 0 result (alength prefix) 65)
    (System/arraycopy server-pub 0 result (+ (alength prefix) 65) 65)
    result))

;; =============================================================================
;; Encryption
;; =============================================================================

(defn encrypt-payload
  "Encrypt a push notification payload per RFC 8291 (aes128gcm).
   client-p256dh-b64: base64url-encoded 65-byte client public key
   client-auth-b64:   base64url-encoded 16-byte client auth secret
   plaintext:         payload bytes to encrypt
   Returns a byte array in aes128gcm format ready for the HTTP body."
  ^bytes [^String client-p256dh-b64 ^String client-auth-b64 ^bytes plaintext]
  (let [;; Decode client keys
        client-pub-bytes (vapid/b64url-decode client-p256dh-b64)
        client-auth      (vapid/b64url-decode client-auth-b64)
        client-pub-key   (vapid/public-key-bytes->ec-public-key client-pub-bytes)

        ;; Generate ephemeral server key pair
        kpg (doto (KeyPairGenerator/getInstance "EC")
              (.initialize (ECGenParameterSpec. "secp256r1") (SecureRandom.)))
        server-kp   (.generateKeyPair kpg)
        server-priv (.getPrivate server-kp)
        server-pub  (.getPublic server-kp)
        ;; Get the 65-byte uncompressed point for the server public key
        server-pub-bytes (let [point (.getW ^java.security.interfaces.ECPublicKey server-pub)
                               x-bytes (.toByteArray (.getAffineX point))
                               y-bytes (.toByteArray (.getAffineY point))
                               result  (byte-array 65)]
                           (aset-byte result 0 (byte 0x04))
                           ;; Pad/trim to exactly 32 bytes each
                           (let [x-len (min (alength x-bytes) 32)
                                 x-off (max 0 (- (alength x-bytes) 32))]
                             (System/arraycopy x-bytes x-off result (+ 1 (- 32 x-len)) x-len))
                           (let [y-len (min (alength y-bytes) 32)
                                 y-off (max 0 (- (alength y-bytes) 32))]
                             (System/arraycopy y-bytes y-off result (+ 33 (- 32 y-len)) y-len))
                           result)

        ;; ECDH key agreement
        ka (doto (KeyAgreement/getInstance "ECDH")
             (.init server-priv)
             (.doPhase client-pub-key true))
        shared-secret (.generateSecret ka)

        ;; Generate 16-byte salt
        salt (let [bs (byte-array 16)]
               (.nextBytes (SecureRandom.) bs)
               bs)

        ;; RFC 8291 key derivation
        ;; Step 1: IKM = HKDF(auth, shared_secret, "WebPush: info\0" || client_pub || server_pub)
        key-info  (build-key-info client-pub-bytes server-pub-bytes)
        prk-key   (hkdf-extract client-auth shared-secret)
        ikm       (hkdf-expand prk-key key-info 32)

        ;; Step 2: Derive CEK and nonce from IKM + salt
        prk       (hkdf-extract salt ikm)
        cek       (hkdf-expand prk (build-info "aes128gcm") 16)
        nonce     (hkdf-expand prk (build-info "nonce") 12)

        ;; Pad plaintext: add a delimiter byte (0x02) then zero-pad
        ;; For simplicity, just add the delimiter with no extra padding
        padded    (let [result (byte-array (+ (alength plaintext) 1))]
                    (System/arraycopy plaintext 0 result 0 (alength plaintext))
                    (aset-byte result (alength plaintext) (byte 2))
                    result)

        ;; AES-128-GCM encrypt
        cipher    (doto (Cipher/getInstance "AES/GCM/NoPadding")
                    (.init Cipher/ENCRYPT_MODE
                           (SecretKeySpec. cek "AES")
                           (GCMParameterSpec. 128 nonce)))
        encrypted (.doFinal cipher padded)

        ;; Build aes128gcm content-coding header:
        ;; salt (16) || rs (4, big-endian uint32) || idlen (1) || keyid (65 = server pub)
        ;; followed by encrypted record
        rs        (+ (alength plaintext) 1 16) ;; record size = padded plaintext + tag
        header    (byte-array 86) ;; 16 + 4 + 1 + 65
        ]
    ;; Write header
    (System/arraycopy salt 0 header 0 16)
    ;; rs as big-endian uint32
    (aset-byte header 16 (byte (bit-and (bit-shift-right rs 24) 0xFF)))
    (aset-byte header 17 (byte (bit-and (bit-shift-right rs 16) 0xFF)))
    (aset-byte header 18 (byte (bit-and (bit-shift-right rs 8) 0xFF)))
    (aset-byte header 19 (byte (bit-and rs 0xFF)))
    ;; idlen = 65
    (aset-byte header 20 (byte 65))
    ;; keyid = server public key
    (System/arraycopy server-pub-bytes 0 header 21 65)

    ;; Concatenate header + encrypted data
    (let [result (byte-array (+ 86 (alength encrypted)))]
      (System/arraycopy header 0 result 0 86)
      (System/arraycopy encrypted 0 result 86 (alength encrypted))
      result)))
