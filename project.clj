(defproject fourtytoo/protobluff "0.1.0-SNAPSHOT"
  :description "A GRPC library for Clojure"
  :url "http://github.com/fourtytoo/protobluff"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [com.google.protobuf/protobuf-java "3.15.1"]
                 [io.grpc/grpc-core "1.35.0"]
                 [io.grpc/grpc-netty "1.35.0"
                  :exclusions [io.grpc/grpc-core
                               io.netty/netty-codec-http2]]
                 [io.grpc/grpc-protobuf "1.35.0"]
                 [camel-snake-kebab "0.4.0"]]
  :repl-options {:init-ns protobluff.core})
