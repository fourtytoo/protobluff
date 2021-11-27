(ns protobluff.core-test
  "This module requires the availability of a free TCPI/IP port so that
  the gRPC can be tested on a real socket connection.  You can do so
  through the `PROTOBLUFF_TEST_PORT` envvar or the `*default-test-port*`
  dynamic variable."
  (:require [clojure.test :refer :all]
            [protobluff.core :as pb])
  (:import [protobluff.test Hello GreeterGrpc
            Hello$GreetRequest Hello$GreetResponse
            Recursive$A Recursive$B1 Recursive$B2
            Recursive$C1 Recursive$C2 Recursive$C3]))


(deftest trivial-test
  (is (= 1 (pb/from-protobuf 1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *default-test-port*
  "In normal circumstances you would want to define an environment
  variable PROTOBLUFF_TEST_PORT with the port the regression tests
  should be using.  If that is not defined, then this is the default.
  You can rebind it if you like.  See also `server-port`."
  1337)

(defn server-port []
  "Return the port number to be used by the these unit tests."
  (if-let [s (System/getenv "PROTOBLUFF_TEST_PORT")]
    (Integer/parseUnsignedInt s)
    *default-test-port*))

;; Define the client side:
(pb/def-blocking-stub-maker Greeter)
(pb/wrap-stub Greeter)

;; Define the server side:
(pb/def-service Greeter
  (greet [s]
    [{:message (str "Hello " (:name s) "!")}]))

(comment
  (def foo (start-server 1337 [(make-greeter-service)] :interceptors [(fn [x] (prn x) nil)]))
  (.shutdown foo))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def auth-token "foo")

(def ^:dynamic auth-interceptors nil)

(defn greet [& args]
  (pb/with-server [server (server-port) [greeter]
                   :interceptors auth-interceptors]
    (pb/with-stub [stub (make-greeter-stub "localhost" (server-port))]
      (apply greeter-greet stub args))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest greeter-test
  (is (= "Hello world!" (:message (greet {:name "world"}))))
  (is (= "Hello to you!" (:message (greet {:name "to you"})))))


(def cc (pb/simple-call-credentials :auth (constantly auth-token)))
(def wrong-cc (pb/simple-call-credentials :auth (constantly (str "wrong-" auth-token))))

(deftest authenticated-greeter-test
  (binding [auth-interceptors [(pb/make-simple-server-auth-interceptor :auth (partial = auth-token))]]
    (is (= "Hello world!" (:message (greet {:name "world"} :call-credentials cc))))
    (is (= "Hello to you!" (:message (greet {:name "to you"} :call-credentials cc))))
    (is (thrown? Exception (greet {:name "to you"})))
    (is (thrown? Exception (greet {:name "to you"} :call-credentials wrong-cc)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(greet {:name "world"})

#_(greet {:name "world"}
          :call-credentials (simple-call-credentials :auth (constantly auth-token)))

#_(binding [auth-interceptors [(make-simple-server-auth-interceptor :auth (partial = auth-token))]]
    (greet {:name "world"}
           :call-credentials cc))

#_(binding [auth-interceptors [(make-simple-server-auth-interceptor :auth (partial = auth-token))]]
    (greet {:name "world"}
           :call-credentials wrong-cc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(deftest conversion
  (let [cvtrq (pb/make-converter-to-protobuf Hello$GreetRequest)
        cvtre (pb/make-converter-to-protobuf Hello$GreetResponse)]
    (is (= {:name "foo"}
           (pb/from-protobuf (cvtrq {:name "foo"}))))
    (is (= {:message "bar"}
           (pb/from-protobuf (cvtre {:message "bar"}))))))

#_(pb/from-protobuf ((pb/make-converter-to-protobuf Recursive$B1) {:b2 nil}))

(deftest recursive-conversion
  (let [cvta (pb/make-converter-to-protobuf Recursive$A)
        cvtb1 (pb/make-converter-to-protobuf Recursive$B1)
        cvtb2 (pb/make-converter-to-protobuf Recursive$B2)
        cvtc1 (pb/make-converter-to-protobuf Recursive$C1)
        cvtc2 (pb/make-converter-to-protobuf Recursive$C2)
        cvtc3 (pb/make-converter-to-protobuf Recursive$C3)]
    (testing "one hop recursive"
      (is (= {:a {}}
             (pb/from-protobuf (cvta {:a nil}))))
      (is (= {:a {:a {}}}
             (pb/from-protobuf (cvta {:a {:a nil}}))))
      (is (= {:a {:a {:a {}}}}
             (pb/from-protobuf (cvta {:a {:a {:a nil}}})))))
    (testing "two hops recursive"
      (is (= {:b2 {:b1 {:b2 {}}}}
             (pb/from-protobuf (cvtb1 {:b2 {:b1 {:b2 nil}}}))))
      (is (= {:b1 {:b2 {:b1 {:b2 {}}}}}
             (pb/from-protobuf (cvtb2 {:b1 {:b2 {:b1 {:b2 nil}}}})))))
    (testing "three hops recursive"
      (is (= {:c2 {:c3 {:c1 {:c2 {}}}}}
             (pb/from-protobuf (cvtc1 {:c2 {:c3 {:c1 {:c2 nil}}}}))))
      (is (= {:c3 {:c1 {:c2 {:c3 {}}}}}
             (pb/from-protobuf (cvtc2 {:c3 {:c1 {:c2 {:c3 nil}}}}))))
      (is (= {:c1 {:c2 {:c3 {:c1 {}}}}}
             (pb/from-protobuf (cvtc3 {:c1 {:c2 {:c3 {:c1 nil}}}})))))))

(deftest json-print
  (let [cv (pb/make-converter-to-protobuf Hello$GreetRequest)]
    (is (= "{\n  \"name\": \"foo\"\n}"
             (pb/protobuf->json (cv {:name "foo"}) :omit-whitespace false)))
    (is (= "{\"name\":\"foo\"}"
           (pb/protobuf->json (cv {:name "foo"}) :omit-whitespace true)))
    (binding [pb/json-omit-whitespace true]
      (is (= "{\"name\":\"foo\"}"
               (pb/protobuf->json (cv {:name "foo"})))))))
