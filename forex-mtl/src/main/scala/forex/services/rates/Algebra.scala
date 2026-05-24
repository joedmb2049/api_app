package forex.services.rates

import forex.domain.Rate
import forex.services.rates.errors.{Error => ServiceError}

trait Algebra[F[_]] {
  def get(pair: Rate.Pair): F[ServiceError Either Rate]
}
