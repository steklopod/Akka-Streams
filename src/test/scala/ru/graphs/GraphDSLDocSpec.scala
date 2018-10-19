package ru.graphs

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import ru.testkit.AkkaSpec

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class GraphDSLDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  implicit val materializer = ActorMaterializer()

  "build simple graph" in {
    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
      import GraphDSL.Implicits._
      val in  = Source(1 to 10)
      val out = Sink.ignore

      val bcast = builder.add(Broadcast[Int](2))
      val merge = builder.add(Merge[Int](2))

      val f1, f2, f3, f4 = Flow[Int].map(_ + 10)

      in ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out
      bcast ~> f4 ~> merge
      ClosedShape
    })
    g.run()
  }

  "flow connection errors" in {
    intercept[IllegalStateException] {
      RunnableGraph.fromGraph(GraphDSL.create() { implicit builder ⇒
        import GraphDSL.Implicits._
        val source1 = Source(1 to 10)
        val source2 = Source(1 to 10)

        val zip = builder.add(Zip[Int, Int]())

        source1 ~> zip.in0
        source2 ~> zip.in1
        ClosedShape
      })
    }.getMessage should include("ZipWith2.out")
  }

  "reusing a flow in a graph" in {
    val topHeadSink    = Sink.head[Int]
    val bottomHeadSink = Sink.head[Int]
    val sharedDoubler  = Flow[Int].map(_ * 2)

    val g =
      RunnableGraph.fromGraph(GraphDSL.create(topHeadSink, bottomHeadSink)((_, _)) {
        implicit builder => (topHS, bottomHS) =>
          import GraphDSL.Implicits._
          val broadcast = builder.add(Broadcast[Int](2))
          Source.single(1) ~> broadcast.in

          broadcast ~> sharedDoubler ~> topHS.in
          broadcast ~> sharedDoubler ~> bottomHS.in
          ClosedShape
      })
    val (topFuture, bottomFuture) = g.run()
    Await.result(topFuture, 300.millis) shouldEqual 2
    Await.result(bottomFuture, 300.millis) shouldEqual 2
  }

  "building a reusable component" in {

    // Форма представляет входные и выходные порты модуля обработки многократного использования
    case class PriorityWorkerPoolShape[In, Out](jobsIn: Inlet[In], priorityJobsIn: Inlet[In], resultsOut: Outlet[Out])
        extends Shape {

      // Важно предоставить список всех входных и выходных данных
      // порты со стабильным порядком. Дубликаты не допускаются.
      override val inlets: immutable.Seq[Inlet[_]] =
        jobsIn :: priorityJobsIn :: Nil
      override val outlets: immutable.Seq[Outlet[_]] =
        resultsOut :: Nil

      // Форма должна иметь возможность создать копию самой себя. В принципе
      // это означает новый экземпляр с копиями портов
      override def deepCopy() =
        PriorityWorkerPoolShape(jobsIn.carbonCopy(), priorityJobsIn.carbonCopy(), resultsOut.carbonCopy())

    }
    object PriorityWorkerPool {
      def apply[In, Out](worker: Flow[In, Out, Any],
                         workerCount: Int): Graph[PriorityWorkerPoolShape[In, Out], NotUsed] = {

        GraphDSL.create() { implicit b ⇒
          import GraphDSL.Implicits._

          val priorityMerge = b.add(MergePreferred[In](1))
          val balance       = b.add(Balance[In](workerCount))
          val resultsMerge  = b.add(Merge[Out](workerCount))

          // После слияния приоритетных и обычных заданий мы подводим их к балансировщику
          priorityMerge ~> balance

          // Подключите каждый из выходов балансира к рабочему потоку
          // затем слейте их обратно
          for (i ← 0 until workerCount)
            balance.out(i) ~> worker ~> resultsMerge.in(i)

          // Теперь мы выставляем входные порты priorityMerge и вывод
          //  resultsMerge как наши порты PriorityWorkerPool
          //  - все аккуратно завернутые в нашу область, специфичную для Формы
          PriorityWorkerPoolShape(jobsIn = priorityMerge.in(0),
                                  priorityJobsIn = priorityMerge.preferred,
                                  resultsOut = resultsMerge.out)
        }

      }

    }

    def println(s: Any): Unit = ()

    val worker1 = Flow[String].map("step 1 " + _)
    val worker2 = Flow[String].map("step 2 " + _)

    RunnableGraph
      .fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._

        val priorityPool1 = b.add(PriorityWorkerPool(worker1, 4))
        val priorityPool2 = b.add(PriorityWorkerPool(worker2, 2))

        Source(1 to 100).map("job: " + _) ~> priorityPool1.jobsIn
        Source(1 to 100).map("priority job: " + _) ~> priorityPool1.priorityJobsIn

        priorityPool1.resultsOut ~> priorityPool2.jobsIn
        Source(1 to 100).map("one-step, priority " + _) ~> priorityPool2.priorityJobsIn

        priorityPool2.resultsOut ~> Sink.foreach(println)
        ClosedShape
      })
      .run()

    import FanInShape.{Init, Name}

    class PriorityWorkerPoolShape2[In, Out](_init: Init[Out] = Name("PriorityWorkerPool"))
        extends FanInShape[Out](_init) {
      protected override def construct(i: Init[Out]) = new PriorityWorkerPoolShape2(i)

      val jobsIn         = newInlet[In]("jobsIn")
      val priorityJobsIn = newInlet[In]("priorityJobsIn")
      // Outlet[Out] with name "out" is automatically created
    }

  }

  "access to materialized value" in {
    import GraphDSL.Implicits._
    val foldFlow: Flow[Int, Int, Future[Int]] = Flow.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) {
      implicit builder ⇒ fold ⇒ FlowShape(fold.in, builder.materializedValue.mapAsync(4)(identity).outlet)
    })

    Await.result(Source(1 to 10).via(foldFlow).runWith(Sink.head), 3.seconds) should ===(55)

    import GraphDSL.Implicits._
    // Это не может произвести никакого значения:
    val cyclicFold: Source[Int, Future[Int]] =
      Source.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) { implicit builder ⇒ fold ⇒
        // - Fold cannot complete until its upstream mapAsync completes
        // - mapAsync cannot complete until the materialized Future produced by
        //   fold completes
        // As a result this Source will never emit anything, and its materialited
        // Future will never complete
        builder.materializedValue.mapAsync(4)(identity) ~> fold
        SourceShape(builder.materializedValue.mapAsync(4)(identity).outlet)
      })
  }

}
