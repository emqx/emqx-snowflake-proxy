(ns emqx.adapter
  (:require
   [camel-snake-kebab.core :as csk]
   [cats.monad.either :refer [left right]]
   [cheshire.core :as json])
  (:import
   [java.util Properties]
   [net.snowflake.ingest.utils HttpUtil]))

(defn channel-client-proxy-config->props
  [{:keys [:host :port :user :password]}]
  (let [props (Properties.)]
    (doto props
      (.setProperty HttpUtil/USE_PROXY "true")
      (.setProperty HttpUtil/PROXY_HOST host)
      (.setProperty HttpUtil/PROXY_PORT (str port)))
    (when user
      (.setProperty props HttpUtil/HTTP_PROXY_USER user))
    (when password
      (.setProperty props HttpUtil/HTTP_PROXY_PASSWORD password))
    props))

(defn channel-client-config->props
  [params]
  (let [keys [:user :url :private-key :port :host :scheme]
        props (Properties.)
        _ (doseq [k keys]
            (.setProperty props (csk/->snake_case_string k) (str (get params k))))
        proxy-props (when-some [proxy-cfg (:proxy params)]
                      (channel-client-proxy-config->props proxy-cfg))]
    [props proxy-props]))

(defn mqtt-client-config
  [{:keys [:host :port :clientid :topic :qos :clean-start :username :password]}]
  {:uri (str "tcp://" host ":" port)
   :clientid clientid
   :topic topic
   :qos qos
   :opts (cond-> {:auto-reconnect true}
           username (assoc :username username)
           password (assoc :password password)
           (boolean? clean-start) (assoc :clean-session clean-start))})

(defn channel-data-in
  "Parses the incoming payload and expects it to be a JSON encoded object"
  [^bytes payload]
  (try
    (let [decoded (json/parse-string (String. payload "UTF-8"))]
      (if (map? decoded)
        (right decoded)
        (left "bad input: should be a single JSON object")))
    (catch Exception e
      (left (str "bad input: failure parsing json: " (.getMessage e))))))
