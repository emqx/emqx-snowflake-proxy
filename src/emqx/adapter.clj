(ns emqx.adapter
  (:require
   [cats.monad.either :refer [left right]]
   [schema.core :as s]))

(s/defschema UpsertChannelWire
  {:chan-name s/Str
   :database s/Str
   :schema s/Str
   :table s/Str
   :on-error (s/enum :continue :abort)})

(defn upsert-channel-in
  [{:keys [:json-params :path-params]}]
  (let [{:keys [:chan-name]} path-params
        {:keys [:database :schema :table :on_error]} json-params
        on-error (keyword on_error)
        chan-params {:chan-name chan-name
                     :database database
                     :schema schema
                     :table table
                     :on-error on-error}
        errors (s/check UpsertChannelWire chan-params)]
    (if errors
      (left errors)
      (right chan-params))))

(s/defschema JsonVal
  (s/maybe
   (s/conditional
    string? s/Str
    number? s/Num
    boolean? s/Bool
    map? {s/Str (s/recursive #'JsonVal)}
    vector? [(s/recursive #'JsonVal)])))

(s/defschema InsertRowsWire
  ;; TODO: should use more lax value types???
  {:rows [(s/conditional
           (every-pred map? not-empty) {s/Str JsonVal})]})

(defn insert-rows-in
  [{:keys [:json-params]}]
  (let [rows (get json-params "rows")
        insert-rows-params {:rows rows}
        errors (s/check InsertRowsWire insert-rows-params)]
    (if errors
      (left errors)
      (right insert-rows-params))))
