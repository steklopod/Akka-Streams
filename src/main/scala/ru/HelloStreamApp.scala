package ru

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object HelloStreamApp extends App {

  implicit val actorSystem = ActorSystem()
  import actorSystem.dispatcher
  implicit val flowMaterializer = ActorMaterializer()

  // Source
  val input: Source[Int, NotUsed] = Source(1 to 100)

  // Flow
  val normalize: Flow[Int, Int, NotUsed] = Flow[Int].map(_ * 2)

  // Sink
  val output: Sink[Int, Future[Done]] = Sink.foreach[Int](println)

  input
    .via(normalize)
    .runWith(output)
    .andThen {
      case _ =>
        actorSystem.terminate()
        Await.ready(actorSystem.whenTerminated, 1 minute)
    }

}
