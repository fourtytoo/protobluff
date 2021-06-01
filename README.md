# protobluff

A Clojure library to speak [GRPC](https://grpc.io), produce and
understand
[protobufs](https://developers.google.com/protocol-buffers).


## Usage

Include a dependency of this library into your project.

```clojure
[fourtytoo/protobluff "0.1.0-SNAPSHOT"]
```

Then require `protobluff.core` in your code.


### Protobuf conversion

Given a protobuf `PBuff` that you defined and compiled elsewhere you
can create a function that converts to such POJO with:

```clojure
(def my-map-to-protobuf (make-converter-to-grpc PBuff))
```

Then you can call `my-map-to-protobuf` to convert a Clojure map to
a PBuff Java object:

```clojure
(my-map-to-protobuf a-conforming-clojure-map)
```

The opposite can be done with:

```clojure
(from-grpc a-pbuff-object)
```

No need to define a function for that.


### Server

The call

```clojure
(start-server port services)
```

starts a GRPC server on `port` exposing a list of services.

The services you need to pass to this function can be defined with the
macro:

```clojure
(def-service name method ...)
```

The syntax is similar to `reify`.  The methods defined with this macro
should match those declared in the protobuf file.  Each method should
accept the argument(s) specified in the protobuf file and return a
sequence of maps.  Both are automatically converted to/from protobuf
POJOs for you.

Example:

```clojure
;; Greeter is a service with two methods: sayHello() and sayGoodbye()
(def-service Greeter
  (say-hello [request]
    (println "Hi" (:who request)))
  (say-goodbye [request]
    (println "Goodbye" (:who request))))
```

will define a function `make-greeter-service` that you can call
without arguments to create an instance of the Greeter service (from
your protobuf definition).

Then you can do:

```clojure
(start-server 1337 [(make-greeter-service)])
```


### Client

You can define a service stub with the macro:

```clojure
(wrap-stub Greeter)
```

provided that your `Greeter` service was defined in a protobuf that
you compiled and imported.  The `wrap-stub` macro automatically
generates the wrappers for the methods, that you have defined in your
service.  These functions accept an open "stub".  See below.

The macro `def-blocking-stub-maker` declares a function that can be
called to connect to a GRPC service.  The function accepts a hostname
and a port number.

A complete example with `with-stub`:

```clojure
;; Greeter is a service with two methods: sayHello() and sayGoodbye()
(wrap-stub Greeter)
;; => defines greeter-say-hello and greeter-say-goodbye

(def connect-to-my-greeter (def-blocking-stub-maker Greeter))

(with-stub [s (connect-to-my-greeter "localhost" 1337)]
  (greeter-say-hello s {:who "Alice"})
  (greeter-say-goodbye s {:who "Bob"}))
```


## License

Copyright © 2019-2021 Walter C. Pelissero

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
