(ns emqx.core
  (:require
   [emqx.channel :as chan]
   [emqx.config :as config]
   [emqx.http :as http-server]
   [taoensso.timbre :as log]))

(defn -main
  [& args]
  (log/info :msg ::init-args :args args)
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable
    (fn []
      (log/info :msg ::closing-channels)
      (doseq [[chan-name _] @chan/channels]
        (log/info :msg ::closing-channel :channel-name chan-name)
        (chan/ensure-chan-deleted chan-name))
      (log/info :msg ::stopping-client)
      (chan/stop-client)
      (log/info :msg ::shutting-down-agents)
      (shutdown-agents))))
  (let [{:keys [:app-name :client :channels :server]} (config/get-config!)]
    ;; Should the client name be unique?
    (chan/start-client client)
    (doseq [chan-params channels]
      (chan/ensure-streaming-agent app-name chan-params))
    (http-server/start server)))
