package ru.hello

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.{Done, NotUsed}
import org.scalatest._
import org.scalatest.concurrent._

import scala.concurrent._
import scala.concurrent.duration._

class StreamHelloSpec extends WordSpec with BeforeAndAfterAll with ScalaFutures {
  implicit val patience = PatienceConfig(5 seconds)

  def println(any: Any) = () // silence printing stuff

  implicit val system       = ActorSystem("StreamHello")
  implicit val materializer = ActorMaterializer() // это фабрика для движков потока, что делает потоки запускаемыми

  val source: Source[Int, NotUsed] = Source(1 to 100)

  source.runForeach(i ⇒ println(i))(materializer)

  val factorials: Source[BigInt, NotUsed] = source.scan( BigInt(1) ) ( (acc, next) ⇒ acc * next )

  val result: Future[IOResult] =
    factorials
      .map(num ⇒ ByteString(s"$num\n"))
      .runWith(FileIO.toPath(Paths.get("factorials.txt")))

  factorials
    .map(_.toString)
    .runWith(lineSink("factorial2.txt"))

  factorials
    .zipWith(Source(0 to 100))((num, idx) ⇒ s"$idx! = $num")
    .throttle(1, 1.second)
    .take(3)
    .runForeach(println)


  val done: Future[Done] = source.runForeach(i ⇒ println(i))(materializer)

  implicit val ec = system.dispatcher
  done.onComplete(_ ⇒ system.terminate())
  done.futureValue

  def lineSink(filename: String): Sink[String, Future[IOResult]] =
    Flow[String]
      .map(s ⇒ ByteString(s + "\n"))
      .toMat(FileIO.toPath(Paths.get(filename)))(Keep.right)
}
