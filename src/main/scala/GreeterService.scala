package com.example

import zio.{ZIO, IO}
import zio.stream.{Stream, ZStream}
import com.example.protos.orders._


//https://grpc.io/docs/what-is-grpc/core-concepts/

class GreeterService extends ZioOrders.Greeter {


  // def sayHello(request: HelloRequest): Future[HelloReply]
  override def sayHello(
      request: HelloRequest
  ): IO[io.grpc.StatusException, HelloReply] = {
    val zdt = java.time.ZonedDateTime.now()
    // IO.raiseError(new Exception("My custom error occurred in my service application code, bla bla"))
    ZIO.attempt(HelloReply(Option("TZ: " + zdt.toString())))
      .mapError(e => io.grpc.Status.INTERNAL.withDescription(e.getMessage).asException())
  }


  override def lotsOfReplies(
      request: HelloRequest
  ): ZStream[Any, io.grpc.StatusException, HelloReply] = {
    ZStream(
      HelloReply(Option("TZ: " + java.time.ZonedDateTime.now().toString())),
      HelloReply(Option("TZ: " + java.time.ZonedDateTime.now().toString())),
      HelloReply(Option("TZ: " + java.time.ZonedDateTime.now().toString())),
      HelloReply(Option("TZ: " + java.time.ZonedDateTime.now().toString()))
    )
  }

  override def lotsOfGreetings(
      request: ZStream[Any, io.grpc.StatusException, HelloRequest]
  ): IO[io.grpc.StatusException, HelloReply] = {
    request
      .runFold("")((acum, c) => acum + "," + c.getName)
      .map(str => HelloReply(Some(str)))
      .mapError(e => io.grpc.Status.INTERNAL.withDescription(e.getMessage).asException())
  }

  override def bidiHello(
      request: ZStream[Any, io.grpc.StatusException, HelloRequest]
  ): ZStream[Any, io.grpc.StatusException, HelloReply] =
    request.map(helloRequest => HelloReply(Some(helloRequest.getName)))

}
