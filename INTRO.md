# Introduction to qh2grpc-zio

## Overview

**qh2grpc-zio** is a lightweight gRPC integration framework for Scala that provides native gRPC support for HTTP/2 servers **without** relying on Google's standard gRPC `ServerBuilder` infrastructure. Instead, it directly implements the gRPC protocol over HTTP/2 using the high-performance ZIO Quartz H2 library.

## Motivation

Traditional gRPC implementations in Scala (like scalapb-grpc) depend on the full gRPC Java stack, including:
- `io.grpc.ServerBuilder` - Heavy server infrastructure
- `io.grpc.netty.*` - Netty-specific transport layer
- Complex interceptor chains and server call handlers

While powerful, this approach:
- **Adds unnecessary overhead** when you already have a performant HTTP/2 server
- **Duplicates functionality** - running a separate gRPC server alongside your HTTP/2 server
- **Limits flexibility** - tied to Google's server architecture and abstractions
- **Increases resource usage** - separate ports, thread pools, and connection handling

**qh2grpc-zio** takes a different approach: **Implement gRPC as HTTP/2 request handlers**.

## Key Concept

gRPC is fundamentally an HTTP/2 protocol with:
1. Specific header conventions (`content-type: application/grpc`)
2. Binary framing (5-byte prefix: 1 byte compression flag + 4 bytes message size)
3. Protobuf serialization
4. Trailer-based status codes (`grpc-status`, `grpc-message`)

Since ZIO Quartz H2 already provides a high-performance HTTP/2 server, we can implement gRPC **directly as routes** without needing Google's server infrastructure.

## Architecture

### Traditional gRPC Stack
```
┌─────────────────────────┐
│   gRPC Service Impl     │
├─────────────────────────┤
│   gRPC ServerBuilder    │
├─────────────────────────┤
│   Netty HTTP/2 Server   │
└─────────────────────────┘
     Separate server
```

### qh2grpc-zio Approach
```
┌─────────────────────────────────┐
│      gRPC Service Impl          │
├─────────────────────────────────┤
│  Router (gRPC Protocol Handler) │  ← This framework
├─────────────────────────────────┤
│    ZIO Quartz H2 HTTP/2 Server  │
└─────────────────────────────────┘
       Single unified server
```

## Core Components

### 1. **Router** (`io.quartz.grpc.Router`)
The main request routing component that:
- Converts HTTP/2 requests to gRPC method calls
- Transforms HTTP headers to/from gRPC `Metadata`
- Handles errors with proper gRPC status codes and trailers
- Integrates with `WebFilter` for authentication/authorization

### 2. **TraitMethodFinder** (`io.quartz.grpc.TraitMethodFinder`)
A compile-time macro (Scala 3) that:
- Introspects gRPC service traits at compile time
- Generates type-safe method maps for all 4 gRPC communication patterns:
  - **Unary → Unary**: Single request, single response
  - **Unary → Stream**: Single request, streaming response (server streaming)
  - **Stream → Unary**: Streaming request, single response (client streaming)
  - **Stream → Stream**: Bidirectional streaming
- Uses `scalapb.GeneratedMessage` for generic type handling

### 3. **Utils** (`io.quartz.grpc.Utils`)
Protocol handling utilities:
- `process01`: Dispatches requests to the correct method type handler
- `byteStreamToMessageStream`: Parses gRPC wire format (5-byte header + protobuf)
- Frame size handling for gRPC framing protocol
- Error wrapping with `QH2GrpcError`

## Request Flow

```
HTTP/2 Request (from gRPC client)
       ↓
WebFilter (authentication/authorization)
       ↓
Router.getIO (match path to gRPC method)
       ↓
Utils.process01 (dispatch by method type)
       ↓
Service Method Execution (e.g., GreeterService.sayHello)
       ↓
Response with gRPC trailers (grpc-status: 0, grpc-message: ok)
       ↓
HTTP/2 Response
```

## Benefits

### 🚀 **Performance**
- **Zero overhead**: Direct HTTP/2 handling without intermediate gRPC layers
- **IO-Uring support**: Leverage Linux kernel io_uring for maximum throughput (Linux 5.5+)
- **Efficient memory usage**: Single server, unified connection pool

