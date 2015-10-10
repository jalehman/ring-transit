(ns ring.middleware.transit
  (:import (java.io ByteArrayOutputStream))
  (:require [ring.util.response :refer :all]
            [plumbing.core :refer [keywordize-map]]
            [cognitect.transit :as transit]))

;; ================================================================================
;; Helpers

(defn- write [x t charset opts]
  (let [baos (ByteArrayOutputStream.)
        w    (transit/writer baos t opts)
        _    (transit/write w x)
        ret  (.toString baos charset)]
    (.reset baos)
    ret))

(defn- transit-response? [response]
  (if-let [type (get-header response "Content-Type")]
    (not (empty? (re-find #"^application/transit\+(json|msgpack)" type)))))

(defn- transit-request? [request]
  (if-let [type (:content-type request)]
    (let [mtch (re-find #"^application/transit\+(json|msgpack)" type)]
      [(not (empty? mtch)) (keyword (second mtch))])))

(defn- read-transit [request {:keys [keywords? opts]}]
  (let [[res t] (transit-request? request)]
    (if res
      (if-let [body (:body request)]
        (let [rdr (transit/reader body t opts)
              f   (if keywords? keywordize-map identity)]
          (try
            [true (f (transit/read rdr))]
            (catch Exception ex
              [false ex])))))))

(def ^{:doc "The default response to return when a Transit request is malformed."}
  default-malformed-response
  {:status  400
   :headers {"Content-Type" "text/plain"}
   :body    "Malformed Transit in request body."})

(defn- transit-response-options
  [options]
  {:encoding (get options :encoding :json)
   :charset  (get options :charset "utf-8")
   :opts     (:opts options)})

(defn- transit-handler
  [handler f sentinel-key {:keys [malformed-response malformed-response-fn]
                           :or {malformed-response default-malformed-response}
                           :as options}]
  (fn [request]
    (if (get request sentinel-key)
      (handler request)
      (if-let [[valid? transit-or-ex] (read-transit request options)]
        (if valid?
          (handler (assoc (f request transit-or-ex) sentinel-key true))
          (if malformed-response-fn
            (malformed-response-fn transit-or-ex request handler)
            malformed-response))
        (handler request)))))

;; ================================================================================
;; API

(defn wrap-transit-body
  "Middleware that parses the body of Transit request maps, and replaces the :body
  key with the parsed data structure. Requests without a Transit content type are
  unaffected.

  Accepts the following options:

  :keywords?               - true if the keys of maps should be turned into keywords
  :opts                    - a map of options to be passed to the transit reader
  :malformed-response      - a response map to return when the Transit is malformed
  :malformed-response-fn   - a custom error handler that gets called when the body is malformed
                             transit. Will be called with three parameters: [exception request handler]"
  {:arglists '([handler] [handler options])}
  [handler & [{:as options}]]
  (letfn [(-assoc-body [req res]
            (assoc req :body res))]
    (transit-handler handler -assoc-body ::wrap-transit-body options)))

(defn wrap-transit-params
  "Middleware that parses the body of Transit requests into a map of parameters,
  which are added to the request map on the :transit-params and :params keys.

  Accepts the following options:

  :opts                  - a map of options to be passed to the transit reader
  :malformed-response    - a response map to return when the Transit is malformed
  :malformed-response-fn - a custom error handler that gets called when the body is malformed
                             transit. Will be called with three parameters: [exception request handler]

  Use the standard Ring middleware, ring.middleware.keyword-params, to
  convert the parameters into keywords."
  {:arglists '([handler] [handler options])}
  [handler & [{:as options}]]
  (letfn [(-assoc-transit-params [req res]
            (let [request (assoc req :transit-params res)]
              (if (map? res)
                (update-in request [:params] merge res)
                request)))]
    (transit-handler handler -assoc-transit-params ::wrap-transit-params options)))

(defn transit-response
  "Create a transit response based on provided options. Will leave responses
  with non-transit Content Type will be returned unaltered."
  [response request {:keys [opts encoding charset]}]
  (letfn [(-transit-response []
            (update-in response [:body] write encoding charset opts))]
    (if (coll? (:body response))
      (if (contains? (:headers response) "Content-Type")
        (if (transit-response? response)
          (-transit-response) ;; content type is already set to transit, just update the body.
          response) ;; There's a content type set and it's not transit -- return it as-is.
        (content-type (-transit-response) (format "application/transit+%s; charset=%s" (name encoding) charset)))
      response)))

(defn wrap-transit-response
  "Middleware that converts responses with a map or a vector for a body into a
  Transit response.

  Accepts the following options:

  :encoding - one of #{:json :json-verbose :msgpack}
  :opts     - a map of options to be passed to the transit writer"
  {:arglists '([handler] [handler options])}
  [handler & [{:as options}]]
  (let [{:keys [encoding] :as opts} (transit-response-options options)]
    (assert (#{:json :json-verbose :msgpack} encoding)
            "The encoding must be one of #{:json :json-verbose :msgpack}.")
    (fn [request]
      (-> (handler request)
          (transit-response request opts)))))
