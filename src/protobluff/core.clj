(ns protobluff.core
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as s])
  (:import [java.lang.reflect Modifier]
           [io.grpc ServerBuilder ManagedChannelBuilder]
           [java.util.concurrent TimeUnit]))



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

(defn enum->map [klass]
  (->> (invoke-static-method klass "values")
       seq
       (map (juxt #(csk/->kebab-case-keyword (.name %))
                  identity))
       (into {})))

(defn enum-keywords [klass]
  (keys (enum->map klass)))

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

(defn make-converter-to-protobuf
  "Make a function that accepts a Clojure type (usually a map) and
  convert it to a PROTOBUF POJO of type `val-type`.  Return a function."
  [val-type]
  (let [new-builder (new-builder-method val-type)]
    (cond new-builder
          (let [setters (builder-setters (.getReturnType new-builder))]
            (fn [v]
              (let [b1 (.invoke new-builder val-type nil #_(into-array Object []))]
                (try (-> (if (and *ignore-nils*
                                  (nil? v))
                           ;; avoid null pointer exceptions
                           b1
                           (do
                             (doseq [[k v] v]
                               (if-let [setter (get setters k)]
                                 (setter b1 v)
                                 (throw (ex-info "unknown member in class" {:key k :value v :type val-type}))))
                             b1))
                         .build)
                     (catch Exception e
                       (throw (ex-info "in to-protobuf" {:builder b1 :type val-type :value v} e)))))))

          (class-is-enum? val-type)
          (fn [v]
            (if (number? v)
              (invoke-static-method val-type "forNumber" v)
              (keyword->enum val-type v)))

          (= String val-type)
          str

          (= Integer/TYPE val-type)
          int

          :else identity)))

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
  (csk/->kebab-case-keyword (subs (.getName method) 3)))

(defn builder-setters [builder-class]
  (->> (class-methods builder-class)
       (map (fn [method]
              (cond (setter-method? method)
                    (let [method-key (method->key method)
                          val-type (first (.getParameterTypes method))
                          to-protobuf (make-converter-to-protobuf val-type)]
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
                          to-protobuf (make-converter-to-protobuf val-type)]
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
                          key-to-protobuf (make-converter-to-protobuf key-type)
                          value-to-protobuf (make-converter-to-protobuf value-type)]
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
  (csk/->kebab-case-keyword (.getName response)))

(defmethod from-protobuf com.google.protobuf.MessageOrBuilder
  [response]
  (->> (.getAllFields response)
       (map (fn [[field value]]
              [(csk/->kebab-case-keyword (.getName field))
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

(defmacro wrap-stub
  "Expose the interface to a GRPC service stub with the automatic
  definition of the corresponding wrapper functions.  If, for
  instance, the service `Greeter` defines the methods `sayHello` and
  `sayGoodbye`, the functions `greeter-say-hello` and
  `greeter-say-goodbye` will be automatically defined for you."
  [service]
  (let [stub (service-stub-class service)
        service-name (-> (service-class service)
                         .getSimpleName
                         (string-remove-suffix service-class-suffix))
        expand-method (fn [[method arg-types]]
                        (let [args (repeatedly (count arg-types) #(gensym "arg"))
                              converters (repeatedly (count arg-types) #(gensym "to-grpc"))
                              stub (gensym "stub")]
                          `(let ~(vec (mapcat (fn [cv type]
                                                  `(~cv (make-converter-to-protobuf ~type)))
                                              converters arg-types))
                             (defn ~(csk/->kebab-case-symbol (str service-name "-" method)) ~(vec (cons stub args))
                               (from-protobuf
                                (. ~stub ~(symbol method)
                                   ~@(map (fn [cv arg]
                                            `(~cv ~arg))
                                          converters args)))))))]
    `(do ~@(->> (service-methods stub)
                (map (fn [method]
                       [(.getName method)
                        (vec (.getParameterTypes method))]))
                (map expand-method)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn service-method-signature [service method]
  (let [c (service-implementation-class service)
        m (or (class-method c (name method))
              (throw (ex-info "Unknown method for class" {:class c :method method})))
        [_ [req resp]] (method-signature m)]
    [(.getType req)
     (first (.. resp getType getActualTypeArguments))]))

(defn start-server
  "Start the list of GRPC `services` on port `port`."
  [port services]
  (let [builder (ServerBuilder/forPort port)]
    (doseq [service services]
      (.addService builder service))
    (.start (.build builder))))

(defmacro def-blocking-stub-maker
  "Define a maker function for a blocking stub of `service`.  The
  resulting function accepts two arguments: a hostname and a port
  number."
  [service]
  `(defn ~(csk/->kebab-case-symbol (str "make-" (last (s/split (str service) #"\.")) "-stub")) [host# port#]
     (. ~(symbol (str service service-class-suffix)) newBlockingStub
        (-> (ManagedChannelBuilder/forAddress host# port#)
            (.usePlaintext true)
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
        super (symbol (str service service-class-suffix "$" service-name
                           service-implementation-base-suffix))
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
                            (map (partial list 'make-converter-to-protobuf)
                                 output-types)))
       (defn ~(csk/->kebab-case-symbol (str "make-" service-name "-service")) []
         (proxy [~super] []
           ~@(map expand-method converters methods))))))
