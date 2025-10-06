package io.quartz.grpc

import java.io.ByteArrayOutputStream
import io.grpc.Metadata
import zio.{ZIO,Task}
import scalapb.GeneratedMessage
import java.io.ByteArrayInputStream
import io.grpc.ServerMethodDefinition
import io.grpc.MethodDescriptor.Marshaller
import scala.quoted.*
import scala.util.Try
import io.grpc.Status
import zio.Chunk
import zio.stream.ZStream
import java.nio.ByteBuffer

case class QH2GrpcError(val code: Status, val message: String)
    extends Exception(message)

object Utils {

  inline def listMethods[T]: List[String] = ${ listMethodsImpl[T] }

  private def listMethodsImpl[T: Type](using Quotes): Expr[List[String]] = {
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val symbol = tpe.typeSymbol

    val methods = symbol.methodMembers
      .filter(m => !m.isClassConstructor && !m.isNoSymbol)
      .map(_.name)
      .toList

    Expr(methods)
  }

  inline def process01[
      svcT,
      ReqT <: GeneratedMessage,
      RespT <: GeneratedMessage
  ](
      svc: svcT,
      d: ServerMethodDefinition[GeneratedMessage, GeneratedMessage],
      method_map: Map[String, io.quartz.grpc.MethodRefBase[svcT]],
      request: ZStream[Any,Throwable, Byte],
      ctx: Metadata
  ): Task[ZStream[Any, Throwable, Array[Byte]]] = {
    for {
      methodName0 <- ZIO.succeed(d.getMethodDescriptor().getBareMethodName())
      methodName <- ZIO.succeed(
        methodName0.substring(0, 1).toLowerCase() + methodName0.substring(1)
      )
      rm <- ZIO.succeed(d.getMethodDescriptor().getRequestMarshaller())


      _ <- ZIO.debug( "******************************>>>>> " + methodName )

      _ <- ZIO.debug( "******************************>>>>> map size = " + method_map.size )

      _ <- ZIO.debug( "******************************>>>>> " + method_map.keySet.mkString(" # "))

      method : io.quartz.grpc.MethodRefBase[svcT] <- ZIO.fromOption(method_map.get(methodName))
        .orElseFail(new NoSuchElementException(
          s"Unexpected error: scala macro method Map: GRPC Service method not found: $methodName"
        ))
      outputStream <- ZIO.succeed(new ByteArrayOutputStream())
      response <- method match {
        case MethodUnaryToUnary[svcT](m) =>
          for {
            req0 <- request.runCollect
              .map(_.toArray)
            req <- ZIO.succeed(rm.parse(new ByteArrayInputStream(extractRequest(req0))))

            response <- m(svc)(req, ctx)
              .map(r => {
                r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
                outputStream.toByteArray
              })
              .map(zio.stream.ZStream.succeed(_))
          } yield (response)

        case MethodUnaryToStream[svcT](m) =>
          for {
            req0 <- request.runCollect
              .map(_.toArray)
            req <- ZIO.succeed(rm.parse(new ByteArrayInputStream(extractRequest(req0))))

            response <- ZIO.succeed(m(svc)(req, ctx).map(r => {
              r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
              outputStream.toByteArray
            }))
          } yield (response)

        case MethodStreamToUnary[svcT](m) =>
          for {
            req <- ZIO.succeed(byteStreamToMessageStream(request, rm))
            response <- m(svc)(req, ctx)
              .map(r => {
                r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
                outputStream.toByteArray
              })
              .map(zio.stream.ZStream.succeed(_))
          } yield (response)

        case MethodStreamToStream(m) =>
          for {
            req <- ZIO.succeed(byteStreamToMessageStream(request, rm))
            response <- ZIO.succeed(m(svc)(req, ctx).map(r => {
              r.writeTo(Utils.sizeResponse(r.serializedSize, outputStream))
              outputStream.toByteArray
            }))
          } yield (response)
      }
    } yield (response)
  }

  def oneRequest(stream: ZStream[Any, Throwable, Byte]): Task[Array[Byte]] = {
    stream.runCollect.map { chunk =>
      val bytes = chunk.toArray
      val sz = ByteBuffer.wrap(bytes.slice(1, 5)).getInt()
      bytes.slice(5, 5 + sz)
    }
  }

  def sizeFromStream(s0: ZStream[Any, Throwable, Byte]): Task[Int] = {
    s0.take(5)
      .runCollect
      .map { chunk =>
        chunk.drop(1).toArray
      }
      .map(bytes => ByteBuffer.wrap(bytes).getInt())
  }

  def extractRequest(protoBytes: Array[Byte]): Array[Byte] = {
    val incoming_size =
      java.nio.ByteBuffer.wrap(protoBytes.slice(1, 5)).getInt()
    protoBytes.slice(5, incoming_size + 1 + 4)
  }

  def sizeResponse(
      serializedSize: Int,
      i: ByteArrayOutputStream
  ): ByteArrayOutputStream = {
    i.reset()
    i.writeBytes(
      java.nio.ByteBuffer
        .allocate(5)
        .put(0.byteValue)
        .putInt(serializedSize)
        .array()
    )
    i
  }

  def outputStreamForResponse(serializedSize: Int): ByteArrayOutputStream = {
    val outputStream = new ByteArrayOutputStream()
    outputStream.writeBytes(
      java.nio.ByteBuffer
        .allocate(5)
        .put(0.byteValue)
        .putInt(serializedSize)
        .array()
    )

    outputStream
  }

  /** Converts a stream of bytes into a stream of GeneratedMessage objects.
    *
    * This function processes a byte stream that contains serialized protocol
    * buffer messages. Each message in the stream is expected to be prefixed
    * with a 5-byte header: 1 byte for a marker or type indicator, followed by 4
    * bytes representing the message size.
    *
    * @param byteStream
    *   A Stream[IO, Byte] containing the serialized messages with headers.
    * @param rm
    *   A Marshaller[GeneratedMessage] used to parse the individual messages.
    * @return
    *   A Stream[IO, GeneratedMessage] of parsed protocol buffer messages.
    *
    * @note
    *   This function uses fs2's `scanChunks` for efficient processing of byte
    *   chunks. It accumulates bytes across chunk boundaries to ensure complete
    *   messages are parsed.
    */
  def byteStreamToMessageStream(
      byteStream: ZStream[Any, Throwable, Byte],
      rm: Marshaller[GeneratedMessage]
  ): ZStream[Any, Throwable, GeneratedMessage] = {

    case class State(buffer: Chunk[Byte], messages: Chunk[GeneratedMessage])

    def readSize(chunk: Chunk[Byte]): (Int, Chunk[Byte]) = {
      val size = chunk.drop(1).take(4).toArray
      val remaining = chunk.drop(5)
      (ByteBuffer.wrap(size).getInt, remaining)
    }

    def processChunk(state: State, chunk: Chunk[Byte]): State = {
      var current: Chunk[Byte] = state.buffer ++ chunk
      var messages: Chunk[GeneratedMessage] = Chunk.empty
      var continue: Boolean = true

      while (continue && current.size >= 5) {
        val (size, remaining) = readSize(current)
        if (remaining.size >= size) {
          val (messageBytes, leftover) = remaining.splitAt(size)
          messages = messages :+ rm.parse(new ByteArrayInputStream(messageBytes.toArray))
          current = leftover
        } else {
          continue = false
        }
      }

      State(current, messages)
    }

    byteStream.chunks
      .scan(State(Chunk.empty, Chunk.empty))(processChunk)
      .flatMap(state => ZStream.fromChunk(state.messages))
  }
}
