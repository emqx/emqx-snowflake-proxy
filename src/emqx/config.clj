(ns emqx.config
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [schema.coerce :as coerce]
   [schema.core :as s]))

(s/defschema ClientConfig
  {:client-name s/Str
   :user s/Str
   :url s/Str
   :private-key s/Str
   :port s/Num
   :host s/Str
   :scheme s/Str})

(s/defschema MQTTConfig
  {:host s/Str
   :port s/Num
   :clientid s/Str
   :topic s/Str
   :qos (s/enum 0 1 2)
   (s/optional-key :username) s/Str
   (s/optional-key :password) s/Str})

(s/defschema ChannelConfig
  {:chan-name s/Str
   :database s/Str
   :schema s/Str
   :table s/Str
   :on-error (s/enum :continue :abort)
   :mqtt MQTTConfig})

(s/defschema EMQXConfig
  {:client ClientConfig
   :channels [ChannelConfig]})

(defn- duplicates
  [coll]
  (->> coll
       frequencies
       (filter (fn [[_ n]] (> n 1)))
       (map (fn [[x _]] x))))

(defn- validate-unique!
  [{:keys [:channels]} ks what]
  (let [all-things (map (fn [chan-config]
                          (get-in chan-config ks))
                        channels)
        duplicate-things (duplicates all-things)]
    (when (seq duplicate-things)
      (throw (RuntimeException.
              (format "Duplicate %s found: %s" what (pr-str duplicate-things)))))))

(defn- validate-unique-clientids!
  [channels]
  (validate-unique! channels [:mqtt :clientid] "clientids"))

(defn- validate-unique-chan-names!
  [channels]
  (validate-unique! channels [:chan-name] "channel names"))

(defn get-config!
  "Get the configurations for startup.
  That config is read from a `config.edn` file in the app's working
  directory."
  []
  (let [params (with-open [r (io/reader "config.edn")]
                 (edn/read (java.io.PushbackReader. r)))
        coercer (coerce/coercer! EMQXConfig coerce/keyword-enum-matcher)
        config (coercer params)
        _ (validate-unique-clientids! config)
        _ (validate-unique-chan-names! config)
        app-name (or (System/getenv "APP_NAME")
                     (str (java.util.UUID/randomUUID)))]
    (assoc config :app-name app-name)))
