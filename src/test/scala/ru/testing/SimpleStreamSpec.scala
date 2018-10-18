package ru.testing

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent._
import scala.concurrent.duration._

class SimpleStreamSpec
    extends TestKit(ActorSystem("test-system"))
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with Matchers {

  override def afterAll = TestKit.shutdownActorSystem(system)

  implicit val flowMaterializer = ActorMaterializer()

  "Simple Sink" should "return the correct results" in {
    val sinkUnderTest = Sink.fold[Int, Int](0)(_ + _)

    val source = Source(1 to 4)

    val result = source.runWith(sinkUnderTest)

    Await.result(result, 100.millis) should equal(10)
  }

  "Simple Source" should "contains a correct elements" in {
    val source = Source(1 to 10)

    val result = source
      .grouped(2)
      .runWith(Sink.head)

    Await.result(result, 100.millis) should equal(1 to 2)
  }

  "Simple Flow" should "do right transformation" in {
    val flow = Flow[Int].takeWhile(_ < 5)

    val result = Source(1 to 10)
      .via(flow)
      .runWith(Sink.fold(Seq.empty[Int])(_ :+ _))

    Await.result(result, 100.millis) should equal(1 to 4)
  }
}
