package sanctity.bids

import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked, SemanticInterpreter}
import sanctity.kernel.*

/** The repository's interpreter for a raw BIDS dataset.
  *
  * It interprets the files in scope with every installed modality, keeps the rest, and records a coverage level for
  * every file. Assessed modalities contribute their content checks; described modalities are read from names and
  * sidecar metadata alone.
  */
final class RepositoryBidsInterpreter[F[_]: Async](store: BlobStore[F], policy: AdmissionPolicy = AdmissionPolicy.Inclusive)
    extends SemanticInterpreter[F]:
  private val core = new BidsInterpreter[F](store, RepositoryBidsContext.Repository)
  private val behavioural = new BehaviouralChecks[F](store, RepositoryBidsContext.Repository)

  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] =
    val partition = BidsScopes.partition(manifest)
    for
      scoped <- Checked.lift[F, Manifest](BidsScopes.scoped(manifest, partition))
      base <- core.prepare(scoped)
      (initial, assessed) = base
      enrichment <-
        if scoped.files.exists(f => BehaviouralChecks.inBehaviour(f.path)) then behavioural.enrich(scoped, initial)
        else Async[F].pure((initial, Vector.empty[ContentCheck], Vector.empty[Finding]))
      (enriched, checks, findings) = enrichment
      levels <- Checked.lift[F, CoverageLevels](BidsScopes.levels(partition, enriched))
      // Bind the result to the whole manifest: the preserved files are part of the release even though no rule read them.
      bound <- Checked.lift[F, Interpretation](Interpretation.from(manifest.id,
        enriched.profile.copy(name = RepositoryBidsInterpreter.Profile, coverage = RepositoryBidsContext.Statement),
        enriched.artifacts, enriched.facts, enriched.participants, enriched.queryVersion, enriched.relationships,
        enriched.declaredBidsVersion, enriched.observations, Some(levels)))
      reported = BidsScopes.relax(assessed.findings ++ findings, policy) ++ BidsScopes.notices(partition, policy)
    yield bound -> Assessment.from(bound, policy.name, reported, assessed.checks ++ checks)

object RepositoryBidsInterpreter:
  val Profile: String = "sanctity-bids-v1"
