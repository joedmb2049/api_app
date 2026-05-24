package forex.http
package rates

import cats.effect.Sync
import cats.syntax.flatMap._
import forex.programs.RatesProgram
import forex.programs.rates.{ Protocol => RatesProgramProtocol, errors => ProgramErrors }
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

class RatesHttpRoutes[F[_]: Sync](rates: RatesProgram[F]) extends Http4sDsl[F] {

  import Converters._, QueryParams._, Protocol._

  private[http] val prefixPath = "/rates"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    /**
     * Handles GET requests to /rates to retrieve currency exchange rates.
     *
     * Expects 'from' and 'to' currency codes as query parameters.
     * Delegates to the RatesProgram to fetch the rate and handles various
     * program-level errors, returning appropriate HTTP status codes and
     * descriptive error messages.
     *
     * @param GET The HTTP GET method.
     * @param Root The root path of the service.
     * @param FromQueryParam The 'from' currency query parameter.
     * @param ToQueryParam The 'to' currency query parameter.
     * @return An HTTP response containing the rate or an error.
     */
    case GET -> Root :? FromQueryParam(from) +& ToQueryParam(to) =>
      rates.get(RatesProgramProtocol.GetRatesRequest(from, to)).flatMap {
        case Right(rate) =>
          Ok(rate.asGetApiResponse)
        case Left(ProgramErrors.Error.RateLookupFailed(msg)) =>
          NotFound(msg)
        case Left(ProgramErrors.Error.RateLimitExceeded(msg)) =>
          TooManyRequests(msg)
        case Left(ProgramErrors.Error.ConnectionFailed(msg)) =>
          ServiceUnavailable(msg)
        case Left(otherError) =>
          InternalServerError(s"An unexpected error occurred: ${otherError.getMessage}")
      }
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
