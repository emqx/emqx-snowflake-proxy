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
   :scheme s/Str
   :role s/Str})

(s/defschema ChannelConfig
  {:chan-name s/Str
   :database s/Str
   :schema s/Str
   :table s/Str
   :on-error (s/enum :continue :abort)})

(s/defschema ServerConfig
  {:host s/Str
   :port s/Num})

(s/defschema EMQXConfig
  {:client ClientConfig
   :channels [ChannelConfig]
   :server ServerConfig})

(defn get-config!
  "Get the configurations for startup.
  That config is read from a `config.edn` file in the app's working
  directory."
  []
  (let [params (with-open [r (io/reader "config.edn")]
                 (edn/read (java.io.PushbackReader. r)))
        coercer (coerce/coercer! EMQXConfig coerce/keyword-enum-matcher)
        config (coercer params)
        app-name (or (System/getenv "APP_NAME")
                     (str (java.util.UUID/randomUUID)))]
    (assoc config :app-name app-name)))
