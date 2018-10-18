package ru.reactive_tweets

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, ClosedShape, OverflowStrategy}
import ru.testkit.AkkaSpec

import scala.concurrent.{Await, Future}
import scala.io.StdIn

object TwitterStreamQuickstartDocSpec {

  final case class Author(handle: String)
  final case class Hashtag(name: String)
  final case class Tweet(author: Author, timestamp: Long, body: String) {
    def hashtags: Set[Hashtag] =
      body
        .split(" ")
        .collect {
          case t if t.startsWith("#") ⇒ Hashtag(t.replaceAll("[^#\\w]", ""))
        }
        .toSet
  }

  val akkaTag = Hashtag("#akka")

  abstract class TweetSourceDecl {
    val tweets: Source[Tweet, NotUsed]
  }

  val tweets: Source[Tweet, NotUsed] = Source(
    Tweet(Author("rolandkuhn"), System.currentTimeMillis, "#akka rocks!") ::
      Tweet(Author("patriknw"), System.currentTimeMillis, "#akka !") ::
      Tweet(Author("bantonsson"), System.currentTimeMillis, "#akka !") ::
      Tweet(Author("drewhk"), System.currentTimeMillis, "#akka !") ::
      Tweet(Author("ktosopl"), System.currentTimeMillis, "#akka on the rocks!") ::
      Tweet(Author("mmartynas"), System.currentTimeMillis, "wow #akka !") ::
      Tweet(Author("akkateam"), System.currentTimeMillis, "#akka rocks!") ::
      Tweet(Author("bananaman"), System.currentTimeMillis, "#bananas rock!") ::
      Tweet(Author("appleman"), System.currentTimeMillis, "#apples rock!") ::
      Tweet(Author("drama"), System.currentTimeMillis, "we compared #apples to #oranges!") ::
      Nil)
}

class TwitterStreamQuickstartDocSpec extends AkkaSpec {
  import TwitterStreamQuickstartDocSpec._

  implicit val executionContext = system.dispatcher

  def println(s: Any): Unit = ()

  trait Example1 {
    implicit val system       = ActorSystem("reactive-tweets")
    implicit val materializer = ActorMaterializer()
  }

  implicit val materializer = ActorMaterializer()

  "filter and map" in {

    val authors: Source[TwitterStreamQuickstartDocSpec.Author, NotUsed] =
      tweets
        .filter(_.hashtags.contains(akkaTag))
        .map(_.author)

    trait Example3 {
      val authors: Source[TwitterStreamQuickstartDocSpec.Author, NotUsed] =
        tweets
          .collect { case t if t.hashtags.contains(akkaTag) ⇒ t.author }
    }

    authors.runWith(Sink.foreach(println))
    authors.runForeach(println)
  }

  "mapConcat hashtags" in {
    val hashtags: Source[TwitterStreamQuickstartDocSpec.Hashtag, NotUsed] = tweets.mapConcat(_.hashtags.toList)
  }

  trait HiddenDefinitions {
    val writeAuthors: Sink[TwitterStreamQuickstartDocSpec.Author, NotUsed]   = ???
    val writeHashtags: Sink[TwitterStreamQuickstartDocSpec.Hashtag, NotUsed] = ???
  }

  "simple broadcast" in {
    val writeAuthors: Sink[TwitterStreamQuickstartDocSpec.Author, Future[Done]]   = Sink.ignore
    val writeHashtags: Sink[TwitterStreamQuickstartDocSpec.Hashtag, Future[Done]] = Sink.ignore

    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val bcast = b.add(Broadcast[TwitterStreamQuickstartDocSpec.Tweet](2))
      tweets ~> bcast.in
      bcast.out(0) ~> Flow[TwitterStreamQuickstartDocSpec.Tweet].map(_.author) ~> writeAuthors
      bcast.out(1) ~> Flow[TwitterStreamQuickstartDocSpec.Tweet].mapConcat(_.hashtags.toList) ~> writeHashtags
      ClosedShape
    })
    g.run()
  }

  "simple fiddle showcase" in {

    tweets
      .filterNot(_.hashtags.contains(akkaTag))  // Удалить все твиты, содержащие #akka hashtag
      .map(_.hashtags)                          // Получите все наборы хэштегов ...
      .reduce(_ ++ _)                           // ... и сводить их к одному набору, удаляя дубликаты во всех твитах
      .mapConcat(identity)                      // Сгладьте поток твитов в поток хэштегов
      .map(_.name.toUpperCase)                  // Преобразование всех хэштегов в верхний регистр
      .runWith(Sink.foreach(println))           // Присоедините поток к раковине, который, наконец, напечатает хэштеги
      .value
  }

  "slowProcessing" in {
    def slowComputation(t: TwitterStreamQuickstartDocSpec.Tweet): Long = {
      Thread.sleep(500) // действовать, как если бы выполняли некоторые тяжелые вычисления
      42
    }

    tweets
      .buffer(10, OverflowStrategy.dropHead)
      .map(slowComputation)
      .runWith(Sink.ignore)
  }

  "backpressure by readline" in {
    trait X {
      import scala.concurrent.duration._

      val completion: Future[Done] =
        Source(1 to 10)
          .map(i ⇒ { println(s"map => $i"); i })
          .runForeach { i ⇒ StdIn.readLine(s"Element = $i; continue reading? [press enter]\n")
          }

      Await.ready(completion, 1.minute)
    }
  }

  "count elements on finite stream" in {
    val count: Flow[TwitterStreamQuickstartDocSpec.Tweet, Int, NotUsed] =
      Flow[TwitterStreamQuickstartDocSpec.Tweet].map(_ ⇒ 1)

    val sumSink: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)

    val counterGraph: RunnableGraph[Future[Int]] =
      tweets
        .via(count)
        .toMat(sumSink)(Keep.right)

    val sum: Future[Int] = counterGraph.run()

    sum.foreach(c ⇒ println(s"Total tweets processed: $c"))

    new AnyRef {
      val sum: Future[Int] = tweets.map(t ⇒ 1).runWith(sumSink)
    }
  }

  "materialize multiple times" in {
    val tweetsInMinuteFromNow = tweets // не на самом деле во втором, просто действуя, как будто

    val sumSink = Sink.fold[Int, Int](0)(_ + _)
    val counterRunnableGraph: RunnableGraph[Future[Int]] =
      tweetsInMinuteFromNow
        .filter(_.hashtags contains akkaTag)
        .map(t ⇒ 1)
        .toMat(sumSink)(Keep.right)

    val morningTweetsCount: Future[Int] = counterRunnableGraph.run()
    val eveningTweetsCount: Future[Int] = counterRunnableGraph.run()

    val sum: Future[Int] = counterRunnableGraph.run()

    sum.map { c ⇒ println(s"Total tweets processed: $c")
    }
  }

}
