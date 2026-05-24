package forex

import cats.effect.{ ConcurrentEffect, Timer }
import forex.config.ApplicationConfig
import forex.http.rates.RatesHttpRoutes
import forex.services._
import forex.programs._
import org.http4s._
import org.http4s.implicits._
import org.http4s.server.middleware.{ AutoSlash, Timeout }

class Module[F[_]: Concurrent: Timer](config: ApplicationConfig) {

  private val ratesService: RatesService[F] = RatesServices.live[F](client)

  /**
   * The RatesProgram instance, which encapsulates the business logic for rates.
   * It coordinates calls to the RatesService.
   */
  private val ratesProgram: RatesProgram[F] = RatesProgram[F](ratesService)

  /**
   * The HTTP routes for the rates API endpoint.
   */
  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  /**
   * Type alias for partial middleware, applied to `HttpRoutes`.
   */
  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  /**
   * Type alias for total middleware, applied to `HttpApp`.
   */
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  /**
   * Middleware for routes, including `AutoSlash`.
   */
  private val routesMiddleware: PartialMiddleware = {
    { http: HttpRoutes[F] =>
      AutoSlash(http)
    }
  }

  /**
   * Middleware for the entire application, including `Timeout`.
   */
  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(http)
  }

  /**
   * The combined HTTP routes of the application.
   */
  private val http: HttpRoutes[F] = ratesHttpRoutes

  /**
   * The final HTTP application, with all routes and middleware applied.
   */
  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)
}
