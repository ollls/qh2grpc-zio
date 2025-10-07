# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a lightweight gRPC-over-HTTP/2 framework written in Scala 3 that implements gRPC protocol support **without** using Google's standard `ServerBuilder` infrastructure. Instead, it provides native gRPC support directly within a high-performance ZIO Quartz H2 HTTP/2 server, supporting both Java NIO and Linux IO-Uring backends.

**Key Innovation:** Implements gRPC as HTTP/2 request handlers rather than running a separate gRPC server stack, enabling unified HTTP/2 and gRPC endpoints with minimal overhead.

**Key Technology Stack:**
- Scala 3.3.3
- ZIO 2.1.16 (functional effect system)
- zio-quartz-h2 0.7.1 (HTTP/2 server library)
- ScalaPB & zio-grpc 0.6.3 (gRPC and Protocol Buffers)
- SBT 1.10.0 (build tool)

## Build Commands

```bash
# Run the server
sbt run

# Compile the project
sbt compile

# Run tests
sbt test

# Clean build artifacts
sbt clean

# Compile with debug output
sbt --debug compile
```

## Running the Server

The main entry point is `com.example.Run` in `zio-qh2-examples/src/main/scala/Run.scala`.

**Server configuration:**
- Default: HTTPS on `localhost:8443`
- Requires: `keystore.jks` file with password "password" in `zio-qh2-examples/`
- Access example: `https://localhost:8443/doc/index.html`

**Logging control via command-line arguments:**
```bash
sbt "run --debug"   # DEBUG level
sbt "run --error"   # ERROR level
sbt "run --trace"   # TRACE level
sbt "run --off"     # Logging OFF
```

**IO Backend Selection:**

By default, the server uses Java NIO. To switch to Linux IO-Uring (requires Linux kernel 5.1+, recommended 5.5+):
1. Edit `src/main/scala/Run.scala`
2. Comment out: `new QuartzH2Server(...).start(grpcIO, sync = false)`
3. Uncomment: `new QuartzH2Server(...).startIO_linuxOnly(1, R, filter)`

Check kernel version: `uname -r`

## Architecture

### gRPC Integration Layer

The project implements a custom gRPC-over-HTTP/2 integration using compile-time reflection (Scala 3 macros):

**Core Components:**

1. **Router** (`io/quartz/grpc/Router.scala`): Main request routing component that:
   - Converts HTTP/2 requests to gRPC calls
   - Handles metadata transformation (HTTP headers ↔ gRPC Metadata)
   - Manages error responses with proper gRPC status codes and trailers
   - Integrates WebFilter for request filtering

2. **TraitMethodFinder** (`io/quartz/grpc/TraitMethodFinder.scala`): Compile-time macro that:
   - Introspects gRPC service traits at compile time using Scala 3 macros
   - Generates method maps for all 4 gRPC method types:
     - **Unary to Unary**: `(request: GeneratedMessage) => Task[GeneratedMessage]`
     - **Unary to Stream**: `(request: GeneratedMessage) => ZStream[Any, Throwable, GeneratedMessage]`
     - **Stream to Unary**: `(request: ZStream[...]) => Task[GeneratedMessage]`
     - **Stream to Stream**: `(request: ZStream[...]) => ZStream[Any, Throwable, GeneratedMessage]`
   - Creates type-safe method references using Scala 3 quoted expressions
   - Uses `scalapb.GeneratedMessage` base type for framework generality (works with any service)
   - Discovers methods by inspecting parameter count, types, and return types at compile time

3. **Utils** (`io/quartz/grpc/Utils.scala`): Protocol handling utilities:
   - `process01`: Dispatches requests to correct method type handler
   - `byteStreamToMessageStream`: Parses gRPC wire format (5-byte header + protobuf)
   - Size prefix handling for gRPC framing protocol
   - Error wrapping with `QH2GrpcError`

**Request Flow:**
```
HTTP/2 Request
  → WebFilter (Either.cond pattern for access control)
  → Router.getIO (match path to gRPC method)
  → Utils.process01 (dispatch by method type)
  → Service method execution (GreeterService)
  → Response with gRPC trailers (grpc-status, grpc-message)
```

