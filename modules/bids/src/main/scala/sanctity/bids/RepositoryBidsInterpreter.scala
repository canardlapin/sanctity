package sanctity.bids

import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, SemanticInterpreter}
import sanctity.kernel.*

/** Compose the existing MRI interpretation with an additive behavioural profile when needed. */
final class RepositoryBidsInterpreter[F[_]: Async](store: BlobStore[F]) extends SemanticInterpreter[F]:
  private val mri = new BidsInterpreter[F](store)
  private val combined = new BidsInterpreter[F](store, RepositoryBidsContext.Behavioural)
  private val behavioural = new BehaviouralChecks[F](store, RepositoryBidsContext.Behavioural)

  def prepare(manifest: Manifest): F[(Interpretation, Assessment)] =
    val hasBehaviour = manifest.files.exists(f => BehaviouralChecks.inBehaviour(f.path))
    val unsupported = manifest.files.filter(f => f.path.value.split('/').dropRight(1).exists(Set("eeg", "meg", "ieeg", "nirs", "pet")))
      .map(f => Finding("UnsupportedScientificModality", Severity.Error, Some(f.path), "this repository profile has no qualified interpreter for this modality"))
    (if hasBehaviour then combined else mri).prepare(manifest).flatMap { (initial, assessment) =>
      if hasBehaviour then behavioural.enrich(manifest, initial).map { (result, checks, findings) =>
        result -> Assessment.from(result, "repository-mri-behavioural-admission-v1", assessment.findings ++ findings ++ unsupported, assessment.checks ++ checks)
      }
      else Async[F].pure(initial -> Assessment.from(initial, assessment.policy, assessment.findings ++ unsupported, assessment.checks))
    }
