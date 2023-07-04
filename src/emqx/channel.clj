(ns emqx.channel
  (:require
   [cheshire.core :as json]
   [taoensso.timbre :as log])
  (:import
   [java.util Properties]
   [net.snowflake.ingest.streaming
    SnowflakeStreamingIngestClientFactory
    OpenChannelRequest
    OpenChannelRequest$OnErrorOption]
   [net.snowflake.ingest.utils
    SFException]))

(defonce client (atom nil))

(defonce channels (atom {}))

(defonce channel-manager (agent {}))

(defn start-client
  []
  (let [params (json/parse-string (slurp "profile.json"))
        props (Properties.)
        _ (doseq [[^String k ^String v] params]
            (.setProperty props k (str v)))
        c (.. (SnowflakeStreamingIngestClientFactory/builder "my_client")
              (setProperties props)
              (build))]
    (reset! client c)))

(defn stop-client
  []
  (.close @client)
  (reset! client nil))

(defn- initial-row-seq-num
  [last-offset-token]
  (if (nil? last-offset-token)
    0
    (try
      (Integer/parseInt last-offset-token)
      (catch Exception _
        0))))

(defn- start-streaming-agent
  "Note: we use an `agent` here instead of just an `atom` because the
  function provided to `swap!` _may_ be executed multiple times, so it
  should be side-effect free.  We then use an agent to serialize the
  side-effects."
  [{:keys [:chan-name :database :schema :table :on-error] :as chan-params}]
  ;; see https://github.com/snowflakedb/snowflake-ingest-java/blob/64182caf0af959271f4249e4bef9203e2a1f6d8d/profile_streaming.json.example
  (let [on-error (case on-error
                   :continue OpenChannelRequest$OnErrorOption/CONTINUE
                   :abort OpenChannelRequest$OnErrorOption/ABORT)
        chan-req (.. (OpenChannelRequest/builder chan-name)
                     (setDBName database)
                     (setSchemaName schema)
                     (setTableName table)
                     (setOnErrorOption on-error)
                     (build))
        ;; TODO: use row sequencer for :n???
        chan (. @client openChannel chan-req)
        start-n (initial-row-seq-num (.getLatestCommittedOffsetToken chan))
        chan-agent (agent {:chan chan :params chan-params :n start-n})]
    chan-agent))

(defn- do-ensure-streaming-agent
  [state {:keys [:chan-name] :as params} reply-promise]
  (let [chan-agent (@channels chan-name)]
    (if chan-agent
      ;; already created by a concurrent call
      (deliver reply-promise chan-agent)
      (let [chan-agent (start-streaming-agent params)]
        (swap! channels assoc chan-name chan-agent)
        (deliver reply-promise chan-agent)))
    state))

(defn ensure-streaming-agent
  [params]
  (let [reply-promise (promise)]
    (send-off channel-manager do-ensure-streaming-agent params reply-promise)
    @reply-promise))

;; note: offset can be null when the channel is new.
;; note: this can throw if the channel becomes invalid and needs to be reopened...
(defn get-latest-committed-offset
  [chan]
  (try
    (.getLatestCommittedOffsetToken chan)
    (catch SFException e
      (log/error :msg ::get-latest-commited-offset-exception
                 :exception (.getMessage e)
                 :cause (.getCause e)
                 :vendor-code (.getVendorCode e))
      (.printStackTrace e)
      nil)
    (catch Exception e
      (log/error :msg ::get-latest-commited-offset-exception
                 :exception (.getMessage e))
      nil)))

;; TODO: return partial success count???
(defn- do-insert-rows
  [chan rows offset-token]
  (try
    (let [response (. chan insertRows rows offset-token)]
      (if (.hasErrors response)
        (let [errors (->> response
                          .getInsertErrors
                          (map (fn [e]
                                 {:row-index (.getRowIndex e)
                                  :message (.getMessage e)})))]
          (log/error :msg ::insert-row-errors :errors errors :offset-token offset-token)
          {:errors errors})
        (do
          (log/debug :msg ::insert-rows-success :offset-token offset-token)
          {:errors nil})))
    (catch SFException e
      (log/error :msg ::insert-row-exception
                 :exception (.getMessage e)
                 :cause (.getCause e)
                 :vendor-code (.getVendorCode e)
                 :offset-token offset-token)
      (.printStackTrace e)
      {:errors [(.getMessage e)]
       :vendor-code (.getVendorCode e)})
    (catch Exception e
      (log/error :msg ::insert-row-exception
                 :exception (.getMessage e)
                 :offset-token offset-token)
      {:errors [(.getMessage e)]})))

(defn- insert-rows
  [{:keys [:chan :n] :as state} rows reply-promise]
  (log/debug :msg ::insert-rows-enter :rows rows)
  (let [row-count (count rows)
        offset-token (str (+ n row-count))
        response (do-insert-rows chan rows offset-token)]
    (deliver reply-promise response)
    (update state :n + row-count)))

(defn insert-rows-sync
  [chan-agent rows]
  (let [reply-promise (promise)]
    (send-off chan-agent insert-rows rows reply-promise)
    @reply-promise))

(defn chan-info
  [chan-name]
  (when-let [chan-agent (@channels chan-name)]
    (let [{:keys [:chan :params]} @chan-agent]
      (assoc params :last-offset (get-latest-committed-offset chan)))))

;; called inside channel manager
(defn- do-ensure_chan-deleted
  [state chan-name reply-promise]
  (if-let [channel-agent (@channels chan-name)]
    (let [{:keys [:chan]} @channel-agent]
      (.close chan)
      (log/info :msg ::channel-closed
                :chan-name chan-name
                :closed? (.isClosed chan)
                :valid? (.isValid chan))
      (swap! channels dissoc chan-name)
      (deliver reply-promise true))
    (deliver reply-promise true))
  state)

(defn ensure-chan-deleted
  [chan-name]
  (let [reply-promise (promise)]
    (send-off channel-manager do-ensure_chan-deleted chan-name reply-promise)
    @reply-promise))

(defn equal-params?
  [info1 info2]
  (let [[info1 info2] (mapv #(select-keys % [:chan-name
                                             :database
                                             :schema
                                             :table
                                             :on-error])
                            [info1 info2])]
    (= info1 info2)))
