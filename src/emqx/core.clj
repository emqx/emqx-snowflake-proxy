(ns emqx.core
  (:require
   [emqx.channel :as chan]
   [emqx.http :as server]
   [taoensso.timbre :as log]))

(defn -main
  [& args]
  (prn "args" args)
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
  (chan/start-client)
  (server/start))
