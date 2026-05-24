package forex.http.rates

import cats.effect._
import cats.implicits._
import forex.domain._
import forex.programs.rates.{Algebra => RatesProgram, Protocol => RatesProgramProtocol, errors => ProgramErrors}
import org.http4s._
import org.http4s.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import io.circe.literal._
import java.time.Instant
import scala.concurrent.ExecutionContext
import cats.effect.unsafe.implicits.global
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.circe.CirceEntityDecoder._

class RatesHttpRoutesSpec extends AnyWordSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val cs: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)

  // Helper for creating a dummy Rate.Pair
  def dummyRatePair(from: String, to: String): Rate.Pair =
    Rate.Pair(Currency.fromString(from).get, Currency.fromString(to).get)

  // Helper for creating a dummy Rate
  def dummyRate(from: String, to: String, price: Double, timestamp: Instant): Rate =
    Rate(dummyRatePair(from, to), Price(BigDecimal(price)), Timestamp(timestamp))

  "RatesHttpRoutes" should {

    "return a rate on a successful request" in {
      val pair = dummyRatePair("USD", "JPY")
      val timestamp = Instant.now()
      val expectedRate = dummyRate("USD", "JPY", 100.0, timestamp)

      val mockRatesProgram = new RatesProgram[IO] {
        override def get(request: RatesProgramProtocol.GetRatesRequest): IO[ProgramErrors.Error Either Rate] =
          IO.pure(expectedRate.asRight[ProgramErrors.Error])
      }

      val routes = new RatesHttpRoutes[IO](mockRatesProgram).routes.orNotFound
      val request = Request[IO](Method.GET, uri"/rates?from=USD&to=JPY")
      val response = routes.run(request).unsafeRunSync()

      response.status shouldBe Status.Ok
      response.as[Rate].unsafeRunSync() shouldBe expectedRate
    }

    "return 404 Not Found for RateLookupFailed error" in {
      val mockRatesProgram = new RatesProgram[IO] {
        override def get(request: RatesProgramProtocol.GetRatesRequest): IO[ProgramErrors.Error Either Rate] =
          IO.pure(ProgramErrors.Error.RateLookupFailed("Currency pair not found").asLeft[Rate])
      }

      val routes = new RatesHttpRoutes[IO](mockRatesProgram).routes.orNotFound
      val request = Request[IO](Method.GET, uri"/rates?from=UNKNOWN&to=CURRENCY")
      val response = routes.run(request).unsafeRunSync()

      response.status shouldBe Status.NotFound
      response.as[String].unsafeRunSync() shouldBe "Currency pair not found"
    }

    "return 429 Too Many Requests for RateLimitExceeded error" in {
      val mockRatesProgram = new RatesProgram[IO] {
        override def get(request: RatesProgramProtocol.GetRatesRequest): IO[ProgramErrors.Error Either Rate] =
          IO.pure(ProgramErrors.Error.RateLimitExceeded("Daily rate limit exceeded").asLeft[Rate])
      }

      val routes = new RatesHttpRoutes[IO](mockRatesProgram).routes.orNotFound
      val request = Request[IO](Method.GET, uri"/rates?from=USD&to=JPY")
      val response = routes.run(request).unsafeRunSync()

      response.status shouldBe Status.TooManyRequests
      response.as[String].unsafeRunSync() shouldBe "Daily rate limit exceeded"
    }

    "return 503 Service Unavailable for ConnectionFailed error" in {
      val mockRatesProgram = new RatesProgram[IO] {
        override def get(request: RatesProgramProtocol.GetRatesRequest): IO[ProgramErrors.Error Either Rate] =
          IO.pure(ProgramErrors.Error.ConnectionFailed("Failed to connect to external service").asLeft[Rate])
      }

      val routes = new RatesHttpRoutes[IO](mockRatesProgram).routes.orNotFound
      val request = Request[IO](Method.GET, uri"/rates?from=USD&to=JPY")
      val response = routes.run(request).unsafeRunSync()

      response.status shouldBe Status.ServiceUnavailable
      response.as[String].unsafeRunSync() shouldBe "Failed to connect to external service"
    }

    "return 500 Internal Server Error for unexpected errors" in {
      val mockRatesProgram = new RatesProgram[IO] {
        override def get(request: RatesProgramProtocol.GetRatesRequest): IO[ProgramErrors.Error Either Rate] =
          IO.pure(new RuntimeException("Unexpected error").asLeft[Rate])
      }

      val routes = new RatesHttpRoutes[IO](mockRatesProgram).routes.orNotFound
      val request = Request[IO](Method.GET, uri"/rates?from=USD&to=JPY")
      val response = routes.run(request).unsafeRunSync()

      response.status shouldBe Status.InternalServerError
      response.as[String].unsafeRunSync() should include("An unexpected error occurred")
    }
  }
}
