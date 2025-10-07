# gRPC Interceptor Implementation Plan

## Executive Summary

This document provides a comprehensive plan to add standard gRPC interceptor capabilities to the `Router.scala` implementation. The goal is to bring feature parity with Google's gRPC `ServerInterceptor` while maintaining ZIO-native semantics and the current zero-dependency architecture on gRPC server infrastructure.

---

## 1. Current State Analysis

### What We Have
- **HTTP/2-level filtering** via `WebFilter[Env]` - binary accept/reject before gRPC processing
- **Basic error handling** - converts exceptions to gRPC status codes with trailers
- **Metadata conversion** - HTTP headers → `io.grpc.Metadata` after filtering
- **Route combination** - `combine()` function merges HTTP/2 and gRPC routes

### What We're Missing
- **Interceptor chain** - multiple ordered interceptors
- **gRPC-aware filtering** - access to method descriptor, metadata, typed requests
- **Lifecycle hooks** - pre/post method execution, error handling, response modification
- **Context propagation** - deadlines, cancellation, call attributes
- **Per-message interception** - streaming message inspection/modification
- **Observability integration** - metrics, tracing, structured logging

---

## 2. Architecture Design

### 2.1 Core Abstractions

#### `GrpcCall[Req, Resp]` - Call Context
Encapsulates all information about a gRPC call:

```scala
case class GrpcCall[Req <: GeneratedMessage, Resp <: GeneratedMessage](
  methodDescriptor: MethodDescriptor[Req, Resp],
  metadata: Metadata,
  attributes: Map[String, Any] = Map.empty,
  deadline: Option[Deadline] = None,
  authority: Option[String] = None,
  securityLevel: SecurityLevel = SecurityLevel.NONE
)

sealed trait SecurityLevel
object SecurityLevel {
  case object NONE extends SecurityLevel
  case object INTEGRITY extends SecurityLevel
  case object PRIVACY_AND_INTEGRITY extends SecurityLevel
}

case class Deadline(timeRemaining: zio.Duration) {
  def isExpired: Boolean = timeRemaining.toMillis <= 0
}
```

#### `GrpcInterceptor[Env]` - Base Interceptor Trait

```scala
trait GrpcInterceptor[Env] {

  /** Intercept a unary call (single request → single response) */
  def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Env, StatusException, Resp]
  ): ZIO[Env, StatusException, Resp] = next(request)

  /** Intercept a server streaming call (single request → stream response) */
  def interceptServerStreaming[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZStream[Env, StatusException, Resp]
  ): ZStream[Env, StatusException, Resp] = next(request)

  /** Intercept a client streaming call (stream request → single response) */
  def interceptClientStreaming[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    requestStream: ZStream[Env, StatusException, Req],
    next: ZStream[Env, StatusException, Req] => ZIO[Env, StatusException, Resp]
  ): ZIO[Env, StatusException, Resp] = next(requestStream)

  /** Intercept a bidirectional streaming call (stream request → stream response) */
  def interceptBidiStreaming[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    requestStream: ZStream[Env, StatusException, Req],
    next: ZStream[Env, StatusException, Req] => ZStream[Env, StatusException, Resp]
  ): ZStream[Env, StatusException, Resp] = next(requestStream)

  /** Hook called on successful completion */
  def onComplete[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp]
  ): ZIO[Env, Nothing, Unit] = ZIO.unit

  /** Hook called on error */
  def onError[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    error: StatusException
  ): ZIO[Env, Nothing, StatusException] = ZIO.succeed(error)

  /** Hook called on cancellation */
  def onCancel[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp]
  ): ZIO[Env, Nothing, Unit] = ZIO.unit
}
```

#### `InterceptorChain[Env]` - Chain Multiple Interceptors

