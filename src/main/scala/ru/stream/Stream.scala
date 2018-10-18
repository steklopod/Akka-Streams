package ru.stream

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}

import scala.concurrent.Await
import scala.concurrent.duration._

object Stream extends App {
  implicit val actorSystem = ActorSystem()
  import actorSystem.dispatcher
  implicit val flowMaterializer = ActorMaterializer()

  // Source
  val input = Source(1 to 100)

  // Flow
  val normalize = Flow[Int].map(_ * 2)

  // Sink
  val output = Sink.foreach[Int](println)

  input.via(normalize).runWith(output).andThen {
    case _ =>
      actorSystem.terminate()
      Await.ready(actorSystem.whenTerminated, 1 minute)
  }
}
