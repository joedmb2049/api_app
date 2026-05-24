package forex.programs.rates

import cats.Functor
import cats.data.EitherT
import errors.{Error => ProgramError}
import forex.domain._
import forex.services.RatesService
import forex.services.rates.errors.{Error => ServiceError}

class Program[F[_]: Functor](
    ratesService: RatesService[F]
) extends Algebra[F] {

  override def get(request: Protocol.GetRatesRequest): F[ProgramError Either Rate] = {
    // Convert the request to Rate.Pair format
    val pair = Rate.Pair(request.from, request.to)

    // Call the rates service (which is either live or dummy)
    EitherT[F, ServiceError, Rate](ratesService.get(pair))
      .leftMap((err: ServiceError) => errors.toProgramError(err))
      .value
  }

}

object Program {

  def apply[F[_]: Functor](
      ratesService: RatesService[F]
   ): Algebra[F] = new Program[F](ratesService)

}
