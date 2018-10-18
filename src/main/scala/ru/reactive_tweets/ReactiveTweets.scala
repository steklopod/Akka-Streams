package ru.reactive_tweets

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import twitter4j.Status

object ReactiveTweets extends App {
  implicit val actorSystem = ActorSystem()
  import actorSystem.dispatcher
  implicit val flowMaterializer = ActorMaterializer()

  val source = Source.fromIterator(() => TwitterClient.retrieveTweets("#Akka"))

  val normalize = Flow[Status]
    .map { t => Tweet(Author(t.getUser.getName), t.getText)
    }

  val sink = Sink.foreach[Tweet](println)

  source
    .via(normalize)
    .runWith(sink)
    .andThen {
      case _ =>
        actorSystem.terminate()
    }

}
