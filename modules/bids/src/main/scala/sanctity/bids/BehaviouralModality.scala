package sanctity.bids

import bids4s.*
import cats.effect.Async
import sanctity.application.{BlobStore, ViewCompiler}
import sanctity.kernel.*

object BehaviouralModality extends ModalityRules:
  import EntityKey.*
  private val entities = Vector(EntityRule(Subject, true), EntityRule(Session, false), EntityRule(Task, true),
    EntityRule(Acquisition, false), EntityRule(Run, false, Some("[0-9]+")))
  private val kinds = Vector("beh", "events").map(name =>
    BidsKind.from(name, Vector("tsv", "json")).fold(e => throw new IllegalArgumentException(e.message), identity))
  val descriptor = BidsModality("beh", "Behaviour", CoverageLevel.Assessed,
    Vector(BidsDatatypeSpec("beh", "beh", DatatypeScope.Raw, entities, kinds)), Some(this))
  val editable = Set("beh.tsv", "events.tsv")
  def view(context: BidsContext): ViewCompiler = new BehaviouralViewCompiler
  def assess[F[_]: Async](store: BlobStore[F], context: BidsContext, manifest: Manifest, initial: Interpretation) =
    if manifest.files.exists(f => BehaviouralChecks.inBehaviour(f.path)) then new BehaviouralChecks[F](store, context).enrich(manifest, initial)
    else Async[F].pure((initial, Vector.empty[ContentCheck], Vector.empty[Finding]))
