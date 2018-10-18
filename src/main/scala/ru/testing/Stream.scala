package ru.testing

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import ru.reactive_tweets.StreamTweetsApp.actorSystem

import scala.collection.immutable
import scala.concurrent.Await
import scala.util.Random
import scala.concurrent.Await
import scala.concurrent.duration._

object Stream extends App {
   implicit val actorSystem = ActorSystem()
   import actorSystem.dispatcher
   implicit val flowMaterializer = ActorMaterializer()

   val input = Source(1 to 100)

   val normalize = Flow[Int].map(_ * 2)

   val output = Sink.foreach[Int](println)

   input.via(normalize).runWith(output).andThen {
     case _ =>
       actorSystem.terminate()
       Await.ready(actorSystem.whenTerminated, 1 minute)   }
}
