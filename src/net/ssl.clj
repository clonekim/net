(ns net.ssl
  "Clojure glue code to interact with the horrible JVM SSL code"
  (:require [net.ty.channel  :as chan]
            [net.ty.pipeline :refer [*channel*]]
            [clojure.java.io :refer [input-stream]])
  (:import java.util.Base64
           java.security.KeyStore
           java.security.KeyFactory
           java.security.PrivateKey
           java.security.cert.X509Certificate
           java.security.cert.CertificateFactory
           java.security.spec.PKCS8EncodedKeySpec
           java.io.ByteArrayInputStream
           io.netty.channel.ChannelHandler
           io.netty.channel.Channel
           io.netty.handler.ssl.SslContext
           io.netty.handler.ssl.SslContextBuilder
           io.netty.handler.ssl.ClientAuth
           io.netty.handler.ssl.util.InsecureTrustManagerFactory))

(def ^:dynamic *storage*
  "Help net decide how to treat input. The default value
   of `:guess` will treat string input as paths under 256
   chars - a common value for **PATH_MAX** - and inlined
   cert data above that.

   A value of `:data` will always assume inlined certs,
   and a value of `:file` will always assume paths."
  :guess)

(defn cert-string
  "Convert input to certificate bytes"
  [input]
  (cond
    (and (map? input) (:path input))
    (some-> input :path slurp)

    (map? input)
    (:data input)

    (not (string? input))
    (throw (ex-info "cannot convert input to cert string" {}))

    (= :file *storage*)
    (slurp input)

    (= :data *storage*)
    input

    ;; Assume `:guess` for storage
    (< (count input) 256)
    (slurp input)

    :else
    input))

(defn ^"[B" cert-bytes
  "Get certificate bytes out of an input."
  [input]
  (if (instance? (Class/forName "[B") input)
    input
    (.getBytes ^String (cert-string input))))

(defn ^X509Certificate s->cert
  "Generate an X509 from a given source."
  [^CertificateFactory factory input]
  (.generateCertificate ^CertificateFactory factory
                        (ByteArrayInputStream. (cert-bytes input))))

(defn ^PrivateKey s->pkey
  "When reading private keys, we unfortunately have to
  read PKCS8 encoded keys, short of pulling-in bouncy castle :-(
  Since these keys are usually DER encoded, they're unconvienent to
  have laying around in strings. We resort to base64 encoded DER here."
  [^KeyFactory factory input]
  (let [bytes (.encode (Base64/getEncoder) (cert-bytes input))
        kspec (PKCS8EncodedKeySpec. bytes)]
    (.generatePrivate factory kspec)))

(defn ^"[Ljava.security.cert.X509Certificate;" ->chain
  "Get a certificate chain out of several certificate specs"
  [^CertificateFactory fact cert-spec]
  (if (sequential? cert-spec)
    (into-array X509Certificate (map (partial s->cert fact) cert-spec))
    (into-array X509Certificate [(s->cert fact cert-spec)])))

(defn client-context
  "Build an SSL client context for netty"
  [{:keys [bundle password cert pkey authority storage insecure]}]
  (let [storage (or storage :guess)
        builder (SslContextBuilder/forClient)]
    (.sslProvider builder io.netty.handler.ssl.SslProvider/OPENSSL)
    (binding [*storage* storage]
      (when (and cert pkey authority)
        (let [cert-fact (CertificateFactory/getInstance "X.509")
              key-fact  (KeyFactory/getInstance "RSA")]
          (let [cert      (s->cert cert-fact cert)
                authority (s->cert cert-fact authority)
                pkey      (s->pkey key-fact pkey)
                chain     ^"[Ljava.security.cert.X509Certificate;" (into-array X509Certificate [cert authority])]
            (.keyManager builder ^PrivateKey pkey chain)
            (.trustManager builder chain))))
      (when insecure
        (.trustManager builder InsecureTrustManagerFactory/INSTANCE))
      (when (and bundle password)
        (let [keystore (KeyStore/getInstance "pkcs12")]
          (with-open [stream (input-stream bundle)]
            (.load keystore stream (char-array password)))
          (let [alias (first (enumeration-seq (.aliases keystore)))
                k     (.getKey keystore alias (char-array password))
                chain (.getCertificateChain keystore alias)]
            (.keyManager builder ^PrivateKey k ^"[Ljava.security.cert.X509Certificate;" (into-array X509Certificate (seq chain)))
            (.trustManager builder ^"[Ljava.security.cert.X509Certificate;" (into-array X509Certificate (seq chain))))))
      (.build builder))))

(defn server-context
  "Build an SSL client context for netty"
  [{:keys [pkey password cert auth-mode ca-cert ciphers
           cache-size session-timeout storage]}]
  (binding [*storage* (or storage :guess)]
    (let [fact     (CertificateFactory/getInstance "X.509")
          certs    ^"[Ljava.security.cert.X509Certificate;" (->chain fact cert)
          key-fact (KeyFactory/getInstance "RSA")
          pk       (s->pkey key-fact pkey)
          builder  (if (string? password)
                     ^SslContextBuilder (SslContextBuilder/forServer pk ^String password certs)
                     ^SslContextBuilder (SslContextBuilder/forServer pk certs))]
      (when ciphers
        (.ciphers builder ciphers))
      (when ca-cert
        (.trustManager builder
                       ^"[Ljava.security.cert.X509Certificate;"
                       (into-array X509Certificate (->chain fact ca-cert))))
      (when cache-size
        (.sessionCacheSize builder (long cache-size)))
      (when session-timeout
        (.sessionTimeout builder (long session-timeout)))
      (when auth-mode
        (.clientAuth builder (case auth-mode
                               :auth-mode-optional ClientAuth/OPTIONAL
                               :auth-mode-require  ClientAuth/REQUIRE
                               :auth-mode-none     ClientAuth/NONE
                               (throw (ex-info "invalid client auth mode" {})))))
      (.build ^SslContextBuilder builder))))

(defn ^ChannelHandler new-handler
  "Create a new SSL handler from an SslContext"
  ([^SslContext ctx ^Channel channel]
   (.newHandler ctx (.alloc channel)))
  ([^SslContext ctx ^Channel channel ^String host port]
   (.newHandler ctx (.alloc channel) host (int port))))

(defn ^clojure.lang.IFn handler-fn
  "Build a handler function to be used in netty pipelines out of an SSL context.
   Will yield a 1-arity function of a context and a 3-arity function of a
   context, a host, and a port which will add a handler to the context."
  [^SslContext ctx]
  (fn ^ChannelHandler make-handler []
    (new-handler ctx *channel*)))
