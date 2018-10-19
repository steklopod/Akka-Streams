package ru.io

import java.io.File

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.util.ByteString

import scala.util.{Failure, Success}

object WriteStreamApp extends App {
  implicit val actorSystem      = ActorSystem()
  implicit val flowMaterializer = ActorMaterializer()
  import actorSystem.dispatcher

  // Source
  val source = Source(1 to 10000).filter(isPrime)

  // Sink
  val sink = FileIO.toFile(new File("src/main/resources/prime.txt"))

  // выход для файла
  val fileSink = Flow[Int]
    .map(i => ByteString(i.toString))
    .toMat(sink)((_, bytesWritten) => bytesWritten)

  val consoleSink = Sink.foreach[Int](println)

  // отправить простые числа в оба стока: файл и консоль, используя граф-API
  val graph = GraphDSL.create(fileSink, consoleSink)((file, _) => file) { implicit builder => (file, console) =>
    import GraphDSL.Implicits._

    val broadCast = builder.add(Broadcast[Int](2))

    source ~> broadCast ~> file
    broadCast ~> console
    ClosedShape
  }

  val materialized = RunnableGraph.fromGraph(graph).run()

  // убедитесь, что выходной файл закрыт и завершение работы системы завершено
  materialized.onComplete {
    case Success(_) =>
      actorSystem.terminate()
    case Failure(e) =>
      println(s"Failure: ${e.getMessage}")
      actorSystem.terminate()
  }

  def isPrime(n: Int): Boolean = {
    if (n <= 1) false
    else if (n == 2) true
    else !(2 until n).exists(x => n % x == 0)
  }
}
