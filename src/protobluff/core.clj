(ns protobluff.core
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as s])
  (:import [java.lang.reflect Modifier]
           [io.grpc CallCredentials Context Contexts ManagedChannelBuilder
            Metadata Metadata$Key
            ServerBuilder ServerCall$Listener ServerInterceptor
            Status]
           [java.util.concurrent TimeUnit]
           [com.google.protobuf.util JsonFormat]))


(def kck
  #(csk/->kebab-case-keyword % :separator \_))

(def kcs
  #(csk/->kebab-case-symbol % :separator \_))

(defn class-methods [class]
  (.getMethods class))

(defn class-method* [class method]
  (->> (class-methods class)
       (filter #(= (str method) (.getName %)))))

(defn class-method [class method]
  (first (class-method* class method)))

(defn class-is-enum? [class]
  (.isEnum class))

(defn invoke-method [method obj & args]
  (.invoke method obj (into-array Object args)))

(defn invoke-static-method [class method & args]
  (clojure.lang.Reflector/invokeStaticMethod class method (into-array Object args)))

(defn int->enum [enum-type x]
  (invoke-static-method enum-type "forNumber" x))

(defn enum->map [klass]
  (->> (invoke-static-method klass "values")
       seq
       (map (juxt #(kck (.name %))
                  identity))
       (into {})))

(defn enum-keywords [klass]
  (keys (enum->map klass)))

(defn wrap-static-method [class method-name args]
  (let [method (.getMethod class method-name (into-array Class args))]
    (fn [& args]
      (.invoke method nil (to-array args)))))

(def byte-array-class (class (byte-array [])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *ignore-nils*
  "Don't set any protobuf instance variable if its value would be nil."
  true)

(defn keyword->enum [enum-type keyword]
  (let [name (s/upper-case (name keyword))]
    (or (->> (invoke-static-method enum-type "values")
             (filter (fn [ev]
                       (= name (s/upper-case (.name ev)))))
             first)
        (throw (ex-info "unknown keyword for enum type" {:type enum-type :keyword keyword
                                                         :legal-values (enum-keywords enum-type)})))))

(def builder-setters)

(defn- new-builder-method
  "Find in `class` the static method newBuilder().  That is, the version
  that doesn't require arguments.  Return a Method object."
  [class]
  (->> (class-method* class "newBuilder")
       (filter (fn [method]
                 (zero? (.getParameterCount method))))
       first))

(defn method-type [builder]
  (.getReturnType builder))

(defn make-converter-to-protobuf
  "Make a function that accepts a Clojure type (usually a map) and
  convert it to a PROTOBUF POJO of type `val-type`.  Return a function."
  ([val-type]
   (make-converter-to-protobuf val-type {}))
  ([val-type visited-types]
   (let [new-builder (new-builder-method val-type)]
     (cond new-builder
           (if-let [f (get visited-types val-type)]
             (fn [v]
               (@f v))
             (let [converter-place (volatile! nil)
                   setters (builder-setters (method-type new-builder) (assoc visited-types val-type converter-place))
                   converter (fn [v]
                               (let [b1 (.invoke new-builder val-type nil)]
                                 (try (-> (if (and *ignore-nils*
                                                   (nil? v))
                                            ;; avoid null pointer exceptions
                                            b1
                                            (do
                                              (doseq [[k v] v]
                                                (if-let [setter (get setters k)]
                                                  (setter b1 v)
                                                  (throw (ex-info "unknown member in class" {:key k :value v :type val-type :available-setters setters}))))
                                              b1))
                                          .build)
                                      (catch Exception e
                                        (throw (ex-info "in to-protobuf" {:builder b1 :type val-type :value v} e))))))]
               (vreset! converter-place converter)))

           (class-is-enum? val-type)
           (fn [v]
             (if (number? v)
               (int->enum val-type v)
               (keyword->enum val-type v)))

           (= String val-type)
           str

           (= Integer/TYPE val-type)
           int

           :else identity))))

(defn- builder-type? [type]
  (-> (.getSimpleName type)
      (= "Builder")))

(defn- setter-method? [method]
  (let [name (.getName method)
        pars (.getParameterTypes method)]
    (and (re-find #"^set[A-Z]" name)
         (and (= 1 (count pars))
              (not (builder-type? (first pars)))))))

(defn- adder-method? [method]
  (let [name (.getName method)
        pars (.getParameterTypes method)]
    (and (re-find #"^add[A-Z]" name)
         (and (= 1 (count pars))
              (not (builder-type? (first pars)))))))

(defn- putter-method? [method]
  (let [name (.getName method)
        pars (.getParameterTypes method)]
    (and (re-find #"^put[A-Z]" name)
         (and (= 2 (count pars))
              (not (builder-type? (first pars)))))))

(defn- method->key [method]
  (kck (subs (.getName method) 3)))

(defn builder-setters [builder-class visited-types]
  (->> (class-methods builder-class)
       (map (fn [method]
              (cond (setter-method? method)
                    (let [method-key (method->key method)
                          val-type (first (.getParameterTypes method))
                          to-protobuf (make-converter-to-protobuf val-type visited-types)]
                      [method-key
                       (fn [builder v]
                         (let [value (to-protobuf v)]
                           (try
                             (invoke-method method builder value)
                             (catch Exception e
                               (throw (ex-info "in setter method"
                                               {:setter method :builder builder :value value}
                                               e))))))])

                    (adder-method? method)
                    (let [method-key (method->key method)
                          val-type (first (.getParameterTypes method))
                          to-protobuf (make-converter-to-protobuf val-type visited-types)]
                      [method-key
                       (fn [builder v]
                         (->> (map (fn [e]
                                     (when (or (not *ignore-nils*)
                                               (some? e))
                                       (let [value (to-protobuf e)]
                                         (try
                                           (invoke-method method builder value)
                                           (catch Exception e
                                             (throw (ex-info "in array setter method"
                                                             {:setter method :builder builder :value value}
                                                             e)))))))
                                   v)
                              dorun))])

                    (putter-method? method)
                    (let [method-key (method->key method)
                          [key-type value-type] (map #(.getType %) (.getAnnotatedParameterTypes method))
                          key-to-protobuf (make-converter-to-protobuf key-type visited-types)
                          value-to-protobuf (make-converter-to-protobuf value-type visited-types)]
                      [method-key
                       (fn [builder v]
                         (->> (map (fn [[k v]]
                                     (when (or (not *ignore-nils*)
                                               (and (some? k) (some? v)))
                                       (let [k' (key-to-protobuf k)
                                             v' (value-to-protobuf v)]
                                         (try
                                           (invoke-method method builder k' v')
                                           (catch Exception e
                                             (throw (ex-info "in map putter method"
                                                             {:putter method :builder builder :key k' :value v'}
                                                             e)))))))
                                   v)
                              dorun))])

                    :else nil)))
       (remove nil?)
       (into {})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def service-class-suffix
  "The protobuf compiler tacks this suffix to the generated Java class.
  That is, if the proto file defines a Foo service, the generated Java
  class will be named FooGrpc. You should not change this under normal
  circumstances."
  "Grpc")

(def service-implementation-base-suffix
  "The protobuf compiler tacks this suffix to the generated Java class.
  You should not change it under normal circumstances."
  "ImplBase")

(def channel-termination-wait
  "How long to wait for a channel termination (in seconds) after a
  shutdown.  Under normal circumstances there is no need to change
  this."
  5)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn service-methods [service]
  (->> (if (class? service)
         service
         (class service))
       .getDeclaredMethods
       (filter #(Modifier/isPublic (.getModifiers %)))))

(defn- method-signature [method]
  [(.getAnnotatedReturnType method) (.getAnnotatedParameterTypes method)])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti from-protobuf
  "Convert a protobuf response to a Clojure value.  POJOs are converted
  to standard Clojure maps."
  class)

(defmethod from-protobuf com.google.protobuf.Descriptors$EnumValueDescriptor
  [response]
  (kck (.getName response)))

(defmethod from-protobuf com.google.protobuf.MessageOrBuilder
  [response]
  (->> (.getAllFields response)
       (map (fn [[field value]]
              [(kck (.getName field))
               (from-protobuf value)]))
       (into {})))

(defmethod from-protobuf java.util.Collection
  [response]
  (let [converted (map from-protobuf response)]
    (if (instance? com.google.protobuf.MapEntry (first response))
      ;; this is curious but inevitable at the moment -wcp03/09/19
      (->> (map (juxt :key :value) converted)
           (into {}))
      (vec converted))))

(defmethod from-protobuf :default
  [response]
  response)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn string-remove-suffix [string suffix]
  (if (s/ends-with? string suffix)
    (subs string 0 (- (count string) (count suffix)))
    string))

(defn service-class [service]
  (-> (name service)
      (str service-class-suffix)
      symbol
      resolve))

(defn- service-implementation-class [service]
  (let [sc (service-class service)]
    (-> (str (.getName sc) "$"
             (string-remove-suffix (.getSimpleName sc) service-class-suffix)
             service-implementation-base-suffix)
        symbol
        resolve)))

(defn service-stub-class
  "Return the stub class associated to `service`.  For internal purposes
  it doesn't matter what kind of stub is returned as long as its
  methods can be introspected."
  [service]
  (let [service-class (if (class? service)
                        service
                        (service-class service))
        stub-name (str (.getName service-class) "$"
                       (-> (.getSimpleName service-class)
                           (string-remove-suffix service-class-suffix))
                       "BlockingStub")]
    (Class/forName stub-name)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-metadata-key [key-name & [marshaller]]
  (Metadata$Key/of (name key-name) (or marshaller Metadata/ASCII_STRING_MARSHALLER)))

(def ^:private authentication-metadata-key
  (make-metadata-key :authentication))

(defn as-metadata-key [k]
  (if (instance? Metadata$Key k)
    k
    (make-metadata-key k)))

(defn simple-call-credentials
  "Instantiate a `CallCredential` object.  The object's
  `applyRequestMetadata` method will call `f` every time it needs to
  generate an authentication token.  The function `f` is called
  without arguments and should return a string. The `k` argument
  should be a metadata key to be used to pass the authentication
  token; it is either a Clojure keyword or a `Metadata$Key` instance."
  [k f]
  (let [k (as-metadata-key k)]
    (proxy [CallCredentials] []
      (applyRequestMetadata [req-info executor metadata-applier]
        (.execute executor (fn []
                             (try
                               (.apply metadata-applier
                                       (doto (Metadata.)
                                         (.put k (f))))
                               (catch Exception e
                                 (.fail metadata-applier
                                        (.withCause (Status/UNAUTHENTICATED) e)))))))
      (thisUsesUnstableApi []
        'yes-we-know))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- stub-method-definition [service method arg-types]
  (let [args (repeatedly (count arg-types) #(gensym "arg"))
        converters (repeatedly (count arg-types) #(gensym "to-protobuf"))
        stub (gensym "stub")
        cc (gensym "call-credentials")]
    `(let ~(vec (mapcat (fn [cv type]
                          `(~cv (make-converter-to-protobuf ~type)))
                        converters arg-types))
       (defn ~(kcs (str service "-" method))
         ~(vec (cons stub
                     (concat args
                             `(& {~cc :call-credentials}))))
         (from-protobuf
          (-> (if ~cc
                (.withCallCredentials ~stub ~cc)
                ~stub)
              (. ~(symbol method)
                 ~@(map (fn [cv arg]
                          `(~cv ~arg))
                        converters args))))))))

(defmacro wrap-stub
  "Expose the interface to a GRPC service stub with the automatic
  definition of the corresponding wrapper functions.  If, for
  instance, the service `Greeter` defines the methods `sayHello` and
  `sayGoodbye`, the functions `greeter-say-hello` and
  `greeter-say-goodbye` will be automatically defined for you.  The
  key argument :call-credentials should be a function returning an
  authentication token (a string).  See also `simple-call-credentials`."
  [service]
  (let [stub (service-stub-class service)
        service-name (-> (service-class service)
                         .getSimpleName
                         (string-remove-suffix service-class-suffix))
        expand-method (fn [[method arg-types]]
                        (stub-method-definition service-name method arg-types))]
    `(do ~@(->> (service-methods stub)
                (map (fn [method]
                       [(.getName method)
                        (vec (.getParameterTypes method))]))
                (map expand-method)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-interceptors [builder interceptors]
  (doseq [interceptor interceptors]
    (.intercept builder interceptor))
  builder)

(defn add-services [builder services]
  (doseq [service services]
    (.addService builder service))
  builder)

(defn make-null-server-call-listener []
  (proxy [ServerCall$Listener][]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The Context is the way the gRPC library juggles thread-bound
;; variables around.  We just use one specific entry in this Context
;; and we set it to a Clojure map.  This is kind of reinventing the
;; wheel, because Clojure has been having these kinds of thread-safe
;; scoping mechanisms since ever (with-local-vars), but we need to
;; use what we've got.

(defonce ^:private ctx-key (Context/keyWithDefault "ctx" {}))

(defn- make-grpc-context [v]
  (.withValue (Context/current) ctx-key v))

(defn grpc-context []
  (.get ctx-key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-server-interceptor
  "Create a server interceptor that calls `f` on each message.  The
  function `f` is called with three arguments the call, the metadata
  and the context (a map). `f` is supposed to return a new context.
  Return a `ServerInterceptor` instance."
  [f]
  (proxy [ServerInterceptor] []
    (interceptCall [call metadata next-handler]
      (try
        (-> (f call metadata (grpc-context))
            make-grpc-context
            (Contexts/interceptCall call metadata next-handler))
        (catch Exception e
          (.close call
                  (.withCause (or (:status (ex-data e))
                                  (Status/INTERNAL))
                              e)
                  metadata)
          (make-null-server-call-listener))))))

(defn make-simple-server-auth-interceptor
  "Instantiate a very simple server authentication interceptor.  The
  only thing that this interceptor does is to throw an exception if
  `f` returns false.  `f` is a function that checks that its only
  argument is a valid authentication token; it returns true if it
  is. `k` is the key where the auth token is to be found in the
  metadata; it can be a Clojure keyword or a `Metadata$key` instance."
  [k f]
  (let [k (as-metadata-key k)]
    (make-server-interceptor
     (fn [call metadata context]
       (let [token (.get metadata k)]
         (when-not (f token)
           (throw (ex-info "missing or invalid authentication"
                           {:key k
                            :token token
                            :status (Status/UNAUTHENTICATED)
                            :metadata metadata})))
         {:authenticated true
          :token token})))))

(defn as-server-interceptor [x]
  (if (instance? ServerInterceptor x)
    x
    (make-server-interceptor x)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-server
  "Start the list of GRPC `services` on port `port`.  `port` is an
  integer and `services` is a list.  Services are defined with
  `def-service` and instantiated with make-*-service, which is
  auto-generated by `def-service`. Return a `ServerImpl`."
  [port services & {:keys [interceptors]}]
  (-> (ServerBuilder/forPort port)
      (add-interceptors (map as-server-interceptor interceptors))
      (add-services services)
      .build
      .start))

(defmacro with-server
  "Execute `body` within the scope of `name` which is bound to a server
  started on `port` and serving `services` (a vector of service names).
  On exit, the server is shut down."
  [[name port services & opts] & body]
  (let [service-makers (mapv (fn [s]
                               `(~(symbol (str "make-" s "-service"))))
                             services)]
    `(let [~name (start-server ~port ~service-makers ~@opts)]
       (try
         (do ~@body)
         (finally
           (.shutdown ~name))))))

(defmacro def-blocking-stub-maker
  "Define a maker function for a blocking stub of `service`.  The
  resulting function accepts two arguments: a hostname and a port
  number."
  [service]
  `(defn ~(kcs (str "make-" (last (s/split (str service) #"\.")) "-stub")) [host# port#]
     (. ~(symbol (str service service-class-suffix)) newBlockingStub
        (-> (ManagedChannelBuilder/forAddress host# port#)
            .usePlaintext
            .build))))

(defmacro with-channel
  "Execute `body` within the lexical scope of `channel` bound to
  `value`. The `channel` will properly be shut down on exit of
  `body`."
  [[channel value] & body]
  `(let [~channel ~value]
     (try
       ~@body
       (finally
         (.. ~channel shutdown (awaitTermination channel-termination-wait TimeUnit/SECONDS))))))

(defmacro with-stub
  "Execute `body` within the lexical scope of `stub` bound to
  `value`. The `stub`'s channel will properly be shut down on exit of
  `body`."
  [[stub value] & body]
  `(let [~stub ~value]
     (with-channel [channel# (.getChannel ~stub)]
       ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- service-method-signature [service method]
  (let [c (service-implementation-class service)
        m (or (class-method c (csk/->camelCase (name method)))
              (throw (ex-info "Unknown method for class" {:class c :method method})))
        [_ [req resp]] (method-signature m)]
    [(.getType req)
     (first (.. resp getType getActualTypeArguments))]))

(defmacro ^{:style/indent [1 [:defn]]} def-service
  "Define a function that creates service objects as expected by the
  `start-server` function.  For a service Foo, the resulting function
  will have the name make-foo-service and accept no arguments.  The
  methods defined with this macro should be those declared in the
  proto file.  Each method should accept the argument(s) specified in
  the proto file and return a sequence of messages.  Return messages
  are expected to be normal Clojure maps; the conversion to protobuf
  is performed automatically."
  [service & methods]
  (let [service-name (last (s/split (str service) #"\."))
        super (service-implementation-class service)
        output-types (map (fn [[method & _]]
                            (second (service-method-signature service method)))
                          methods)
        converters (repeatedly (count methods) gensym)
        expand-method (fn [output-converter [method [request] & forms]]
                        `(~(csk/->camelCaseSymbol method) [request# response#]
                          (let [~request (from-protobuf request#)
                                result# (do ~@forms)]
                            (doseq [obj# result#]
                              (.onNext response# (~output-converter obj#)))
                            (.onCompleted response#))))]
    `(let ~(vec (interleave converters
                            (map (partial list `make-converter-to-protobuf)
                                 output-types)))
       (defn ~(kcs (str "make-" service-name "-service")) []
         (proxy [~(symbol (.getName super))] []
           ~@(map expand-method converters methods))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic json-preserve-field-names true)
(def ^:dynamic json-omit-whitespace false)
(def ^:dynamic json-include-default-values true)
(def ^:dynamic json-enums-as-ints false)

(defn protobuf->json
  "Given a GRPC protobuf message, return a JSON string of its representation."
  [protobuf & {:keys [preserve-field-names omit-whitespace include-default-values enum-as-ints]
               :or {preserve-field-names json-preserve-field-names
                    omit-whitespace json-omit-whitespace
                    include-default-values json-include-default-values
                    enum-as-ints json-enums-as-ints}}]
  (.print (cond-> (JsonFormat/printer)
            preserve-field-names .preservingProtoFieldNames
            omit-whitespace .omittingInsignificantWhitespace
            include-default-values .includingDefaultValueFields
            enum-as-ints .printingEnumsAsInts)
          protobuf))

(defn make-json->protobuf [type]
  (let [new-builder (wrap-static-method type "newBuilder" [])]
    (fn [s]
      (let [builder (new-builder)]
        (.merge (JsonFormat/parser) s builder)
        (.build builder)))))

(defn make-parse-from [type]
  (wrap-static-method type "parseFrom" [byte-array-class]))
