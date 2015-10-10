(ns ring.middleware.test.transit
  (:use ring.middleware.transit
        clojure.test
        ring.util.io))

(deftest test-transit-body
  (let [handler (wrap-transit-body identity)]
    (testing "xml body"
      (let [request  {:content-type "application/xml"
                      :body (string-input-stream "<xml></xml>")}
            response (handler request)]
        (is (= "<xml></xml>") (slurp (:body response)))))

    (testing "transit body"
      (let [request  {:content-type "application/transit+json; charset=UTF-8"
                      :body (string-input-stream "[\"^ \",\"baz\",\"qüüx\"]")}
            response (handler request)]
        (is (= {"baz" "qüüx"} (:body response)))))

    (testing "malformed json"
      (let [request {:content-type "application/transit+json; charset=UTF-8"
                     :body (string-input-stream "[\"^ \",\"foo\",\"bar\"")}]
        (is (= (handler request)
               {:status  400
                :headers {"Content-Type" "text/plain"}
                :body    "Malformed Transit in request body."})))))

  (let [handler (wrap-transit-body identity {:keywords? true})]
    (testing "keyword keys"
      (let [request  {:content-type "application/transit+json"
                      :body (string-input-stream "[\"^ \",\"foo\",\"bar\"]")}
            response (handler request)]
        (is (= {:foo "bar"} (:body response))))))

  (testing "custom malformed transit"
    (let [malformed {:status 400
                     :headers {"Content-Type" "text/html"}
                     :body "<b>Your Transit is malformed!</b>"}
          handler (wrap-transit-body identity {:malformed-response malformed})
          request {:content-type "application/transit+json"
                   :body (string-input-stream "{\"foo\": \"bar\"")}]
      (is (= (handler request) malformed))))

  (testing "custom malformed handler"
    (let [handler (wrap-transit-body identity {:malformed-response-fn
                                               (fn [ex req _]
                                                 {:body      "Oh no!"
                                                  :exception ex
                                                  :request   req})})
          request {:content-type "application/transit+json"
                   :body (ring.util.io/string-input-stream "{\"foo\": \"bar\"")}
          result (handler request)]
      (is (instance? java.lang.Throwable (:exception result)))
      (is (= "Oh no!" (:body result)))
      (is (= request (:request result)))))

  (testing "idempotence of `wrap-transit-body'"
    (let [handler (-> (wrap-transit-body identity {:keywords? false})
                      (wrap-transit-body {:keywords? true}))
          request {:content-type "application/transit+json"
                   :body (string-input-stream "[\"^ \",\"foo\",\"bar\"]")}
          response (handler request)]
      (is (= {:foo "bar"} (:body response))))))

(deftest test-transit-params
  (let [handler  (wrap-transit-params identity)]
    (testing "xml body"
      (let [request  {:content-type "application/xml"
                      :body (string-input-stream "<xml></xml>")
                      :params {"id" 3}}
            response (handler request)]
        (is (= "<xml></xml>") (slurp (:body response)))
        (is (= {"id" 3} (:params response)))
        (is (nil? (:transit-params response)))))

    (testing "transit body"
      (let [request  {:content-type "application/transit+json; charset=UTF-8"
                      :body (string-input-stream "[\"^ \",\"foo\",\"bar\"]")
                      :params {"id" 3}}
            response (handler request)]
        (is (= {"id" 3, "foo" "bar"} (:params response)))
        (is (= {"foo" "bar"} (:transit-params response)))))

    (testing "array transit body"
      (let [request  {:content-type "application/transit+json; charset=UTF-8"
                      :body (string-input-stream "[\"foo\"]")
                      :params {"id" 3}}
            response (handler request)]
        (is (= {"id" 3} (:params response)))))

    (testing "malformed transit"
      (let [request {:content-type "application/transit+json; charset=UTF-8"
                     :body (string-input-stream "[\"^ \",\"foo\",\"bar\"")}]
        (is (= (handler request)
               {:status  400
                :headers {"Content-Type" "text/plain"}
                :body    "Malformed Transit in request body."}))))

    (testing "custom malformed json"
      (let [malformed {:status 400
                       :headers {"Content-Type" "text/html"}
                       :body "<b>Your Transit is wrong!</b>"}
            handler (wrap-transit-params identity {:malformed-response malformed})
            request {:content-type "application/transit+json"
                     :body (string-input-stream "[\"^ \",\"foo\",\"bar\"")}]
        (is (= (handler request) malformed))))))

(deftest test-json-response
  (testing "map body"
    (let [handler  (constantly {:status 200 :headers {} :body {:foo "qüüx"}})
          response ((wrap-transit-response handler) {})]
      (is (= (get-in response [:headers "Content-Type"]) "application/transit+json; charset=utf-8"))
      (is (= (:body response) "[\"^ \",\"~:foo\",\"qüüx\"]"))))

  (testing "string body"
    (let [handler  (constantly {:status 200 :headers {} :body "qüüx"})
          response ((wrap-transit-response handler) {})]
      (is (= (:headers response) {}))
      (is (= (:body response) "qüüx"))))

  (testing "vector body"
    (let [handler  (constantly {:status 200 :headers {} :body [:foo :bar]})
          response ((wrap-transit-response handler) {})]
      (is (= (get-in response [:headers "Content-Type"]) "application/transit+json; charset=utf-8"))
      (is (= (:body response) "[\"~:foo\",\"~:bar\"]"))))

  (testing "list body"
    (let [handler  (constantly {:status 200 :headers {} :body '(:foo :bar)})
          response ((wrap-transit-response handler) {})]
      (is (= (get-in response [:headers "Content-Type"]) "application/transit+json; charset=utf-8"))
      (is (= (:body response) "[\"~#list\",[\"~:foo\",\"~:bar\"]]"))))

  (testing "set body"
    (let [handler  (constantly {:status 200 :headers {} :body #{:foo :bar}})
          response ((wrap-transit-response handler) {})]
      (is (= (get-in response [:headers "Content-Type"]) "application/transit+json; charset=utf-8"))
      (is (or (= (:body response) "[\"~#set\",[\"~:foo\",\"~:bar\"]]")
              (= (:body response) "[\"~#set\",[\"~:bar\",\"~:foo\"]]")))))

  (testing "don’t overwrite Content-Type if already set"
    (let [handler  (constantly {:status 200 :headers {"Content-Type" "application/transit+json; some-param=some-value"} :body {:foo "bar"}})
          response ((wrap-transit-response handler) {})]
      (is (= (get-in response [:headers "Content-Type"]) "application/transit+json; some-param=some-value"))
      (is (= (:body response) "[\"^ \",\"~:foo\",\"bar\"]"))))

  (testing "leaves responses with other Content-Types unchanged"
    (let [handler  (constantly {:status 200 :headers {"Content-Type" "application/json"}
                                :body "{\"foo\": \"bar\"}"})
          response ((wrap-transit-response handler) {})]
      (is (= (get-in response [:headers "Content-Type"]) "application/json"))
      (is (= (:body response) "{\"foo\": \"bar\"}")))))
