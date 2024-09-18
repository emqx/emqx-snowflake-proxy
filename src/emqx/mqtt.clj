(ns emqx.mqtt
  (:require
   [cats.monad.either :as either]
   [clojurewerkz.machine-head.client :as mh]
   [emqx.adapter :as adapter]
   [emqx.channel :as chan]
   [emqx.log :as log]))

(defonce clients (atom {}))

(defn subscribe
  [client chan-name topic qos]
  (let [chan-agent (get @chan/channels chan-name)]
    (mh/subscribe
     client
     {topic qos}
     (fn [^String _topic _metadata ^bytes payload]
       (either/branch
        (adapter/channel-data-in payload)
        (fn [error]
          (log/warn! ::bad-input :reason error))
        (fn [decoded-row]
          (chan/insert-rows-sync chan-agent [decoded-row])))))))

(defn try-start-client
  [chan-name {:keys [:uri :clientid :topic :qos :opts]}]
  (try
    (either/right
     (mh/connect
      uri
      {:client-id clientid
       :opts opts
       :on-connect-complete (fn [client reconnect? server-uri]
                              (log/info! ::connection-established
                                         :reconnect? reconnect? :server-uri server-uri)
                              (subscribe client chan-name topic qos))
       :on-connection-lost (fn [reason]
                             (log/warn! ::conection-lost :reason reason))}))
    (catch Exception e
      (either/left e))))

(defn start-client
  [{:keys [:chan-name :mqtt]}]
  (let [mqtt-config (adapter/mqtt-client-config mqtt)]
    (either/branch
     (try-start-client chan-name mqtt-config)
     (fn [e]
       (log/error!
        ::failed-to-start-client
        :chan-name chan-name
        :reason e)
       (throw e))
     (fn [conn]
       (swap! clients assoc chan-name conn)
       conn))))

(defn -stop-client
  [client]
  (mh/disconnect-and-close client))

(defn stop-client
  [chan-name]
  (when-let [client (get @clients chan-name)]
    (-stop-client client)
    (swap! clients dissoc chan-name)))