### WebFilter Pattern

All examples use a functional filtering pattern with `Either.cond`:
```scala
val filter: WebFilter[Any] = (r: Request) =>
  ZIO.succeed(
    Either.cond(condition, r, Response.Error(StatusCode.Forbidden))
  )
```
- `Right(r)`: Request passes to routes
- `Left(response)`: Request rejected with error response

### Protocol Buffers

- Proto files: `src/main/protobuf/*.proto`
- Generated code: `target/scala-3.3.3/src_managed/main/scalapb/`
- Build plugin: `sbt-protoc` with `zio-grpc-codegen`
- Configuration in `build.sbt` generates both standard gRPC stubs (`grpc = true`) and ZIO gRPC code

### Service Implementation

Service implementations extend generated ZIO gRPC traits (e.g., `ZioOrders.Greeter`) and override methods with ZIO effect signatures.

**Important:** Services use **1-parameter methods** (just the request), not 2-parameter methods (request + Metadata). The framework's `TraitMethodFinder` macro looks for methods with single parameters that are subtypes of `scalapb.GeneratedMessage`.

Example from `GreeterService.scala`:
```scala
class GreeterService extends ZioOrders.Greeter {
  // Unary to Unary
  override def sayHello(request: HelloRequest): IO[StatusException, HelloReply] = {
    ZIO.succeed(HelloReply(Some(s"Hello, ${request.getName}!")))
  }

  // Unary to Stream
  override def lotsOfReplies(request: HelloRequest): ZStream[Any, StatusException, HelloReply] = {
    ZStream(HelloReply(Some("Reply 1")), HelloReply(Some("Reply 2")))
  }

  // Stream to Unary
  override def lotsOfGreetings(
    request: ZStream[Any, StatusException, HelloRequest]
  ): IO[StatusException, HelloReply] = {
    request.runFold("")((acc, req) => acc + "," + req.getName)
      .map(names => HelloReply(Some(names)))
  }

  // Stream to Stream (bidirectional)
  override def bidiHello(
    request: ZStream[Any, StatusException, HelloRequest]
  ): ZStream[Any, StatusException, HelloReply] = {
    request.map(req => HelloReply(Some(req.getName)))
  }
}
```

## Project Structure

```
qh2grpc-zio/
├── src/main/
│   ├── scala/
│   │   ├── Run.scala                        # Main application entry point
│   │   ├── GreeterService.scala             # Example gRPC service implementation
│   │   └── io/quartz/grpc/                  # gRPC integration framework
│   │       ├── Router.scala                 # HTTP/2 to gRPC routing
│   │       ├── TraitMethodFinder.scala      # Compile-time method discovery
│   │       └── Utils.scala                  # Protocol handling utilities
│   ├── protobuf/
│   │   └── orders.proto                     # Example protobuf definitions
│   └── resources/                           # Runtime resources (keystore, etc.)
├── web_root/                                # Static files served via HTTP/2
├── build.sbt                                # SBT build configuration
├── project/
│   ├── plugins.sbt                          # SBT plugins (protoc, pgp)
│   └── Dependencies.scala                   # Dependency definitions
├── INTRO.md                                 # Framework introduction and motivation
└── CLAUDE.md                                # This file (AI assistant guidance)
```

## How to Add a New gRPC Service

1. **Define your protobuf** in `src/main/protobuf/yourservice.proto`:
   ```protobuf
   syntax = "proto3";
   package com.example.protos;

   service YourService {
     rpc YourMethod(YourRequest) returns (YourReply) {}
   }
   ```

2. **Compile protobuf**: Run `sbt compile` to generate ZIO gRPC traits

3. **Implement the service**:
   ```scala
   class YourServiceImpl extends ZioYourService.YourService {
     override def yourMethod(request: YourRequest): IO[StatusException, YourReply] = {
       ZIO.succeed(YourReply(...))
     }
   }
   ```

