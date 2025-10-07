/** This example was created with Cascade in Windsurf (the world's first agentic IDE) under the guidance of Oleg
  * Strygun.
  *
  * It demonstrates a simple HTTP/2 server using ZIO Quartz H2 with a basic route that handles query parameters and
  * accesses the ZIO environment.
  */
package com.example

import zio.{ZIO, ZLayer}
import zio.ZIOAppDefault
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Method, Request, Response, ContentType}
import io.quartz.http2.model.Method._
import io.quartz.http2.routes.{WebFilter, HttpRouteIO}
import zio.stream.ZStream

import io.quartz.grpc.Router
import io.quartz.grpc.TraitMethodFinder

import ch.qos.logback.classic.Level
import zio.logging.backend.SLF4J
import com.example.protos.orders.ZioOrders
import io.quartz.http2.model.StatusCode

// Define query parameter for name
object name extends QueryParam("name")

object Run extends ZIOAppDefault {



  val service = new GreeterService();

  override val bootstrap =
    zio.Runtime.removeDefaultLoggers ++ SLF4J.slf4j ++ zio.Runtime.enableWorkStealing

  // The WebFilter uses Either.cond to conditionally filter requests
  // Either.cond takes three parameters:
  // 1. A condition (Boolean) - if true, returns Right(value), if false, returns Left(error)
  // 2. The value to return in the Right case (the request passes through)
  // 3. The value to return in the Left case (the request is rejected with this response)
  //
  // In this example, the condition is always true, so all requests pass through
  // If you change the condition to something like r.uri.getPath().endsWith(".html"),
  // then only requests ending with .html would pass through, and all others would be
  // rejected with the Forbidden response
  val filter: WebFilter[Any] = (r: Request) =>
    ZIO.succeed(
      Either.cond(true, r, Response.Error(StatusCode.Forbidden).asText("Access denied to: " + r.uri.getPath()))
    )

  // HTTP/2 routes - includes the doc/index.html handler from Run.txt
  val httpRoutes: HttpRouteIO[Any] = {
    case GET -> "doc" /: remainingPath =>
      val FOLDER_PATH = "web_root/"
      val FILE = if (remainingPath.toString.isEmpty) "index.html" else remainingPath.toString
      val BLOCK_SIZE = 1024 * 14
      for {
        jpath <- ZIO.attempt(new java.io.File(FOLDER_PATH + FILE))
        present <- ZIO.attempt(jpath.exists())
        _ <- ZIO.fail(new java.io.FileNotFoundException(jpath.toString())).when(present == false)
      } yield (Response
        .Ok()
        .asStream(ZStream.fromFile(jpath, BLOCK_SIZE))
        .contentType(ContentType.contentTypeFromFileName(FILE)))
  }



  def run = {
    val env = ZLayer.fromZIO(ZIO.succeed("Hello"))

    ZIO.executor.flatMap { executor =>
      implicit val ec: scala.concurrent.ExecutionContext =
        executor.asExecutionContext

      (for {
      _ <- zio.Console.printLine("****************************************************************************************")
      _ <- zio.Console.printLine("\u001B[31mUse https://localhost:8443/doc/index.html to read the index.html file\u001B[0m")
      _ <- zio.Console.printLine("****************************************************************************************")
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")

      args <- this.getArgs
      _ <- ZIO.when(args.find(_ == "--debug").isDefined)(ZIO.attempt(QuartzH2Server.setLoggingLevel(Level.DEBUG)))
      _ <- ZIO.when(args.find(_ == "--error").isDefined)(ZIO.attempt(QuartzH2Server.setLoggingLevel(Level.ERROR)))
      _ <- ZIO.when(args.find(_ == "--trace").isDefined)(ZIO.attempt(QuartzH2Server.setLoggingLevel(Level.TRACE)))
      _ <- ZIO.when(args.find(_ == "--off").isDefined)(ZIO.attempt(QuartzH2Server.setLoggingLevel(Level.OFF)))


      sd <- ZioOrders.Greeter.genericBindable.bind(service)

      x <- ZIO.attempt(TraitMethodFinder.getAllMethods[GreeterService])

      router <- ZIO.succeed(
          Router[GreeterService](
            service,
            sd,
            x,
          )
        )

      grpcIO = router.getIO

      // Combine HTTP/2 routes with gRPC routes under unified filter
      combinedRoute = router.combine(httpRoutes, grpcIO, filter)

      exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).start(combinedRoute, sync = false)

      // For Linux IO-Uring support, comment out the line above and uncomment the line below
      // exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).startIO_linuxOnly(1, R, filter)
    } yield (exitCode)).provideSomeLayer(env)
    }
  }
}
