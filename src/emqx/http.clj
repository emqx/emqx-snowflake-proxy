(ns emqx.http
  (:require
   [camel-snake-kebab.core :as csk]
   [cats.monad.either :as e]
   [clojure.string :as str]
   [emqx.adapter :as adapter]
   [emqx.channel :as chan]
   [io.pedestal.http :as http]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.content-negotiation :as conneg]
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :refer [interceptor]]
   [io.pedestal.interceptor.chain :as chain]
   [io.pedestal.log :as log]))

;; FIXME: terminate interceptor chain if content type is not json...
(def content-neg-interceptor
  (let [supported-types ["application/json"]]
    (conneg/negotiate-content supported-types)))

(def chan-interceptor
  {:name ::channel-interceptor
   :enter
   (fn [context]
     (let [chan-name (get-in context [:request :path-params :chan-name])
           chan-agent (get @chan/channels chan-name)]
       (if chan-agent
         (assoc-in context [:request :chan] (get @chan/channels chan-name))
         (-> context
             (assoc :response {:status 404 :body {:error "channel not found"}})
             chain/terminate))))})

;; TODO: transform recursively?
(def json-key-interceptor
  {:name ::json-key-interceptor
   :leave
   (fn [context]
     (if (map? (get-in context [:response :body]))
       (update-in context [:response :body] update-keys csk/->snake_case)
       context))})

;; TODO: make the whole interceptor chain use m/->=
(defn adapter-interceptor
  [adapter-fn]
  {:name ::adapter-interceptor
   :enter
   (fn [context]
     (let [request (:request context)
           res (adapter-fn request)]
       (e/branch res
                 (fn [errors]
                   (-> context
                       (assoc :response {:status 400 :body {:error "bad input"
                                                            ;; TODO: better formatting...
                                                            :details (str errors)}})
                       chain/terminate))
                 (fn [parsed-params]
                   (assoc-in context [:request :input-params] parsed-params)))))})

(def log-request
  "Logs all http requests with response time."
  {:name ::log-request
   :enter (fn [context]
            (assoc-in context [:request :start-time] (System/currentTimeMillis)))
   :leave (fn [context]
            (let [{:keys [uri start-time request-method]} (:request context)
                  finish (System/currentTimeMillis)
                  total (- finish start-time)]
              (log/info :msg "request completed"
                        :method (str/upper-case (name request-method))
                        :uri uri
                        :status (:status (:response context))
                        :response-time total)
              context))})

#_(def upsert-channel
    {:name ::upsert-channel
     :enter
     (fn [context]
       (let [{:keys [:request]} context
             {:keys [:json-params :path-params]} request
             {:keys [:chan-name]} path-params
             {:keys [:database :schema :table :on_error]} json-params
             on-error (keyword on_error)
             existing-chan-info (chan/chan-info chan-name)
             chan-params {:chan-name chan-name
                          :database database
                          :schema schema
                          :table table
                          :on-error on-error}
             chan-info (if (and existing-chan-info
                                (chan/equal-params? existing-chan-info chan-params))
                         existing-chan-info
                         (do
                           (chan/ensure-chan-deleted chan-name)
                           (chan/ensure-streaming-agent chan-params)
                           (chan/chan-info chan-name)))]
         (assoc context :response {:status 200 :body chan-info})))})

(def get-chan
  {:name ::get-channel
   :enter
   (fn [context]
     (let [chan-name (get-in context [:request :path-params :chan-name])
           chan-info (chan/chan-info chan-name)]
       (if chan-info
         (assoc context :response {:status 200 :body chan-info})
         (assoc context :response {:status 404}))))})

#_(def delete-chan
    {:name ::delete-channel
     :enter
     (fn [context]
       (let [chan-name (get-in context [:request :path-params :chan-name])]
         (chan/ensure-chan-deleted chan-name)
         (assoc context :response {:status 204})))})

;; TODO: validate input before this interceptor; pedestal-api???
(def insert-rows
  {:name ::insert-rows
   :enter
   (fn [context]
     (let [{:keys [:json-params :chan]} (:request context)
           rows (get json-params "rows" [])
           result (chan/insert-rows-sync chan rows)
           success? (-> result :errors empty?)]
       (if success?
         (assoc context :response {:status 204})
         (let [status (case (:vendor-code result)
                        "0013" 500
                        400)]
           (assoc context :response {:status status :body result})))))})

(def routes
  (route/expand-routes
   #{["/channels/:chan-name/insert"
      :post [content-neg-interceptor
             (body-params/body-params
              (body-params/default-parser-map
               :json-options {:key-fn str}))
             http/json-body
             chan-interceptor
             json-key-interceptor
             (adapter-interceptor adapter/insert-rows-in)
             insert-rows]]

     #_["/channels/:chan-name"
        :post [content-neg-interceptor
               (body-params/body-params)
               http/json-body
               json-key-interceptor
               (adapter-interceptor adapter/upsert-channel-in)
               upsert-channel]]

     #_["/channels/:chan-name"
        :delete [content-neg-interceptor
                 (body-params/body-params)
                 http/json-body
                 json-key-interceptor
                 delete-chan]]

     ["/channels/:chan-name"
      :get [content-neg-interceptor
            (body-params/body-params)
            http/json-body
            chan-interceptor
            json-key-interceptor
            get-chan]]}))

(defn create-server
  [params]
  (http/create-server
   {::http/routes routes
    ::http/request-logger (interceptor log-request)
    ::http/type :jetty
    ;; FIXME: for repl
    ::http/join? false
    ::http/host "0.0.0.0"
    ::http/port (get params :port 9099)}))

(defonce server (atom nil))

(defn start
  []
  (reset! server (http/start (create-server {}))))

(defn restart
  []
  (swap! server http/stop)
  (reset! server (start)))
