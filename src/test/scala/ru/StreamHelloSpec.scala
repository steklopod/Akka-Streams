package ru

import akka.stream._
import akka.stream.scaladsl._

import akka.{ NotUsed, Done }
import akka.actor.ActorSystem
import akka.util.ByteString
import scala.concurrent._
import scala.concurrent.duration._
import java.nio.file.Paths

import org.scalatest._
import org.scalatest.concurrent._


class StreamHelloSpec extends WordSpec with BeforeAndAfterAll with ScalaFutures {
  implicit val patience = PatienceConfig(5.seconds)

  def println(any: Any) = () // silence printing stuff

  "demonstrate Source" in {
    implicit val system = ActorSystem("QuickStart")
    implicit val materializer = ActorMaterializer()

    val source: Source[Int, NotUsed] = Source(1 to 100)

    source.runForeach(i ⇒ println(i))(materializer)

    val factorials = source.scan(BigInt(1))((acc, next) ⇒ acc * next)

    val result: Future[IOResult] =
      factorials
        .map(num ⇒ ByteString(s"$num\n"))
        .runWith(FileIO.toPath(Paths.get("factorials.txt")))

    factorials.map(_.toString).runWith(lineSink("factorial2.txt"))

    factorials
      .zipWith(Source(0 to 100))((num, idx) ⇒ s"$idx! = $num")
      .throttle(1, 1.second)
      .take(3)
      .runForeach(println)

    val done: Future[Done] = source.runForeach(i ⇒ println(i))(materializer)

    implicit val ec = system.dispatcher
    done.onComplete(_ ⇒ system.terminate())

    done.futureValue
  }

  def lineSink(filename: String): Sink[String, Future[IOResult]] =
    Flow[String]
      .map(s ⇒ ByteString(s + "\n"))
      .toMat(FileIO.toPath(Paths.get(filename)))(Keep.right)
}