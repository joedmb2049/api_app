package forex.services.rates

import cats.effect.Sync
import org.http4s.client.Client
import cats.Applicative
import interpreters._

/**
 * Factory object for creating instances of the `RatesService` Algebra.
 * Provides both a live implementation (interacting with the One-Frame API)
 * and a dummy implementation for testing purposes.
 */
object Interpreters {
  /**
   * Returns a live interpreter for the `RatesService` Algebra.
   * This implementation interacts with the external One-Frame API.
   *
   * @param F The effect type, constrained by `Sync`.
   * @param client The http4s client used to make HTTP requests.
   * @return A `RatesService` instance that fetches live rates.
   */
  def live[F[_]: Sync](client: Client[F]): Algebra[F] = new LiveInterpreter[F](client)

  /**
   * Returns a dummy interpreter for the `RatesService` Algebra.
   * This implementation provides hardcoded rates for testing.
   *
   * @param F The effect type, constrained by `Applicative`.
   * @return A `RatesService` instance that provides dummy rates.
   */
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()

}
