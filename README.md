# protobluff

A Clojure library to speak [GRPC](https://grpc.io), produce and
understand
[protobufs](https://developers.google.com/protocol-buffers).


## Usage

Include a dependency of this library into your project.

```
[fourtytoo/protobluff "0.1.0-SNAPSHOT"]
```


### Protobuf conversion

Given a protobuf `PBuff` that you defined and compiled elsewhere you
can create a function that converts to such POJO with:

```clojure
(def my-map-to-protobuf (protobluff.core/make-converter-to-grpc PBuff))
```

Then you can call `my-map-to-protobuf` to convert a Clojure map to
a PBuff Java object:

```clojure
(my-map-to-protobuf a-conforming-clojure-map)
```

The opposite can be done with:

```clojure
(protobluff.core/from-grpc a-pbuff-object)
```

No need to define a function for that.


### Client

You can define a service stub with the macro:

```clojure
(protobluff.core/wrap-stub MyService)
```

provided that your service was defined in a protobuf and you have
imported it.

The `wrap-stup` macro automatically generates the wrappers for the
methods, that you have defined in your service.


### Server

The call

```clojure
(protobluff.core/start-server port services)
```

start a GRPC server on `port` exposing a list of services.  The
services you need to pass to this function can be defined with the
macro:

```clojure
(protobluff.core/def-service name method ...)
```

The methods defined with this macro should match those declared in the
protobuf file.  Each method should accept the argument(s) specified in
the protobuf file and return a sequence of maps.  Those are
automatically converted to protobuf POJOs for you.



## License

Copyright © 2019 Walter C. Pelissero <walter@pelissero.de>

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
