package forex.services.rates

/**
 * Defines error types specific to the rates service layer.
 */
object errors {

  sealed trait Error
  object Error {
    /**
     * Represents a failure to look up a rate from the One-Frame API.
     * @param msg A descriptive error message.
     */
    final case class OneFrameLookupFailed(msg: String) extends Error
    /**
     * Represents a situation where the One-Frame API rate limit has been exceeded.
     * @param msg A descriptive error message.
     */
    final case class OneFrameRateLimitExceeded(msg: String) extends Error
    /**
     * Represents a failure to connect to the One-Frame API.
     * @param msg A descriptive error message.
     */
    final case class OneFrameConnectionFailed(msg: String) extends Error
   }

}