### 🎯 **Simplicity**
- **Single server**: No need to run separate gRPC and HTTP/2 servers
- **Unified routing**: gRPC endpoints coexist with regular HTTP/2 routes
- **Standard tooling**: Use standard Scala Protocol Buffers (ScalaPB)

### 🔧 **Flexibility**
- **Custom filtering**: Reuse existing `WebFilter` infrastructure
- **Fine-grained control**: Direct access to HTTP/2 primitives
- **ZIO integration**: Native ZIO effects and streaming

### 📦 **Minimal Dependencies**
- No `grpc-netty` or `grpc-netty-shaded`
- No Netty dependency conflicts
- Just ZIO, ScalaPB, and ZIO Quartz H2

## Example Usage

```scala
import io.quartz.grpc.{Router, TraitMethodFinder}
import com.example.protos.orders.ZioOrders

// Define your gRPC service
class GreeterService extends ZioOrders.Greeter {
  override def sayHello(request: HelloRequest): IO[StatusException, HelloReply] = {
    ZIO.succeed(HelloReply(Some(s"Hello, ${request.getName}!")))
  }

  // Support all 4 gRPC patterns...
}

// Create router with compile-time method discovery
val service = new GreeterService()
val methodMap = TraitMethodFinder.getAllMethods[GreeterService]

for {
  serverDef <- ZioOrders.Greeter.genericBindable.bind(service)
  router = Router(service, serverDef, methodMap)

  // Add to your HTTP/2 server routes
  _ <- server.addRoute("/orders.Greeter/*", router.getIO)
} yield ()
```

## Technical Highlights

### Compile-Time Type Safety
Using Scala 3 macros, method discovery happens at compile time:
- No reflection overhead at runtime
- Type errors caught during compilation
- IDE autocomplete for method names

### Generic Message Handling
All protobuf messages extend `scalapb.GeneratedMessage`, allowing:
- Framework code to work with **any** gRPC service
- Type-safe casting at method invocation
- Zero-cost abstractions

### Wire Protocol Compliance
Full compliance with gRPC wire protocol:
- Correct header handling (`content-type`, `grpc-encoding`, etc.)
- Proper framing (5-byte length-prefixed messages)
- Trailer-based status reporting
- Compatible with **any** gRPC client (grpcurl, Postman, language clients)

## Comparison

| Feature | qh2grpc-zio | Traditional gRPC |
|---------|-------------|------------------|
| Server Infrastructure | HTTP/2 native | Google ServerBuilder |
| Dependencies | Minimal (ZIO, ScalaPB) | Heavy (grpc-netty, full stack) |
| Method Discovery | Compile-time macros | Runtime reflection |
| Server Count | 1 (unified) | 2+ (separate servers) |
| IO Backend | NIO or io_uring | Netty only |
| Integration | Direct HTTP/2 routes | Separate gRPC server |

## Use Cases

Perfect for:
- ✅ Microservices that need both REST and gRPC endpoints
- ✅ High-performance applications requiring io_uring
- ✅ Projects wanting to minimize dependencies
- ✅ Teams already using ZIO and HTTP/2 servers
- ✅ Applications requiring fine-grained control over request handling

Not ideal for:
- ❌ Projects requiring Google's gRPC interceptor ecosystem
- ❌ Applications using advanced gRPC features (health checks, reflection API)
- ❌ Teams unfamiliar with ZIO or Scala 3 macros

## Getting Started

See [CLAUDE.md](CLAUDE.md) for detailed setup instructions, including:
- Building and running the server
- Creating gRPC services
- Testing with gRPC clients
- IO-Uring backend configuration

### Quick Test

A comprehensive test script is included to validate all 4 gRPC communication patterns:

```bash
# Start the server
sbt run

# In another terminal, run the test script
./test-grpc.sh
```

The script tests:
- ✅ Unary to Unary (SayHello)
- ✅ Unary to Stream (LotsOfReplies)
- ✅ Stream to Unary (LotsOfGreetings)
- ✅ Stream to Stream (BidiHello)

See [test-grpc.sh](test-grpc.sh) for details.

## License

See LICENSE file for details.

## Contributing

Contributions welcome! This is an experimental framework demonstrating how gRPC can be implemented as lightweight HTTP/2 handlers without the full Google gRPC server stack.
