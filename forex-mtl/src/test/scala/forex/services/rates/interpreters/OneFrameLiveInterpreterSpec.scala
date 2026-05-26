package forex.services.rates.interpreters

import cats.effect._
import forex.domain._
import forex.services.rates.Algebra
import forex.services.rates.errors._
import org.http4s._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import io.circe.syntax._
import io.circe.generic.auto._
import java.time.{Instant, LocalDate}
import scala.concurrent.ExecutionContext
import java.time.{OffsetDateTime, ZoneOffset}
import cats.kernel.Eq

class OneFrameLiveInterpreterSpec extends AnyWordSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val cs: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)

  // Helper for creating a dummy OneFrameRate
  def dummyOneFrameRate(from: String, to: String, price: Double, timestamp: Instant): OneFrameRate =
    OneFrameRate(from, to, price - 0.1, price + 0.1, price, timestamp)

  // Helper for creating a dummy Rate.Pair
  def dummyRatePair(from: String, to: String): Rate.Pair =
    Rate.Pair(Currency.fromString(from), Currency.fromString(to))

  // Helper for creating a dummy Rate
  def dummyRate(from: String, to: String, price: Double, timestamp: OffsetDateTime): Rate =
    Rate(dummyRatePair(from, to), Price(BigDecimal(price)), Timestamp(timestamp))

  // Define a custom Eq for OffsetDateTime to ignore nanoseconds for comparison
  implicit val eqInstant: Eq[OffsetDateTime] = Eq.fromUniversalEquals
  implicit val eqTimestamp: Eq[Timestamp] = (x: Timestamp, y: Timestamp) => eqInstant.eqv(x.value, y.value)
  implicit val eqPrice: Eq[Price] = Eq.fromUniversalEquals
  implicit val eqRate: Eq[Rate] = (x: Rate, y: Rate) =>
    x.pair == y.pair && eqPrice.eqv(x.price, y.price) && eqTimestamp.eqv(x.timestamp, y.timestamp)

  "OneFrameLiveInterpreter" should {

    "successfully fetch a new rate if cache is empty" in {
      val pair = dummyRatePair("USD", "JPY")
      val timestampInstant = Instant.now()
      val timestamp = OffsetDateTime.ofInstant(timestampInstant, ZoneOffset.UTC)
      val oneFrameResponse = List(dummyOneFrameRate("USD", "JPY", 100.0, timestampInstant)).asJson.noSpaces

      val client: Client[IO] = Client.fromHttpApp[IO](HttpApp[IO] {
        case req @ GET -> Root / "rates" =>
          req.uri.query.params.get("pair") match {
            case Some(v) if v.contains("USDJPY") => Ok(oneFrameResponse)
            case _ => NotFound()
          }
        case _ =>
          NotFound()
      })

      val interpreter: Algebra[IO] = new LiveInterpreter[IO](client)
      val result = interpreter.get(pair).unsafeRunSync()

      result shouldBe Right(dummyRate("USD", "JPY", 100.0, timestamp))
    }

    "exceed daily rate limit and return an error" in {
      val pair = dummyRatePair("AUD", "NZD")
      val client: Client[IO] = Client.fromHttpApp[IO](HttpApp[IO] {
        case _ =>
          fail("Should not call external API if rate limit exceeded")
      })

      val interpreter = new LiveInterpreter[IO](client)
      interpreter.dailyUsage.set((interpreter.DailyRateLimit, LocalDate.now())).unsafeRunSync()

      val result = interpreter.get(pair).unsafeRunSync()
      result shouldBe Left(Error.OneFrameRateLimitExceeded("Daily rate limit exceeded"))
    }

    "reset daily rate limit at a new day" in {
      val pair = dummyRatePair("CAD", "CHF")
      val yesterday = LocalDate.now().minusDays(1)
      val timestampInstant = Instant.now()
      val timestamp = OffsetDateTime.ofInstant(timestampInstant, ZoneOffset.UTC)
      val oneFrameResponse = List(dummyOneFrameRate("CAD", "CHF", 0.75, timestampInstant)).asJson.noSpaces

      val client: Client[IO] = Client.fromHttpApp[IO](HttpApp[IO] {
        case req @ GET -> Root / "rates" =>
          req.uri.query.params.get("pair") match {
            case Some(v) if v.contains("CADCHF") => Ok(oneFrameResponse)
            case _ => NotFound()
          }
        case _ =>
          NotFound()
      })

      val interpreter = new LiveInterpreter[IO](client)
      interpreter.dailyUsage.set((interpreter.DailyRateLimit, yesterday)).unsafeRunSync() // Set usage for yesterday

      val result = interpreter.get(pair).unsafeRunSync()
      result shouldBe Right(dummyRate("CAD", "CHF", 0.75, timestamp))

      // Verify usage was reset and incremented for today
      val (usage, date) = interpreter.dailyUsage.get.unsafeRunSync()
      usage shouldBe 1L
      date shouldBe LocalDate.now()
    }

    "return connection failed error on HTTP client failure" in {
      val pair = dummyRatePair("GBP", "SGD")
      val client: Client[IO] = Client.fromHttpApp[IO](HttpApp[IO] {
        case req @ GET -> Root / "rates" =>
          req.uri.query.params.get("pair") match {
            case Some(v) if v.contains("GBPSGD") => InternalServerError("Simulated HTTP error")
            case _ => NotFound()
          }
        case _ =>
          NotFound()
      })

      val interpreter = new LiveInterpreter[IO](client)
      val result = interpreter.get(pair).unsafeRunSync()

      result shouldBe Left(Error.OneFrameConnectionFailed("Failed to connect to One-Frame API: Server returned 500 status code"))
    }

    "return lookup failed error on invalid JSON response" in {
      val pair = dummyRatePair("SGD", "EUR")
      val invalidJsonResponse = """{"invalid_json"}"""

      val client: Client[IO] = Client.fromHttpApp[IO](HttpApp[IO] {
        case req @ GET -> Root / "rates" =>
          req.uri.query.params.get("pair") match {
            case Some(v) if v.contains("SGDEUR") => Ok(invalidJsonResponse)
            case _ => NotFound()
          }
        case _ =>
          NotFound()
      })

      val interpreter = new LiveInterpreter[IO](client)
      val result = interpreter.get(pair).unsafeRunSync()

      result match {
        case Left(Error.OneFrameLookupFailed(msg)) => msg should include("Failed to parse One-Frame response")
        case _ => fail("Expected OneFrameLookupFailed")
      }
    }

    "return lookup failed error if no rate found in response" in {
      val pair = dummyRatePair("CHF", "CAD")
      val emptyResponse = List.empty[OneFrameRate].asJson.noSpaces

      val client: Client[IO] = Client.fromHttpApp[IO](HttpApp[IO] {
        case req @ GET -> Root / "rates" =>
          req.uri.query.params.get("pair") match {
            case Some(v) if v.contains("CHFCAD") => Ok(emptyResponse)
            case _ => NotFound()
          }
        case _ =>
          NotFound()
      })

      val interpreter = new LiveInterpreter[IO](client)
      val result = interpreter.get(pair).unsafeRunSync()

      result shouldBe Left(Error.OneFrameLookupFailed("No rate found for CHF/CAD in One-Frame response."))
    }
  }
}