```scala
class InterceptorChain[Env](interceptors: List[GrpcInterceptor[Env]]) {

  def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    handler: Req => ZIO[Env, StatusException, Resp]
  ): ZIO[Env, StatusException, Resp] = {
    interceptors.foldRight(handler) { (interceptor, next) =>
      (req: Req) => interceptor.interceptUnary(call, req, next)
    }(request).tapBoth(
      error => ZIO.foreach(interceptors)(_.onError(call, error)).map(_.last),
      _ => ZIO.foreach(interceptors)(_.onComplete(call))
    )
  }

  def interceptServerStreaming[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    handler: Req => ZStream[Env, StatusException, Resp]
  ): ZStream[Env, StatusException, Resp] = {
    val wrapped = interceptors.foldRight(handler) { (interceptor, next) =>
      (req: Req) => interceptor.interceptServerStreaming(call, req, next)
    }
    ZStream.unwrap(
      ZIO.succeed(wrapped(request))
        .tapError(error => ZIO.foreach(interceptors)(_.onError(call, error)))
    ).ensuring(ZIO.foreach(interceptors)(_.onComplete(call)).ignore)
  }

  def interceptClientStreaming[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    requestStream: ZStream[Env, StatusException, Req],
    handler: ZStream[Env, StatusException, Req] => ZIO[Env, StatusException, Resp]
  ): ZIO[Env, StatusException, Resp] = {
    interceptors.foldRight(handler) { (interceptor, next) =>
      (stream: ZStream[Env, StatusException, Req]) =>
        interceptor.interceptClientStreaming(call, stream, next)
    }(requestStream).tapBoth(
      error => ZIO.foreach(interceptors)(_.onError(call, error)).map(_.last),
      _ => ZIO.foreach(interceptors)(_.onComplete(call))
    )
  }

  def interceptBidiStreaming[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    requestStream: ZStream[Env, StatusException, Req],
    handler: ZStream[Env, StatusException, Req] => ZStream[Env, StatusException, Resp]
  ): ZStream[Env, StatusException, Resp] = {
    val wrapped = interceptors.foldRight(handler) { (interceptor, next) =>
      (stream: ZStream[Env, StatusException, Req]) =>
        interceptor.interceptBidiStreaming(call, stream, next)
    }
    ZStream.unwrap(
      ZIO.succeed(wrapped(requestStream))
        .tapError(error => ZIO.foreach(interceptors)(_.onError(call, error)))
    ).ensuring(ZIO.foreach(interceptors)(_.onComplete(call)).ignore)
  }
}
```

### 2.2 Router Integration

Update `Router` class signature:

```scala
class Router[T](
    service: T,
    d: ServerServiceDefinition,
    method_map: Map[String, MethodRefBase[T]],
    filter: WebFilter[Any] = (r0: Request) => ZIO.succeed(Right(r0)),
    interceptors: List[GrpcInterceptor[Any]] = Nil  // NEW
) {

  private val chain = new InterceptorChain(interceptors)

  // ... existing methods ...
}
```

### 2.3 Utils.scala Updates

Modify `process01` to accept and use interceptor chain:

