package io.quartz.grpc

import io.grpc.ServerMethodDefinition
import io.grpc.ServerServiceDefinition
import io.grpc.Metadata
import io.quartz.http2.model.{Headers, Request, Response}
import scala.jdk.CollectionConverters._
import io.quartz.http2.routes.{HttpRoute, HttpRouteIO, WebFilter}
import scalapb.GeneratedMessage
import zio.{Task,ZIO}
import zio.stream.Stream
import io.grpc.Status

class Router[T](
    service: T,
    d: ServerServiceDefinition,
    method_map: Map[String, MethodRefBase[T]],
    filter: WebFilter[Any] = (r0: Request) => ZIO.succeed(Right(r0))
) {

  def headersToMetadata(hdr: Headers): Metadata = {
    val excludedPrefixes = Set(":", "grpc-")
    val excludedHeaders = Set("content-type", "user-agent")

    hdr.tbl.foldRight(new Metadata) {
      case ((key, value), m) => {
        val lowerKey = key.toLowerCase
        if (
          !excludedPrefixes.exists(lowerKey.startsWith) && !excludedHeaders
            .contains(lowerKey)
        ) {
          if (!key.endsWith("-bin")) {
            value.foreach { v =>
              m.put(
                Metadata.Key.of(lowerKey, Metadata.ASCII_STRING_MARSHALLER),
                v
              )
            }
          } else {
            value.foreach { v =>
              val base64string = java.util.Base64.getDecoder.decode(v)
              m.put(
                Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER),
                base64string
              )
            }
          }
        }
        m
      }
    }
  }

  def getIO: HttpRoute[Any] = { req =>
    for {
      fR <- filter(req)
      response <- fR match {
        case Left(response) => ZIO.succeed(Some(response))
        case Right(request) =>
          post_getIO(request).catchAll {
            case QH2GrpcError(status, message) => {
              ZIO.succeed(Some(
                Response
                  .Ok()
                  .hdr("content-type" -> "application/grpc")
                  .trailers(
                    Headers(
                      "grpc-status" -> status.getCode.value().toString(),
                      "grpc-message" -> message.toString()
                    )
                  )
                  .asStream(
                    zio.stream.ZStream.fromIterable(
                      Utils.outputStreamForResponse(0).toByteArray()
                    )
                  )
              ))
            }
            case e: Throwable =>
              ZIO.succeed(Some(
                Response
                  .Ok()
                  .hdr("content-type" -> "application/grpc")
                  .trailers(
                    Headers(
                      "grpc-status" -> Status.INTERNAL
                        .getCode()
                        .value()
                        .toString(),
                      "grpc-message" -> e.getMessage
                    )
                  )
                  .asStream(
                    zio.stream.ZStream.fromIterable(
                      Utils.outputStreamForResponse(0).toByteArray()
                    )
                  )
              ))
          }
      }
    } yield (response)

  }

  private def post_getIO: HttpRoute[Any] = { req =>
    {
      for {
        // grpc_request <- req.body
        methodDefOpt <- ZIO.attempt(

          //method_map[]

          d.getMethods()
            .asScala
            .find(p => {
              "/" + p.getMethodDescriptor().getFullMethodName() == req.path
            })
            .map(mD =>
              mD.asInstanceOf[ServerMethodDefinition[
                GeneratedMessage,
                GeneratedMessage
              ]]
            )
        )

        result: Option[Stream[Throwable, Array[Byte]]] <- methodDefOpt match {
          case None => ZIO.succeed(None)
          case Some(serverMethodDef) =>
            Utils
              .process01[T, GeneratedMessage, GeneratedMessage](
                service,
                serverMethodDef,
                method_map: Map[String, MethodRefBase[T]],
                req.stream,
                headersToMetadata(req.headers)
              )
              .map(c => Some(c))
        }
      } yield (result.map(stream =>
        Response
          .Ok()
          .trailers(
            Headers(
              "grpc-status" -> Status.OK.getCode().value().toString(),
              "grpc-message" -> "ok",
              "content-type" -> "application/grpc"
            )
          )
          .hdr("content-type" -> "application/grpc")
          .asStream(stream.flatMap(zio.stream.ZStream.fromIterable(_)))
      ))

    }

  }

  /** Combines HTTP/2 routes with gRPC routes under a unified filter.
    *
    * @param pf
    *   Partial function for HTTP/2 routes
    * @param grpcRoute
    *   Already lifted gRPC HttpRoute (from getIO)
    * @param filter
    *   WebFilter to apply to all requests
    * @return
    *   Combined HttpRoute that handles both HTTP/2 and gRPC requests
    */
  def combine[Env](
      pf: HttpRouteIO[Env],
      grpcRoute: HttpRoute[Env],
      filter: WebFilter[Env]
  ): HttpRoute[Env] = { (r0: Request) =>
    filter(r0).flatMap {
      case Left(response) =>
        ZIO.logWarning(
          s"Web filter denied access with response code ${response.code}"
        ) *> ZIO.succeed(Some(response))
      case Right(request) =>
        pf.lift(request) match {
          case Some(c) => c.flatMap(r => ZIO.succeed(Some(r)))
          case None    => grpcRoute(request)
        }
    }
  }
}
