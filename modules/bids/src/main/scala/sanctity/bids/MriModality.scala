package sanctity.bids

import bids4s.*
import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked, ViewCompiler}
import sanctity.kernel.*

object MriModality extends ModalityRules:
  val descriptor = BidsModality("mri", "MRI", CoverageLevel.Assessed, BidsRegistry.Builtin.specs, Some(this))
  val editable = Set("dwi.bval", "dwi.bvec", "events.tsv")
  def view(context: BidsContext): ViewCompiler = new MriViewCompiler(context)

  override def assess[F[_]: Async](store: BlobStore[F], context: BidsContext, manifest: Manifest, initial: Interpretation) =
    for
      enriched <- new StimulusBindings[F](store).enrich(manifest, initial)
      (interpretation, stimulusFindings) = enriched
      inspected <- new MriHeaderChecks[F](store).inspect(manifest, interpretation)
      (headerChecks, headers) = inspected
      dwiChecks <- new DwiContentChecks[F](store).assess(manifest, interpretation, headers)
      eventChecks <- new EventContentChecks[F](store).assess(manifest, interpretation, headers)
      echoChecks <- new MultiEchoChecks[F](store).assess(manifest, interpretation, headers)
      sbrefChecks <- Checked.lift[F, Vector[ContentCheck]](SbrefBindings.assess(manifest, interpretation, headers))
    yield (interpretation, dwiChecks ++ eventChecks ++ headerChecks ++ echoChecks ++ sbrefChecks, stimulusFindings)

  override def relationships(named: BidsManifest, resolved: Map[BidsPath, BidsMetadataResolution],
      artifacts: Vector[ArtifactMetadata], targets: Vector[ArtifactFacts]) =
    val findings = Vector.newBuilder[Finding]
    def relative(p: BidsPath) = RelPath.from(p.value).fold(e => throw sanctity.application.Failure(e), identity)
    val families = bids4s.BidsPhaseDifferenceResolver.fromResolved(named, resolved)
    families.issues.foreach(i => findings += Finding("Phasediff" + i.code.toString, Severity.Error, Some(relative(i.path)), i.message))
    val familyLinks = families.groups.map { g =>
      ScientificRelationship(relative(g.phase), "bids.phasediff-magnitudes", g.magnitudes.map(relative), None,
        g.magnitudes.map(p => RelationshipEvidence.CompanionSelection(relative(g.phase), relative(p),
          bids4s.BidsPhaseDifferenceResolver.Version, g.datasetRoot.map(relative), g.entities, Vector(relative(p)))))
    }
    val echoes = bids4s.BidsMultiEchoResolver.fromResolved(named, resolved)
    echoes.issues.foreach(i => findings += Finding("MultiEcho" + i.code.toString, Severity.Error, Some(relative(i.path)), i.message))
    val echoLinks = echoes.groups.flatMap { g =>
      g.members.map { member =>
        ScientificRelationship(relative(member), "bids.multi-echo-bold", g.members.filterNot(_ == member).map(relative), None,
          g.members.filterNot(_ == member).map(p => RelationshipEvidence.CompanionSelection(relative(member), relative(p),
            bids4s.BidsMultiEchoResolver.Version, g.datasetRoot.map(relative), g.entities, Vector(relative(p)))) ++
            g.evidence.map(e => RelationshipEvidence(relative(e.artifact), relative(e.document), e.field)))
      }
    }
    val (resolvedSbrefs, sbrefFindings) = SbrefBindings.resolve(targets)
    val resolvedArtifacts = artifacts.map(_.path).toSet
    val sbrefLinks = resolvedSbrefs.filter(r => (r.from +: r.to).forall(resolvedArtifacts))
    if sbrefLinks.size != resolvedSbrefs.size then
      findings += Finding("SbrefMetadata", Severity.Error, None, "Applicable SBRef or BOLD metadata could not be resolved")
    sbrefFindings.foreach(findings += _)
    val fieldmaps = BidsFieldmapResolver.fromResolved(named, resolved)
    val phasePairs = bids4s.BidsPhasePairResolver.fromResolved(named, resolved)
    phasePairs.issues.foreach(i => findings += Finding("PhasePair" + i.code.toString, Severity.Error, Some(relative(i.path)), i.message))
    val pairLinks = phasePairs.groups.flatMap { g =>
      g.members.map { member =>
        ScientificRelationship(relative(member), "bids.phase-pair-family", g.members.filterNot(_ == member).map(relative), None,
          g.members.filterNot(_ == member).map(p => RelationshipEvidence.CompanionSelection(relative(member), relative(p),
            bids4s.BidsPhasePairResolver.Version, g.datasetRoot.map(relative), g.entities, Vector(relative(p)))) ++
            g.evidence.map(e => RelationshipEvidence(relative(e.artifact), relative(e.document), e.field)))
      }
    }
    fieldmaps.issues.foreach { issue =>
      findings += Finding("Fieldmap" + issue.code.toString, if issue.isError then Severity.Error else Severity.Warning,
        Some(relative(issue.path)), issue.field.fold(issue.message)(f => s"$f: ${issue.message}"))
    }
    val links = fieldmaps.associations.map { a =>
      ScientificRelationship(relative(a.target), a.mechanism match
        case bids4s.BidsFieldmapMechanism.B0FieldSource => "bids.b0-field-source"
        case bids4s.BidsFieldmapMechanism.IntendedFor => "bids.intended-for",
        a.sources.map(relative), a.identifier,
        a.evidence.map(e => RelationshipEvidence(relative(e.artifact), relative(e.document), e.field)))
    }
    val companionResolver = BidsCompanionResolver(named)
    val resolvedPaths = artifacts.map(_.path).toSet
    val companionLinks = artifacts.flatMap { artifact =>
      named.context.parseGeneric(artifact.path.fileName).toOption.toVector
        .filter(n => Set("nii", "nii.gz")(n.extension)).flatMap { name =>
          val kinds = name.kind match
            case "dwi" => Vector(BidsCompanionKind.Bval, BidsCompanionKind.Bvec)
            case "bold" => Vector(BidsCompanionKind.Events)
            case _ => Vector.empty
          val selected = kinds.flatMap { kind =>
            companionResolver.resolve(BidsPath(artifact.path.value), kind) match
              case Left(error) =>
                findings += Finding("CompanionResolution", Severity.Error, Some(artifact.path), error.message)
                None
              case Right(resolution) => resolution.outcome match
                case BidsCompanionOutcome.Selected(candidate) =>
                  if resolution.applicable.forall(c => resolvedPaths(relative(c.path))) then
                    Some(candidate -> RelationshipEvidence.CompanionSelection(artifact.path, relative(candidate.path),
                      BidsCompanionResolver.Version + ":" + kind.toString, resolution.datasetRoot.map(relative),
                      candidate.matchedEntities, resolution.applicable.map(c => relative(c.path))))
                  else
                    findings += Finding("CompanionMetadata", Severity.Error, Some(artifact.path),
                      "an applicable companion has unresolved metadata")
                    None
                case BidsCompanionOutcome.Missing =>
                  if kind != BidsCompanionKind.Events || !name.entities.get(bids4s.EntityKey.Task).contains("rest") then
                    findings += Finding("CompanionMissing" + kind.toString,
                      if kind == BidsCompanionKind.Events then Severity.Warning else Severity.Error,
                      Some(artifact.path), s"no applicable ${kind.suffix}.${kind.extension} companion")
                  None
                case BidsCompanionOutcome.Ambiguous(paths) =>
                  findings += Finding("CompanionAmbiguous", Severity.Error, Some(artifact.path),
                    s"${kind.toString}: multiple applicable files at one level: ${paths.map(_.value).mkString(", ")}")
                  None
                case other =>
                  findings += Finding("CompanionUnsupported", Severity.Error, Some(artifact.path), other.toString)
                  None
          }
          if selected.isEmpty || selected.size != kinds.size then Vector.empty
          else Vector(ScientificRelationship(artifact.path,
            if name.kind == "dwi" then "bids.dwi-gradients" else "bids.task-events",
            selected.map((c, _) => relative(c.path)), None, selected.map(_._2)))
        }
    }
    (links ++ companionLinks ++ familyLinks ++ pairLinks ++ echoLinks ++ sbrefLinks, findings.result())
