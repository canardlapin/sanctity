package sanctity.bids

import bids4s.BidsContext
import cats.syntax.all.*
import sanctity.kernel.*

private[bids] object BehaviouralEdits:
  def validate(source: Release, edits: Vector[FileEdit], context: BidsContext): Either[Problem, Unit] =
    val mapping = edits.map(e => e.source -> e.destination).toMap
    def require(ok: Boolean, why: String): Either[Problem, Unit] = Either.cond(ok, (), Problem.Unsupported(why))
    def sidecar(p: RelPath): RelPath = RelPath.from(p.value.stripSuffix(".tsv") + ".json").toOption.get
    for
      _ <- edits.filter(e => BehaviouralChecks.inBehaviour(e.source)).traverse_ { e =>
        e.destination.traverse_ { to =>
          require(to.parent == e.source.parent && context.parsePath(bids4s.BidsPath(to.value)).isRight &&
            context.parseGeneric(e.source.fileName).toOption.exists(a => context.parseGeneric(to.fileName).toOption.exists(b =>
              Vector(bids4s.EntityKey.Subject, bids4s.EntityKey.Session).forall(k => a.entities.get(k) == b.entities.get(k)))),
            "behavioural moves preserve participant/session and datatype placement")
        }
      }
      _ <- source.interpretation.facts.filter(f => f.suffix == "beh" && mapping.contains(f.path)).traverse_ { root =>
        val companions = source.interpretation.relationships.toVector.flatMap(_.relationships)
          .filter(r => r.from == root.path && r.kind == BehaviouralChecks.EventsKind).flatMap(_.to)
        val local = Vector(sidecar(root.path)).filter(source.manifest.byPath.contains) ++ companions.filter(_.parent == root.path.parent)
          .flatMap(p => Vector(p) ++ Vector(sidecar(p)).filter(source.manifest.byPath.contains))
        for
          _ <- require(local.forall(mapping.contains), "behavioural recording moves/removals must include local sidecars and events")
          _ <- local.traverse_ { p =>
            mapping(root.path) match
              case None => require(mapping(p).isEmpty, "local behavioural family must be removed together")
              case Some(to) =>
                val suffix = if p.fileName.contains("_events.") then "_events" else "_beh"
                val extension = if p.fileName.endsWith(".json") then ".json" else ".tsv"
                require(mapping(p).exists(_.value == to.value.stripSuffix("_beh.tsv") + suffix + extension),
                  "local behavioural family must move with coherent task/run entities")
          }
          _ <- companions.filterNot(_.parent == root.path.parent).traverse_ { p =>
            mapping(root.path).traverse_ { to =>
              val applies = for
                name <- context.parseGeneric(to.fileName).toOption
                event <- context.parseGeneric(p.fileName).toOption
              yield event.entities.values.forall((k, v) => name.entities.get(k).contains(v))
              require(applies.contains(true), "localize inherited events before a rename that changes their applicability")
            }
          }
        yield ()
      }
    yield ()