```scala
def process01[T, Req <: GeneratedMessage, Resp <: GeneratedMessage](
  service: T,
  serverMethodDef: ServerMethodDefinition[Req, Resp],
  method_map: Map[String, MethodRefBase[T]],
  requestStream: zio.stream.Stream[Throwable, Byte],
  metadata: Metadata,
  chain: InterceptorChain[Any],  // NEW
  deadline: Option[Deadline] = None  // NEW
): Task[zio.stream.Stream[Throwable, Array[Byte]]] = {

  val call = GrpcCall(
    methodDescriptor = serverMethodDef.getMethodDescriptor,
    metadata = metadata,
    deadline = deadline
  )

  // Existing type detection logic...
  val methodType = serverMethodDef.getMethodDescriptor.getType

  methodType match {
    case MethodDescriptor.MethodType.UNARY =>
      // Parse request, then intercept
      for {
        req <- byteStreamToMessageStream[Req](requestStream, parser).runHead.someOrFailException
        handler = (r: Req) => /* existing unary logic */
        result <- chain.interceptUnary(call, req, handler)
      } yield outputStreamForResponse(result.serializedSize, result)

    case MethodDescriptor.MethodType.SERVER_STREAMING =>
      for {
        req <- byteStreamToMessageStream[Req](requestStream, parser).runHead.someOrFailException
        handler = (r: Req) => /* existing server streaming logic */
        resultStream = chain.interceptServerStreaming(call, req, handler)
      } yield resultStream.map(msg => outputStreamForResponse(msg.serializedSize, msg))

    case MethodDescriptor.MethodType.CLIENT_STREAMING =>
      val reqStream = byteStreamToMessageStream[Req](requestStream, parser)
      val handler = (stream: ZStream[Any, StatusException, Req]) => /* existing client streaming logic */
      for {
        result <- chain.interceptClientStreaming(call, reqStream, handler)
      } yield outputStreamForResponse(result.serializedSize, result)

    case MethodDescriptor.MethodType.BIDI_STREAMING =>
      val reqStream = byteStreamToMessageStream[Req](requestStream, parser)
      val handler = (stream: ZStream[Any, StatusException, Req]) => /* existing bidi logic */
      val resultStream = chain.interceptBidiStreaming(call, reqStream, handler)
      ZIO.succeed(resultStream.map(msg => outputStreamForResponse(msg.serializedSize, msg)))
  }
}
```

---

## 3. Common Interceptor Implementations

### 3.1 Logging Interceptor

```scala
class LoggingInterceptor extends GrpcInterceptor[Any] {

  override def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Any, StatusException, Resp]
  ): ZIO[Any, StatusException, Resp] = {
    val methodName = call.methodDescriptor.getFullMethodName
    for {
      _ <- ZIO.logInfo(s"→ Unary call: $methodName")
      startTime <- Clock.nanoTime
      result <- next(request).tapBoth(
        error => ZIO.logError(s"✗ $methodName failed: ${error.getStatus}"),
        _ => Clock.nanoTime.flatMap { endTime =>
          val durationMs = (endTime - startTime) / 1_000_000
          ZIO.logInfo(s"✓ $methodName completed in ${durationMs}ms")
        }
      )
    } yield result
  }

  override def interceptServerStreaming[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZStream[Any, StatusException, Resp]
  ): ZStream[Any, StatusException, Resp] = {
    val methodName = call.methodDescriptor.getFullMethodName
    ZStream.unwrap(
      ZIO.logInfo(s"→ Server streaming: $methodName") *>
      ZIO.succeed(next(request).tap(msg => ZIO.logDebug(s"  ← Sent message")))
    )
  }

  // Similar for client streaming and bidi streaming...
}
```

### 3.2 Authentication Interceptor

```scala
class AuthInterceptor(
  tokenValidator: String => ZIO[Any, StatusException, AuthContext]
) extends GrpcInterceptor[Any] {

  private def authenticate(metadata: Metadata): ZIO[Any, StatusException, AuthContext] = {
    Option(metadata.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER))) match {
      case Some(token) if token.startsWith("Bearer ") =>
        tokenValidator(token.substring(7))
      case _ =>
        ZIO.fail(StatusException(Status.UNAUTHENTICATED.withDescription("Missing or invalid token")))
    }
  }

  override def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Any, StatusException, Resp]
  ): ZIO[Any, StatusException, Resp] = {
    authenticate(call.metadata).flatMap { authContext =>
      // Add auth context to call attributes for downstream use
      val updatedCall = call.copy(attributes = call.attributes + ("auth" -> authContext))
      next(request)
    }
  }

  // Similar for other method types...
}

case class AuthContext(userId: String, roles: Set[String])
```

### 3.3 Deadline/Timeout Interceptor

