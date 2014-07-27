(ns ring.middleware.transit
  (:import (java.io ByteArrayOutputStream))
  (:require [ring.util.response :refer :all]
            [plumbing.core :refer [keywordize-map]]
            [cognitect.transit :as transit]))

(defn- write [x t]
  (let [baos (ByteArrayOutputStream.)
        w    (transit/writer baos t)
        _    (transit/write w x)
        ret  (.toString baos)]
    (.reset baos)
    ret))

(write {:foo "bar"} :json)

(defn- transit-request? [request]
  (if-let [type (:content-type request)]
    (let [mtch (re-find #"^application/transit\+(json|msgpack)" type)]
      [(not (empty? mtch)) (keyword (second mtch))])))

(defn- read-transit [request & [keywords?]]
  (let [[res t] (transit-request? request)]
    (if res
      (if-let [body (:body request)]
        (let [rdr (transit/reader body t)
              f   (if keywords? keywordize-map identity)]
          (try
            [true (f (transit/read rdr))]
            (catch Exception ex
              [false nil])))))))

(def ^{:doc "The default response to return when a Transit request is malformed."}
  default-malformed-response
  {:status  400
   :headers {"Content-Type" "text/plain"}
   :body    "Malformed Transit in request body."})

(defn wrap-transit-body
  "Middleware that parses the body of Transit request maps, and replaces the :body
  key with the parsed data structure. Requests without a Transit content type are
  unaffected.

  Accepts the following options:

  :keywords?          - true if the keys of maps should be turned into keywords
  :malformed-response - a response map to return when the JSON is malformed"
  {:arglists '([handler] [handler options])}
  [handler & [{:keys [keywords? malformed-response]
               :or {malformed-response default-malformed-response}}]]
  (fn [request]
    (if-let [[valid? transit] (read-transit request keywords?)]
      (if valid?
        (handler (assoc request :body transit))
        malformed-response)
      (handler request))))

(defn- assoc-transit-params [request transit]
  (if (map? transit)
    (-> request
        (assoc :transit-params transit)
        (update-in [:params] merge transit))
    request))

(defn wrap-transit-params
  "Middleware that parses the body of Transit requests into a map of parameters,
  which are added to the request map on the :transit-params and :params keys.

  Accepts the following options:

  :malformed-response - a response map to return when the JSON is malformed

  Use the standard Ring middleware, ring.middleware.keyword-params, to
  convert the parameters into keywords."
  {:arglists '([handler] [handler options])}
  [handler & [{:keys [malformed-response]
               :or {malformed-response default-malformed-response}}]]
  (fn [request]
    (if-let [[valid? transit] (read-transit request)]
      (if valid?
        (handler (assoc-transit-params request transit))
        malformed-response)
      (handler request))))

(defn wrap-transit-response
  "Middleware that converts responses with a map or a vector for a body into a
  Transit response.

  Accepts the following options:

  :encoding - one of #{:json :msgpack}"
  [handler & [{:keys [encoding] :or {encoding :json}}]]
  (assert (#{:json :msgpack} encoding) "The encoding must be one of #{:json :msgpack}.")
  (fn [request]
    (let [response (handler request)]
      (if (coll? (:body response))
        (let [transit-response (update-in response [:body] write encoding)]
          (if (contains? (:headers response) "Content-Type")
            transit-response
            (content-type transit-response (format "application/transit+%s; charset=utf-8" (name encoding)))))
        response))))
