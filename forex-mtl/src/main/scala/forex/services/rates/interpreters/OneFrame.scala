package forex.services.rates.interpreters


import cats.implicits._
import cats.Show
import cats.effect.Sync
import cats.effect.concurrent.Ref
import org.slf4j.LoggerFactory
import io.circe.generic.auto._
import io.circe.parser.decode
import forex.services.rates.Algebra
import forex.services.rates.errors.Error
import forex.domain.{Price, Timestamp, Currency, Rate}
import org.http4s.client.Client
import org.http4s.Uri
import org.http4s.headers.Authorization
import org.http4s.Credentials
import org.http4s.AuthScheme
import org.http4s.implicits._
import org.http4s.{Request, Method}
import java.time.{Instant, Duration, LocalDate, OffsetDateTime, ZoneOffset}
/**
 * Represents a single rate response from the One-Frame API.
 *
 * @param from The "from" currency code.
 * @param to The "to" currency code.
 * @param bid The bid price.
 * @param ask The ask price.
 * @param price The mid price.
 * @param time_stamp The timestamp of the rate.
 */
case class OneFrameRate(
    from: String,
    to: String,
    bid: Double,
    ask: Double,
    price: Double,
    time_stamp: Instant
)

/**
 * Represents a cached currency exchange rate with its timestamp.
 *
 * @param rate The `Rate` domain object.
 * @param timestamp The `Instant` when this rate was fetched and cached.
 */
case class CachedRate(rate: Rate, timestamp: Instant)

/**
 * Live implementation of the OneFrame exchange rates service.
 *
 * This interpreter interacts with the external One-Frame API to fetch currency exchange rates.
 * It includes caching mechanisms to ensure rate freshness (not older than 5 minutes)
 * and to work around the One-Frame API's daily request limit by intelligently
 * re-using cached rates and tracking API usage.
 *
 * @param client The http4s client used to make HTTP requests.
 */
class LiveInterpreter[F[_]: Sync](client: Client[F])(implicit showCurrency: Show[Currency]) extends Algebra[F] {

  private val BaseUri: Uri = uri"http://localhost:8080"
  private val AuthToken: String = "10dc303535874aeccc86a8251e6992f5"
  private val DailyRateLimit: Long = 1000L
  private val RateFreshnessDuration: Duration = Duration.ofMinutes(5)

  /**
   * A concurrent, immutable cache for storing `CachedRate` objects, keyed by `Rate.Pair`.
   */
  private val rateCache: Ref[F, Map[Rate.Pair, CachedRate]] =
    Ref.unsafe(Map.empty[Rate.Pair, CachedRate])

  /**
   * A concurrent, immutable reference for tracking daily API usage and the date of the last reset.
   * Stored as a tuple: (current daily usage count, date of last reset).
   */
  private val dailyUsage: Ref[F, (Long, LocalDate)] =
    Ref.unsafe((0L, LocalDate.now()))

  private val logger = LoggerFactory.getLogger(getClass.getName)

  override def get(pair: Rate.Pair): F[forex.services.rates.errors.Error Either Rate] = {
    rateCache.get.flatMap { cachedValue =>
      cachedValue.get(pair) match {
        case Some(cached) if isFresh(cached) =>
          logger.info(s"Cache hit for $pair, rate is fresh.")
          (cached.rate).asRight[forex.services.rates.errors.Error].pure[F]
        case _ =>
          fetchRateFromOneFrame(pair)
      }
    }
  }

  /**
   * Checks if a cached rate is still fresh (not older than `RateFreshnessDuration`).
   *
   * @param cached The `CachedRate` to check.
   * @return `true` if the rate is fresh, `false` otherwise.
   */
  private def isFresh(cached: CachedRate): Boolean =
    Duration.between(cached.timestamp, Instant.now()).compareTo(RateFreshnessDuration) < 0

  /**
   * Fetches the exchange rate for a given currency pair from the One-Frame API.
   * This method incorporates rate limiting and updates the cache upon successful fetch.
   *
   * @param pair The `Rate.Pair` to fetch.
   * @return An `F[Error Either Rate]` indicating success or failure.
   */
  private def fetchRateFromOneFrame(pair: Rate.Pair): F[Error Either Rate] = {
    dailyUsage.modify { case (usage, date) =>
      val today = LocalDate.now()
      val (currentUsage, currentDate) =
        if (date.isBefore(today)) (0L, today) else (usage, date)

      if (currentUsage >= DailyRateLimit) {
        logger.warn(s"Daily rate limit exceeded for One-Frame API.")
        ((currentUsage, currentDate), (Error.OneFrameRateLimitExceeded("Daily rate limit exceeded"): Error).asLeft[Rate].pure[F].widen[Either[Error, Rate]])
      } else {
        ((currentUsage + 1, currentDate),
         makeApiRequest(pair).flatMap {
           case Right(rate) =>
             rateCache.update(_ + (pair -> CachedRate(rate, Instant.now()))).as(rate.asRight[Error])
           case Left(error) =>
             ((error: Error)).asLeft[Rate].pure[F].widen[Either[Error, Rate]]
         })
      }
    }.flatten
  }

  /**
   * Makes the actual HTTP request to the One-Frame API.
   *
   * @param pair The `Rate.Pair` to request.
   * @return An `F[Error Either Rate]` indicating the parsed rate or an error.
   */
  private def makeApiRequest(pair: Rate.Pair): F[Error Either Rate] = {
    val requestUri = (BaseUri / "rates").withQueryParam("pair", s"${showCurrency.show(pair.from)}${showCurrency.show(pair.to)}")
    val request = Request[F](method = Method.GET, uri = requestUri).withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, AuthToken)))

    client.expect[String](request).attempt.flatMap {
      case Right(responseBody) =>
        decode[List[OneFrameRate]](responseBody) match {
          case Right(oneFrameRates) =>
            oneFrameRates.headOption match {
              case Some(oneFrameRate) =>
                Rate(
                  pair = pair,
                  price = Price(oneFrameRate.price),
                  timestamp = Timestamp(OffsetDateTime.ofInstant(oneFrameRate.time_stamp, ZoneOffset.UTC))
                ).asRight[Error].pure[F].widen[Either[Error, Rate]]
              case None =>
                Error.OneFrameLookupFailed(s"No rate found for $pair in One-Frame response.").asLeft[Rate].pure[F].widen[Either[Error, Rate]]
            }
          case Left(decodeError) =>
            Error.OneFrameLookupFailed(s"Failed to parse One-Frame response: ${decodeError.getMessage}").asLeft[Rate].pure[F].widen[Either[Error, Rate]]
        }
      case Left(throwable) =>
        Error.OneFrameConnectionFailed(s"Failed to connect to One-Frame API: ${throwable.getMessage}").asLeft[Rate].pure[F].widen[Either[Error, Rate]]
    }
  }
}