```scala
class DeadlineInterceptor extends GrpcInterceptor[Any] {

  private def extractDeadline(metadata: Metadata): Option[Deadline] = {
    Option(metadata.get(Metadata.Key.of("grpc-timeout", Metadata.ASCII_STRING_MARSHALLER)))
      .flatMap(parseTimeout)
  }

  private def parseTimeout(timeout: String): Option[Deadline] = {
    // Parse gRPC timeout format: "1H", "2M", "3S", "1000m", "1000000u", "1000000000n"
    val pattern = """(\d+)([HMSmu n])""".r
    timeout match {
      case pattern(value, unit) =>
        val nanos = unit match {
          case "H" => value.toLong * 3600 * 1_000_000_000L
          case "M" => value.toLong * 60 * 1_000_000_000L
          case "S" => value.toLong * 1_000_000_000L
          case "m" => value.toLong * 1_000_000L
          case "u" => value.toLong * 1_000L
          case "n" => value.toLong
        }
        Some(Deadline(zio.Duration.fromNanos(nanos)))
      case _ => None
    }
  }

  override def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Any, StatusException, Resp]
  ): ZIO[Any, StatusException, Resp] = {
    val deadline = call.deadline.orElse(extractDeadline(call.metadata))
    deadline match {
      case Some(d) if d.isExpired =>
        ZIO.fail(StatusException(Status.DEADLINE_EXCEEDED))
      case Some(d) =>
        next(request).timeoutFail(StatusException(Status.DEADLINE_EXCEEDED))(d.timeRemaining)
      case None =>
        next(request)
    }
  }

  // Similar for streaming methods...
}
```

### 3.4 Metrics Interceptor

```scala
class MetricsInterceptor(
  registry: MetricsRegistry  // Use your metrics library
) extends GrpcInterceptor[Any] {

  override def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Any, StatusException, Resp]
  ): ZIO[Any, StatusException, Resp] = {
    val methodName = call.methodDescriptor.getFullMethodName
    val startTime = System.nanoTime()

    next(request).tapBoth(
      error => ZIO.attempt {
        registry.incrementCounter(s"grpc.server.calls.failed", Map("method" -> methodName, "status" -> error.getStatus.getCode.toString))
        registry.recordHistogram(s"grpc.server.call.duration", System.nanoTime() - startTime, Map("method" -> methodName))
      }.ignore,
      resp => ZIO.attempt {
        registry.incrementCounter(s"grpc.server.calls.succeeded", Map("method" -> methodName))
        registry.recordHistogram(s"grpc.server.call.duration", System.nanoTime() - startTime, Map("method" -> methodName))
        registry.recordHistogram(s"grpc.server.response.size", resp.serializedSize, Map("method" -> methodName))
      }.ignore
    )
  }

  override def interceptServerStreaming[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZStream[Any, StatusException, Resp]
  ): ZStream[Any, StatusException, Resp] = {
    val methodName = call.methodDescriptor.getFullMethodName
    var messageCount = 0

    next(request).tap { msg =>
      ZIO.attempt {
        messageCount += 1
        registry.recordHistogram(s"grpc.server.response.size", msg.serializedSize, Map("method" -> methodName))
      }.ignore
    }.ensuring(
      ZIO.attempt(registry.recordHistogram(s"grpc.server.stream.messages", messageCount, Map("method" -> methodName))).ignore
    )
  }
}

trait MetricsRegistry {
  def incrementCounter(name: String, tags: Map[String, String]): Unit
  def recordHistogram(name: String, value: Long, tags: Map[String, String]): Unit
}
```

### 3.5 Rate Limiting Interceptor

```scala
class RateLimitInterceptor(
  limiter: RateLimiter
) extends GrpcInterceptor[Any] {

  override def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Any, StatusException, Resp]
  ): ZIO[Any, StatusException, Resp] = {
    val key = call.attributes.get("auth").map(_.asInstanceOf[AuthContext].userId)
      .getOrElse(call.authority.getOrElse("anonymous"))

    limiter.acquire(key).flatMap {
      case true => next(request)
      case false =>
        ZIO.fail(StatusException(
          Status.RESOURCE_EXHAUSTED.withDescription("Rate limit exceeded")
        ))
    }
  }
}

trait RateLimiter {
  def acquire(key: String): ZIO[Any, Nothing, Boolean]
}
```

