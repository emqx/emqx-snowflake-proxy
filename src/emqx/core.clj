(ns emqx.core
  (:require
   [emqx.channel :as chan]
   [emqx.config :as config]
   [emqx.mqtt :as mqtt]
   [emqx.log :as log])
  (:gen-class))

(defn- block-forever
  []
  (while true
    (Thread/sleep 60000)))

(defn- cleanup-hook
  []
  (log/info! ::closing-channels)
  (doseq [[chan-name _] @chan/channels]
    (log/info! ::closing-mqtt-client :channel-name chan-name)
    (mqtt/stop-client chan-name)
    (log/info! ::closing-channel :channel-name chan-name)
    (chan/ensure-chan-deleted chan-name))
  (log/info! ::stopping-client)
  (chan/stop-client)
  (log/info! ::shutting-down-agents)
  (shutdown-agents))

(defn -main
  [& args]
  (log/info! ::init-args :args args)
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable cleanup-hook))
  (let [{:keys [:app-name :client :channels]} (config/get-config!)]
    ;; Should the client name be unique?
    (chan/start-client client)
    (doseq [chan-params channels]
      (chan/ensure-streaming-agent app-name chan-params)
      (mqtt/start-client chan-params))
    (try
      (block-forever)
      (catch Exception e
        (log/error! ::main-fn-exception :reason e)
        (println (.getMessage e))
        (.printStackTrace e)
        (System/exit 1)))))
