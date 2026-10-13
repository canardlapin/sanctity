package sanctity.application

import cats.MonadThrow
import cats.syntax.all.*
import sanctity.kernel.*

/** Absence is relative to an explicit, authorized universe of accepted derivative releases. */
final class ScientificResults[F[_]: MonadThrow](catalog: Catalog[F]):
  def coverage(ctx: AccessContext, derivatives: Vector[ReleaseRef], query: ObservationQuery): F[Either[Problem, Vector[DerivativeCoverage]]] =
    Checked.attempt {
      for
        _ <- Checked.lift[F, Unit](Either.cond(query.valid, (), Problem.Unsupported("invalid observation query")))
        _ <- query.inputs.traverse_(i => catalog.release(ctx, i.at).flatMap(Checked.lift[F, Release]).flatMap(r =>
          Checked.lift[F, Unit](Either.cond(i.matches(r), (), Problem.Integrity("query input differs from accepted artifact")))))
        found <- catalog.observations(ctx, derivatives, query).flatMap(Checked.lift[F, Vector[ObservationHit]])
      yield query.inputs.map(i => DerivativeCoverage(i, found.filter(_.observation.source == i)))
    }
