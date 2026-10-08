package sanctity.application

import sanctity.kernel.*

/** A network boundary obtains its context here; it never accepts a principal from the caller.
  * Catalog implementations must recheck credential liveness inside their transactions.
  */
trait Authenticator[F[_]]:
  def authenticate(bearer: String): F[Either[Problem, AccessContext]]
