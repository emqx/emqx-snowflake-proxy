(ns emqx.log
  (:require
   [taoensso.telemere :as t]))

(defmacro log!
  [level msg & data]
  (let [data (apply hash-map data)
        opts (cond-> {:level level}
               (seq data) (assoc :data data))]
    `(t/log! ~opts ~msg)))

(defmacro debug!
  [msg & data]
  `(log! :debug ~msg ~@data))

(defmacro info!
  [msg & data]
  `(log! :info ~msg ~@data))

(defmacro warn!
  [msg & data]
  `(log! :warn ~msg ~@data))

(defmacro error!
  [msg & data]
  `(log! :error ~msg ~@data))
