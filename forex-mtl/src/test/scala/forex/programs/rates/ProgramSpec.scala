package forex.programs.rates

import cats.effect._
import cats.implicits._
import forex.domain._
import forex.programs.rates.errors.{Error => ProgramError}
import forex.services.rates.{Algebra => RatesService, errors => RatesServiceErrors}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.time.Instant

class ProgramSpec extends AnyWordSpec with Matchers {

  // Helper for creating a dummy Rate.Pair
  def dummyRatePair(from: String, to: String): Rate.Pair =
    Rate.Pair(Currency.fromString(from).get, Currency.fromString(to).get)

  // Helper for creating a dummy Rate
  def dummyRate(from: String, to: String, price: Double, timestamp: Instant): Rate =
    Rate(dummyRatePair(from, to), Price(BigDecimal(price)), Timestamp(timestamp))

  "RatesProgram" should {

    "successfully return a rate when the service succeeds" in {
      val pair = dummyRatePair("USD", "JPY")
      val request = Protocol.GetRatesRequest(pair.from, pair.to)
      val expectedRate = dummyRate("USD", "JPY", 100.0, Instant.now())

      val mockRatesService = new RatesService[IO] {
        override def get(p: Rate.Pair): IO[RatesServiceErrors.Error Either Rate] =
          IO.pure(expectedRate.asRight[RatesServiceErrors.Error])
      }

      val program = Program[IO](mockRatesService)
      val result = program.get(request).unsafeRunSync()

      result shouldBe Right(expectedRate)
    }

    "map OneFrameLookupFailed from service to RateLookupFailed program error" in {
      val pair = dummyRatePair("EUR", "GBP")
      val request = Protocol.GetRatesRequest(pair.from, pair.to)

      val mockRatesService = new RatesService[IO] {
        override def get(p: Rate.Pair): IO[RatesServiceErrors.Error Either Rate] =
          IO.pure(RatesServiceErrors.Error.OneFrameLookupFailed("Service lookup failed").asLeft[Rate])
      }

      val program = Program[IO](mockRatesService)
      val result = program.get(request).unsafeRunSync()

      result shouldBe Left(ProgramError.RateLookupFailed("Service lookup failed"))
    }

    "map OneFrameRateLimitExceeded from service to RateLimitExceeded program error" in {
      val pair = dummyRatePair("GBP", "USD")
      val request = Protocol.GetRatesRequest(pair.from, pair.to)

      val mockRatesService = new RatesService[IO] {
        override def get(p: Rate.Pair): IO[RatesServiceErrors.Error Either Rate] =
          IO.pure(RatesServiceErrors.Error.OneFrameRateLimitExceeded("Service rate limit exceeded").asLeft[Rate])
      }

      val program = Program[IO](mockRatesService)
      val result = program.get(request).unsafeRunSync()

      result shouldBe Left(ProgramError.RateLimitExceeded("Service rate limit exceeded"))
    }

    "map OneFrameConnectionFailed from service to ConnectionFailed program error" in {
      val pair = dummyRatePair("AUD", "NZD")
      val request = Protocol.GetRatesRequest(pair.from, pair.to)

      val mockRatesService = new RatesService[IO] {
        override def get(p: Rate.Pair): IO[RatesServiceErrors.Error Either Rate] =
          IO.pure(RatesServiceErrors.Error.OneFrameConnectionFailed("Service connection failed").asLeft[Rate])
      }

      val program = Program[IO](mockRatesService)
      val result = program.get(request).unsafeRunSync()

      result shouldBe Left(ProgramError.ConnectionFailed("Service connection failed"))
    }
  }
}