4. **Register in Run.scala**:
   ```scala
   val service = new YourServiceImpl()
   val methodMap = TraitMethodFinder.getAllMethods[YourServiceImpl]

   for {
     serverDef <- ZioYourService.YourService.genericBindable.bind(service)
     router = Router(service, serverDef, methodMap)
     _ <- server.addRoute("/com.example.protos.YourService/*", router.getIO)
   } yield ()
   ```

## Framework Design Principles

1. **Zero Google gRPC Server Dependency**: Uses only the gRPC model classes (`Metadata`, `Status`, etc.) but not `ServerBuilder` or Netty transport
2. **Compile-Time Safety**: All method discovery happens at compile time via Scala 3 macros
3. **Generic Message Handling**: Framework operates on `scalapb.GeneratedMessage` base type, making it work with any protobuf service
4. **ZIO-Native**: Full integration with ZIO effects and streams, no Future-based APIs
5. **HTTP/2 First**: gRPC implemented as HTTP/2 routes, not a separate server

## SSL/TLS Configuration

The server requires a JKS keystore file. Expected location and credentials:
- Path: `keystore.jks` (in project root)
- Password: `password`
- Type: JKS
- Protocol: TLS

See `QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")` in `Run.scala`.

## Testing the gRPC Server

### Automated Test Script

A comprehensive test script is included that validates all 4 gRPC communication patterns:

```bash
# Start the server in one terminal
sbt run

# Run the test script in another terminal
./test-grpc.sh
```

The [test-grpc.sh](test-grpc.sh) script automatically tests:
- **Unary to Unary** (`SayHello`): Single request/response
- **Unary to Stream** (`LotsOfReplies`): Single request, streaming response
- **Stream to Unary** (`LotsOfGreetings`): Streaming request, single response
- **Stream to Stream** (`BidiHello`): Bidirectional streaming

The script provides color-coded output, detailed descriptions, and validates expected behavior for each method.

### Manual Testing with grpcurl

You can also test individual methods manually:

**Unary to Unary:**
```bash
grpcurl -v -insecure -proto src/main/protobuf/orders.proto \
  -d '{"name": "John The Cube Jr", "number": 101}' \
  localhost:8443 com.example.protos.Greeter/SayHello
```

**Unary to Stream:**
```bash
grpcurl -v -insecure -proto src/main/protobuf/orders.proto \
  -d '{"name": "Alice", "number": 42}' \
  localhost:8443 com.example.protos.Greeter/LotsOfReplies
```

**Stream to Unary:**
```bash
grpcurl -v -insecure -proto src/main/protobuf/orders.proto \
  -d @ localhost:8443 com.example.protos.Greeter/LotsOfGreetings <<EOF
{"name": "Bob"}
{"name": "Charlie"}
{"name": "Dave"}
EOF
```

**Stream to Stream (Bidirectional):**
```bash
grpcurl -v -insecure -proto src/main/protobuf/orders.proto \
  -d @ localhost:8443 com.example.protos.Greeter/BidiHello <<EOF
{"name": "Emma", "number": 1}
{"name": "Frank", "number": 2}
{"name": "Grace", "number": 3}
EOF
```

**Expected output:** The server logs will show "Found: 4 methods" at startup, confirming all gRPC methods were discovered by the compile-time macro.

## Troubleshooting

**Issue: "Found: 0 methods" at startup**
- **Cause**: `TraitMethodFinder` didn't find any methods matching the expected signatures
- **Solution**: Verify service methods have exactly 1 parameter (request only, no Metadata parameter)
- **Check**: Methods must have return types matching `Task[GeneratedMessage]` or `ZStream[Any, Throwable, GeneratedMessage]`

**Issue: gRPC client receives INTERNAL error**
- **Cause**: Exception thrown in service method
- **Solution**: Check server logs for stack trace, ensure proper error handling with `IO[StatusException, _]`

**Issue: Protobuf not compiling**
- **Cause**: SBT plugin misconfiguration
- **Solution**: Run `sbt clean compile` to regenerate protobuf code
