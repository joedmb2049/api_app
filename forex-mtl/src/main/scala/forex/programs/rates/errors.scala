package forex.programs.rates

import forex.services.rates.errors.{ Error => RatesServiceError }

object errors {

  sealed trait Error extends Exception
  object Error {
    final case class RateLookupFailed(msg: String) extends Error
    /**
     * Represents a situation where an external rate provider's rate limit has been exceeded.
     * @param msg A descriptive error message.
     */
    final case class RateLimitExceeded(msg: String) extends Error
    /**
     * Represents a failure to connect to an external rate provider.
     * @param msg A descriptive error message.
     */
    final case class ConnectionFailed(msg: String) extends Error
  }

  def toProgramError(error: RatesServiceError): Error = error match {
    case RatesServiceError.OneFrameLookupFailed(msg)    => Error.RateLookupFailed(msg)
    case RatesServiceError.OneFrameRateLimitExceeded(msg) => Error.RateLimitExceeded(msg)
    case RatesServiceError.OneFrameConnectionFailed(msg) => Error.ConnectionFailed(msg)
  }
}