### 3.6 Exception Mapping Interceptor

```scala
class ExceptionMappingInterceptor extends GrpcInterceptor[Any] {

  override def onError[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    error: StatusException
  ): ZIO[Any, Nothing, StatusException] = {
    error.getCause match {
      case _: java.util.NoSuchElementException =>
        ZIO.succeed(StatusException(Status.NOT_FOUND.withDescription("Resource not found")))
      case _: IllegalArgumentException =>
        ZIO.succeed(StatusException(Status.INVALID_ARGUMENT.withDescription(error.getMessage)))
      case _: SecurityException =>
        ZIO.succeed(StatusException(Status.PERMISSION_DENIED.withDescription("Access denied")))
      case _: java.util.concurrent.TimeoutException =>
        ZIO.succeed(StatusException(Status.DEADLINE_EXCEEDED.withDescription("Operation timed out")))
      case _ =>
        ZIO.succeed(error)
    }
  }
}
```

---

## 4. Implementation Phases

### Phase 1: Core Abstractions (Week 1)
**Files to create:**
- `src/main/scala/io/quartz/grpc/GrpcCall.scala`
- `src/main/scala/io/quartz/grpc/GrpcInterceptor.scala`
- `src/main/scala/io/quartz/grpc/InterceptorChain.scala`

**Tasks:**
1. Define `GrpcCall[Req, Resp]` case class
2. Define `GrpcInterceptor[Env]` trait with all 4 method types
3. Implement `InterceptorChain[Env]` with proper fold logic
4. Write unit tests for chain composition

**Success Criteria:**
- [ ] Can create interceptor instances
- [ ] Chain executes in correct order
- [ ] Lifecycle hooks fire at right times
- [ ] Tests pass with 80%+ coverage

### Phase 2: Router Integration (Week 2)
**Files to modify:**
- `src/main/scala/io/quartz/grpc/Router.scala`
- `src/main/scala/io/quartz/grpc/Utils.scala`

**Tasks:**
1. Add `interceptors` parameter to `Router` constructor
2. Create `InterceptorChain` instance in Router
3. Update `Utils.process01` to accept chain and deadline
4. Pass chain through to method handlers
5. Update error handling to call `onError` hooks

**Success Criteria:**
- [ ] Router compiles with new signature
- [ ] Existing tests pass unchanged
- [ ] Can add interceptor to Router
- [ ] Interceptor methods get called

### Phase 3: Common Interceptors (Week 3)
**Files to create:**
- `src/main/scala/io/quartz/grpc/interceptors/LoggingInterceptor.scala`
- `src/main/scala/io/quartz/grpc/interceptors/AuthInterceptor.scala`
- `src/main/scala/io/quartz/grpc/interceptors/DeadlineInterceptor.scala`
- `src/main/scala/io/quartz/grpc/interceptors/MetricsInterceptor.scala`

**Tasks:**
1. Implement LoggingInterceptor with structured logging
2. Implement AuthInterceptor with token validation
3. Implement DeadlineInterceptor with timeout parsing
4. Implement MetricsInterceptor (abstract metrics backend)
5. Write examples for each interceptor

**Success Criteria:**
- [ ] All 4 interceptors implemented
- [ ] Can chain multiple interceptors
- [ ] Example in Run.scala demonstrates usage
- [ ] Integration tests pass

### Phase 4: Advanced Features (Week 4)
**Files to modify:**
- `src/main/scala/io/quartz/grpc/GrpcCall.scala` (add context propagation)
- `src/main/scala/io/quartz/grpc/GrpcInterceptor.scala` (add metadata modification)

**Tasks:**
1. Add context propagation (ZIO FiberRef)
2. Support modifying outgoing metadata/trailers
3. Implement RateLimitInterceptor
4. Implement ExceptionMappingInterceptor
5. Add per-message interception for streams

