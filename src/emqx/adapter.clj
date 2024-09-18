(ns emqx.adapter
  (:require
   [cats.monad.either :refer [left right]]
   [cheshire.core :as json]))

(defn mqtt-client-config
  [{:keys [:host :port :clientid :topic :qos :username :password]}]
  {:uri (str "tcp://" host ":" port)
   :clientid clientid
   :topic topic
   :qos qos
   :opts (cond-> {:auto-reconnect true}
           username (assoc :username username)
           password (assoc :password password))})

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
