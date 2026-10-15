package sanctity.bids

import bids4s.{BidsContext, BidsDatatypeSpec, BidsKind, DatatypeScope, EntityKey, EntityRule}

/** Additive, explicitly bounded repository coverage. The upstream MRI context stays available. */
object RepositoryBidsContext:
  private val behavioural = BidsDatatypeSpec(
    name = "beh", folder = "beh", scope = DatatypeScope.Raw,
    entities = Vector(
      EntityRule(EntityKey.Subject, true), EntityRule(EntityKey.Session, false),
      EntityRule(EntityKey.Task, true), EntityRule(EntityKey.Acquisition, false),
      EntityRule(EntityKey.Run, false, Some("[0-9]+"))
    ),
    kinds = Vector("beh", "events").map(s => BidsKind.from(s, Vector("tsv", "json")).fold(e =>
      throw new IllegalArgumentException(e.message), identity))
  )
  val Behavioural: BidsContext = BidsContext.from(
    "sanctity-mri-behavioural-v1", BidsContext.Default.registry.withDatatype(behavioural),
    Some("1.11.2"),
    "Existing bounded MRI coverage plus raw behavioural beh/events TSV and JSON. " +
      "One subject/session/task/run table is one behavioural recording. Events require a corresponding beh table. " +
      "Strict table content and local stimuli; no physiology, HED validation, signal processing or full BIDS conformance."
  ).fold(e => throw new IllegalArgumentException(e.message), identity)
