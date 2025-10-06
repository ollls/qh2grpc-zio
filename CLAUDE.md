# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a ZIO Quartz H2 HTTP/2 and gRPC server example project written in Scala 3. The project demonstrates high-performance HTTP/2 server implementations using the ZIO Quartz H2 library with support for both Java NIO and Linux IO-Uring backends.

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
   - Introspects gRPC service traits at compile time
   - Generates method maps for all 4 gRPC method types:
     - Unary to Unary
     - Unary to Stream
     - Stream to Unary
     - Stream to Stream
   - Creates type-safe method references using Scala 3 quoted expressions

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

Service implementations extend generated ZIO gRPC traits (e.g., `ZioOrders.Greeter`) and override methods with ZIO effect signatures. See `GreeterService.scala` for examples of all 4 gRPC method types.

## Project Structure

```
zio-qh2-examples/
├── src/main/
│   ├── scala/
│   │   ├── Run.scala                    # Main application entry point
│   │   ├── GreeterService.scala         # gRPC service implementation
│   │   └── io/quartz/grpc/              # gRPC integration layer
│   ├── protobuf/                        # Protocol buffer definitions
│   └── resources/                       # Runtime resources (keystore, etc.)
├── web_root/                            # Static files served via HTTP/2
├── build.sbt                            # SBT build configuration
└── project/
    ├── plugins.sbt                      # SBT plugins (protoc, pgp)
    └── Dependencies.scala               # Dependency definitions
```

## SSL/TLS Configuration

The server requires a JKS keystore file. Expected location and credentials:
- Path: `keystore.jks` (in `zio-qh2-examples/`)
- Password: `password`
- Type: JKS
- Protocol: TLS

See `QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")` in `Run.scala`.