**Success Criteria:**
- [ ] Context propagates through call chain
- [ ] Can modify response metadata
- [ ] Rate limiting works correctly
- [ ] Exception mapping improves error messages

### Phase 5: Documentation & Examples (Week 5)
**Files to create:**
- `INTERCEPTORS.md` - usage guide
- `examples/AuthExample.scala`
- `examples/MetricsExample.scala`

**Tasks:**
1. Write comprehensive INTERCEPTORS.md
2. Create example showing auth + logging + metrics
3. Update CLAUDE.md with interceptor info
4. Add troubleshooting guide
5. Create performance benchmarks

**Success Criteria:**
- [ ] Documentation covers all interceptors
- [ ] Examples run successfully
- [ ] Performance acceptable (<10% overhead)
- [ ] Migration guide for existing users

---

## 5. Usage Examples

### Basic Usage

```scala
import io.quartz.grpc.interceptors._

val loggingInterceptor = new LoggingInterceptor()
val authInterceptor = new AuthInterceptor(validateJWT)
val metricsInterceptor = new MetricsInterceptor(myMetricsRegistry)

val router = Router[GreeterService](
  service,
  serverDef,
  methodMap,
  filter = filter,
  interceptors = List(
    loggingInterceptor,
    authInterceptor,
    metricsInterceptor
  )
)
```

### Custom Interceptor

```scala
class CustomHeaderInterceptor extends GrpcInterceptor[Any] {
  override def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Any, StatusException, Resp]
  ): ZIO[Any, StatusException, Resp] = {
    val requestId = call.metadata.get(
      Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER)
    )

    ZIO.logInfo(s"Processing request: ${requestId}") *>
    next(request).tap { response =>
      ZIO.logInfo(s"Completed request: ${requestId}")
    }
  }
}
```

### Method-Specific Interceptor

```scala
class MethodFilterInterceptor(allowedMethods: Set[String]) extends GrpcInterceptor[Any] {
  override def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Any, StatusException, Resp]
  ): ZIO[Any, StatusException, Resp] = {
    if (allowedMethods.contains(call.methodDescriptor.getFullMethodName)) {
      next(request)
    } else {
      ZIO.fail(StatusException(Status.PERMISSION_DENIED))
    }
  }
}

// Usage
val interceptor = new MethodFilterInterceptor(Set(
  "com.example.protos.Greeter/SayHello",
  "com.example.protos.Greeter/LotsOfReplies"
))
```

---

## 6. Testing Strategy

### Unit Tests

```scala
class InterceptorChainSpec extends ZIOSpecDefault {
  def spec = suite("InterceptorChain")(
    test("executes interceptors in order") {
      var order = List.empty[Int]

      val interceptor1 = new GrpcInterceptor[Any] {
        override def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
          call: GrpcCall[Req, Resp],
          request: Req,
          next: Req => ZIO[Any, StatusException, Resp]
        ) = {
          ZIO.succeed(order = order :+ 1) *> next(request)
        }
      }

      val interceptor2 = new GrpcInterceptor[Any] {
        override def interceptUnary[Req <: GeneratedMessage, Resp <: GeneratedMessage](
          call: GrpcCall[Req, Resp],
          request: Req,
          next: Req => ZIO[Any, StatusException, Resp]
        ) = {
          ZIO.succeed(order = order :+ 2) *> next(request)
        }
      }

      val chain = new InterceptorChain(List(interceptor1, interceptor2))
      val call = GrpcCall(mockMethodDescriptor, new Metadata)
      val handler = (req: HelloRequest) => ZIO.succeed(HelloReply())

      for {
        _ <- chain.interceptUnary(call, HelloRequest(), handler)
      } yield assertTrue(order == List(1, 2))
    },

    test("calls onError hook on failure") {
      var errorCalled = false

      val interceptor = new GrpcInterceptor[Any] {
        override def onError[Req <: GeneratedMessage, Resp <: GeneratedMessage](
          call: GrpcCall[Req, Resp],
          error: StatusException
        ) = {
          ZIO.succeed { errorCalled = true; error }
        }
      }

      val chain = new InterceptorChain(List(interceptor))
      val call = GrpcCall(mockMethodDescriptor, new Metadata)
      val handler = (req: HelloRequest) => ZIO.fail(StatusException(Status.INTERNAL))

      for {
        result <- chain.interceptUnary(call, HelloRequest(), handler).exit
      } yield assertTrue(errorCalled && result.isFailure)
    }
  )
}
```

