(defproject fourtytoo/protobluff "0.1.0-SNAPSHOT"
  :description "A GRPC library for Clojure"
  :url "http://github.com/fourtytoo/protobluff"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [com.google.protobuf/protobuf-java "3.19.1"]
                 [com.google.protobuf/protobuf-java-util "3.19.1"]
                 [io.grpc/grpc-core "1.42.1"]
                 [io.grpc/grpc-protobuf "1.42.1"]
                 [camel-snake-kebab "0.4.2"]]
  :repl-options {:init-ns protobluff.core}
  :plugins [[lein-protoc "0.5.0"]]
  :protoc-grpc {}
  ;; overridden in other profiles
  :proto-source-paths []
  :proto-target-path "target/generated-sources/protobuf"
  :profiles {:dev {:proto-source-paths ["src/proto"]
                   :java-source-paths  ["target/generated-sources/protobuf"]
                   :dependencies [[javax.annotation/javax.annotation-api "1.2"]
                                  [com.taoensso/tufte "2.2.0"]
                                  [io.grpc/grpc-stub "1.42.1"]
                                  [io.grpc/grpc-netty "1.42.1" :exclusions [io.grpc/grpc-core]]
                                  [io.netty/netty-tcnative-boringssl-static "2.0.40.Final"]]}})
