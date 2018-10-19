package ru.io

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

object ReadStreamApp extends App {
  implicit val actorSystem = ActorSystem()
  import actorSystem.dispatcher
  implicit val flowMaterializer = ActorMaterializer()

  // читать строки из файла журнала
  val logFile = Paths.get("src/main/resources/log.txt")

  val source = FileIO.fromPath(logFile)

  // анализировать фрагменты байтов в строки
  val flow = Framing
    .delimiter(ByteString(System.lineSeparator()), maximumFrameLength = 512, allowTruncation = true)
    .map(_.utf8String)

  val sink = Sink.foreach(println)

  source
    .via(flow)
    .runWith(sink)
    .andThen {
      case _ =>
        actorSystem.terminate()
        Await.ready(actorSystem.whenTerminated, 1 minute)
    }
}