### Integration Tests

```scala
class RouterWithInterceptorsSpec extends ZIOSpecDefault {
  def spec = suite("Router with Interceptors")(
    test("logging interceptor logs method calls") {
      for {
        logRef <- Ref.make(List.empty[String])
        loggingInterceptor = new LoggingInterceptor() // capture to logRef
        router = Router[GreeterService](
          service,
          serverDef,
          methodMap,
          interceptors = List(loggingInterceptor)
        )
        request = Request(/* mock gRPC request */)
        _ <- router.getIO(request)
        logs <- logRef.get
      } yield assertTrue(logs.exists(_.contains("SayHello")))
    }
  )
}
```

---

## 7. Performance Considerations

### Overhead Analysis

**Expected overhead per interceptor:**
- Empty interceptor: ~50ns per call (function call overhead)
- Logging interceptor: ~1-5μs (depending on backend)
- Auth interceptor: ~100μs-1ms (JWT validation)
- Metrics interceptor: ~500ns (histogram recording)

**Optimization strategies:**
1. **Lazy chain construction** - build chain once at Router creation
2. **Inline small interceptors** - JIT compiler optimization
3. **Avoid allocations** - reuse GrpcCall objects where possible
4. **Fast path for empty chain** - skip chain logic if no interceptors

### Benchmarks

```scala
// Add to benchmarks/
object InterceptorBenchmark extends App {
  val runtime = Runtime.default

  val emptyChain = new InterceptorChain(Nil)
  val chainWith3 = new InterceptorChain(List(
    new LoggingInterceptor(),
    new MetricsInterceptor(noopRegistry),
    new DeadlineInterceptor()
  ))

  val iterations = 1_000_000

  // Benchmark empty chain
  val start1 = System.nanoTime()
  (1 to iterations).foreach { _ =>
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(
        emptyChain.interceptUnary(call, request, handler)
      )
    }
  }
  val duration1 = System.nanoTime() - start1
  println(s"Empty chain: ${duration1 / iterations}ns per call")

  // Benchmark 3-interceptor chain
  val start2 = System.nanoTime()
  (1 to iterations).foreach { _ =>
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(
        chainWith3.interceptUnary(call, request, handler)
      )
    }
  }
  val duration2 = System.nanoTime() - start2
  println(s"3-interceptor chain: ${duration2 / iterations}ns per call")
}
```

---

## 8. Migration Guide

### For Existing Users

**Before (no interceptors):**
```scala
val router = Router[GreeterService](service, serverDef, methodMap)
```

**After (with interceptors):**
```scala
val router = Router[GreeterService](
  service,
  serverDef,
  methodMap,
  interceptors = List(new LoggingInterceptor())
)
```

**Backward compatibility:**
- Interceptors parameter has default value `Nil`
- Existing code compiles without changes
- No breaking changes to public API

---

## 9. Open Questions

1. **Should we support modifying the request in interceptors?**
   - Pro: Standard gRPC allows this
   - Con: Breaks type safety if request type changes
   - **Decision:** Allow for same-type modification only

2. **How to handle environment type Env in interceptors?**
   - Option A: All interceptors are `GrpcInterceptor[Any]`
   - Option B: Support `GrpcInterceptor[Env]` with ZLayer composition
   - **Recommendation:** Start with `Any`, add `Env` support in Phase 4

3. **Should interceptors be able to modify response metadata?**
   - Pro: Useful for adding correlation IDs, timing headers
   - Con: Requires Response wrapper object
   - **Recommendation:** Add in Phase 4 with `ResponseContext[Resp]`

