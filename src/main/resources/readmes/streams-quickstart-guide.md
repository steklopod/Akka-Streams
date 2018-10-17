## Краткое руководство по потокам

### Зависимость
Чтобы использовать потоки Akka, добавьте модуль в свой проект:

```sbtshell
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.5.17"
```

### Первые шаги
Обычно поток начинается с источника, мы также начинаем в Akka Stream. Прежде чем создать его, мы импортируем 
полный набор средств потоковой передачи:

```scala
import akka.stream._
import akka.stream.scaladsl._
```
Если вы хотите выполнить образцы кода во время чтения руководства по быстрому началу работы, вам также понадобятся следующие импорт:

```scala
import akka.{ NotUsed, Done }
import akka.actor.ActorSystem
import akka.util.ByteString
import scala.concurrent._
import scala.concurrent.duration._
import java.nio.file.Paths
```

И объект для хранения вашего кода, например:
```scala
object Main extends App {
  // код здесь
}
```
Теперь мы начнем с довольно простого источника, испускающего целые числа от 1 до 100:

```scala
val source: Source[Int, NotUsed] = Source(1 to 100)
```
Тип источника параметризуется двумя типами: первый - это тип элемента, который этот источник испускает, а второй может 
сигнализировать о том, что запуск источника приводит к некоторому вспомогательному значению (например, сетевой источник 
может предоставлять информацию о связанном порте или одноранговой сети адрес). В тех случаях, когда не создается 
вспомогательная информация, используется тип `akka.NotUsed`, и простой класс целых чисел, несомненно, попадает в эту категорию.

Создав этот источник, мы имеем описание того, как испускать первые 100 натуральных чисел, но этот источник еще не 
активирован. Чтобы получить эти номера, мы должны запустить его:

```scala
source.runForeach(i ⇒ println(i))(materializer)
```
Эта строка дополнит источник функцией пользователя - в этом примере мы печатаем номера в консоли и передаем эту 
небольшую настройку потока aктору, который его запускает. Эта активация сигнализируется тем, что «run» является 
частью имени метода; есть и другие методы, которые управляют потоками Akka, и все они следуют этому шаблону.

При запуске этого источника в файле scala.App вы можете заметить, что он не завершается, потому что ActorSystem 
никогда не прерывается. К счастью, `runForeach` возвращает `Future[Done]`, которое разрешается, когда поток заканчивается:

```scala
val done: Future[Done] = source.runForeach(i ⇒ println(i))(materializer)

implicit val ec = system.dispatcher
done.onComplete(_ ⇒ system.terminate())
```

Вы можете задаться вопросом, где создается aктор, который управляет потоком, и вы, вероятно, также спрашиваете 
себя, что означает этот материализатор. Чтобы получить это значение, нам сначала нужно создать систему aктора:

```scala
implicit val system = ActorSystem("QuickStart")
implicit val materializer = ActorMaterializer()
```
Существуют и другие способы создания материализатора, например, из `ActorContext` при использовании потоков изнутри 
акторов. `Materializer` - это фабрика для движков потока, это то, что делает потоки запускаемыми - вам не нужно 
беспокоиться ни о каких деталях прямо сейчас, кроме того, что вам нужно для вызова любого из методов запуска в источнике. 
Материализатор подбирается неявно, если он опущен из аргументов вызова метода запуска, что мы будем делать в следующем.

Самое приятное в потоках Akka - это то, что `Source` - это описание того, что вы хотите запустить, и, как проект 
архитектора, его можно использовать повторно, встроенный в более крупный дизайн. Мы можем выбрать преобразование 
источника целых чисел и вместо этого записать его в файл:

```scala
val factorials = source.scan(BigInt(1))((acc, next) ⇒ acc * next)

val result: Future[IOResult] =
  factorials
    .map(num ⇒ ByteString(s"$num\n"))
    .runWith(FileIO.toPath(Paths.get("factorials.txt")))
```

Сначала мы используем оператор сканирования для выполнения вычисления по всему потоку: начиная с номера 1 (`BigInt (1)`) 
мы умножаем каждый из входящих чисел один за другим; операция сканирования испускает начальное значение, а затем каждый 
результат вычисления. Это дает ряд факториальных чисел, которые мы откладываем в качестве источника (`Source`) для последующего 
повторного использования. Важно помнить, что пока ничего не вычисляется, это описание того, что мы хотим вычислить, 
как только мы запускаем поток. Затем мы преобразуем полученную серию чисел в поток объектов `ByteString`, описывающих 
строки в текстовом файле. Затем этот поток запускается путем присоединения файла в качестве получателя данных. В 
терминологии потоков Акка это называется раковиной. `IOResult` - это тип, в котором операции ввода-вывода возвращаются в 
потоках Akka, чтобы рассказать вам, сколько байтов или элементов было потреблено, и был ли поток прекращен нормально 
или исключительно.

#### Пример с использованием браузера
Вот еще один пример, который вы можете редактировать и запускать в браузере:
```scala
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._

final case class Author(handle: String)

final case class Hashtag(name: String)

final case class Tweet(author: Author, timestamp: Long, body: String) {
  def hashtags: Set[Hashtag] = body.split(" ").collect {
    case t if t.startsWith("#") ⇒ Hashtag(t.replaceAll("[^#\\w]", ""))
  }.toSet
}

val akkaTag = Hashtag("#akka")

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

  implicit val system = ActorSystem("reactive-tweets")
  implicit val materializer = ActorMaterializer()

  tweets
    .map(_.hashtags)                // Получить все наборы хэштегов ...
    .reduce(_ ++ _)                 // ... и сводить их к одному набору, удаляя дубликаты во всех твитах
    .mapConcat(identity)            // Сглаживание потока твитов в поток хэштегов
    .map(_.name.toUpperCase)        // Преобразование всех хэштегов в верхний регистр
    .runWith(Sink.foreach(println)) // Прикрепите поток к раковине, который, наконец, распечатает хэштеги
```


[<= содержание](https://github.com/steklopod/Akka-Streams/blob/master/readme.md)

_Если этот проект окажется полезным тебе - нажми на кнопочку **`★`** в правом верхнем углу._