4. **How to handle cancellation propagation?**
   - Option A: Use ZIO interruption
   - Option B: Explicit cancellation signals
   - **Recommendation:** Use ZIO interruption, add `onCancel` hook

---

## 10. Future Enhancements

### Context Propagation (Post-Phase 5)

```scala
object GrpcContext {
  val requestId: FiberRef[Option[String]] = ???
  val authContext: FiberRef[Option[AuthContext]] = ???
  val deadline: FiberRef[Option[Deadline]] = ???
}

class ContextPropagatingInterceptor extends GrpcInterceptor[Any] {
  override def interceptUnary[Req, Resp](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Any, StatusException, Resp]
  ) = {
    val requestId = UUID.randomUUID().toString
    GrpcContext.requestId.locally(Some(requestId)) {
      GrpcContext.deadline.locally(call.deadline) {
        next(request)
      }
    }
  }
}
```

### Distributed Tracing Integration

```scala
class TracingInterceptor(tracer: Tracer) extends GrpcInterceptor[Any] {
  override def interceptUnary[Req, Resp](
    call: GrpcCall[Req, Resp],
    request: Req,
    next: Req => ZIO[Any, StatusException, Resp]
  ) = {
    val span = tracer.startSpan(call.methodDescriptor.getFullMethodName)
    span.setTag("grpc.method", call.methodDescriptor.getFullMethodName)

    next(request).tapBoth(
      error => ZIO.attempt(span.setTag("error", true).finish()),
      _ => ZIO.attempt(span.finish())
    )
  }
}
```

### Server Reflection Support

Add interceptor to handle `grpc.reflection.v1alpha.ServerReflection` service automatically.

---

## 11. References

- [gRPC Java ServerInterceptor](https://grpc.github.io/grpc-java/javadoc/io/grpc/ServerInterceptor.html)
- [gRPC Metadata Documentation](https://grpc.io/docs/guides/metadata/)
- [ZIO Best Practices](https://zio.dev/reference/best-practices/)
- [ScalaPB Generated Code Guide](https://scalapb.github.io/generated-code.html)

---

## Appendix A: File Structure After Implementation

```
src/main/scala/io/quartz/grpc/
├── Router.scala                          # Modified - add interceptors param
├── TraitMethodFinder.scala              # Unchanged
├── Utils.scala                          # Modified - pass chain to process01
├── GrpcCall.scala                       # NEW - call context
├── GrpcInterceptor.scala                # NEW - base trait
├── InterceptorChain.scala               # NEW - chain execution
└── interceptors/                        # NEW - common implementations
    ├── LoggingInterceptor.scala
    ├── AuthInterceptor.scala
    ├── DeadlineInterceptor.scala
    ├── MetricsInterceptor.scala
    ├── RateLimitInterceptor.scala
    └── ExceptionMappingInterceptor.scala
```

## Appendix B: Compatibility Matrix

| Feature | Standard gRPC | qh2grpc-zio (Current) | qh2grpc-zio (After) |
|---------|--------------|----------------------|---------------------|
| Server Interceptors | ✅ | ❌ | ✅ |
| Metadata Access | ✅ | ✅ | ✅ |
| Context Propagation | ✅ | ❌ | ✅ (Phase 4) |
| Deadline Handling | ✅ | ❌ | ✅ |
| Error Interception | ✅ | ⚠️ Basic | ✅ |
| Lifecycle Hooks | ✅ | ❌ | ✅ |
| Streaming Interception | ✅ | ❌ | ✅ |
| Response Metadata | ✅ | ⚠️ Limited | ✅ (Phase 4) |

---

## Summary

This implementation plan provides a complete path to adding enterprise-grade gRPC interceptor support while maintaining the project's core principles:
- **Zero dependency** on Google's ServerBuilder
- **ZIO-native** semantics
- **Type-safe** compile-time method discovery
- **High performance** with minimal overhead

The phased approach ensures incremental value delivery and allows for course correction based on real-world usage.
