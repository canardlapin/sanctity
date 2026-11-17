package sanctity.app

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import sanctity.application.*
import sanctity.bids.{MriViewCompiler, BidsChangeCompiler, SbrefBindings}
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import sanctity.processing.*
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}

/** These cases run unchanged against reference and PostgreSQL catalogs. */
abstract class CatalogCalibrationInputSuite extends CatalogMultiEchoTemporalSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private val members = Vector(1, 2, 3).map(i => p(s"sub-01/func/sub-01_task-cue_run-01_echo-${i}_bold.nii"))
  private val mask = DisclosureProfile(Set.empty, Set("License", "TaskName", "RepetitionTime", "EchoTime", "SliceTiming", "PhaseEncodingDirection", "TotalReadoutTime", "B0FieldSource", "B0FieldIdentifier", "IntendedFor"),
    eventFields = Some(Set("onset", "duration", "trial_type", "stim_file")))
  private val contract = TemporalStatistics.CalibratedContract
  private def raw(s: RepositorySystem, geometry: Boolean = true) = new NativeRepository[IO](s.catalog, s.store,
    new PayloadBidsInterpreter[IO](s.store, qualifyGeometry = true, qualifyCalibration = geometry), IdSource.random[IO])
  private def derivatives(s: RepositorySystem) = new NativeRepository[IO](s.catalog, s.store,
    new TemporalDerivativeInterpreter[IO](s.store), IdSource.random[IO])
  private def submissions(s: RepositorySystem) = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives(s), IdSource.random[IO])
  private def producer(s: RepositorySystem) = new TemporalProducer[IO](s.catalog, s.store, raw(s), IdSource.random[IO], TemporalFixture.python)
  private def inputs(s: RepositorySystem) = new ProcessingInputs[IO](s.catalog, raw(s),
    new ScientificViews[IO](s.catalog, s.store, raw(s), new MriViewCompiler, IdSource.random[IO]), new TemporalInputPlanner)
  private def install(root: Path, corpus: String = "calibration-inputs"): IO[Unit] = TestFiles.remove(root.resolve("source")) *> IO.blocking {
    val names = MriFixtures.json(corpus + "/provenance.json").hcursor.downField("files").focus.get.asObject.get.keys
    names.foreach { name =>
      val out = root.resolve("source").resolve(name)
      Files.createDirectories(out.getParent)
      val _ = Files.write(out, MriFixtures.bytes(corpus + "/dataset/" + name))
    }
  }
  private def original(s: RepositorySystem, root: Path): IO[Release] = for
    preview <- checked(raw(s).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
    _ = assert(preview.candidate.assessment.admissible, preview.candidate.assessment.errors.toString)
    accepted <- checked(raw(s).accept(s.context, preview.candidate.id, key("multi-source")))
    release <- checked(s.catalog.release(s.context, accepted.release))
  yield release
  private def bundle(s: RepositorySystem, ctx: AccessContext, source: Release): IO[Release] = for
    selected <- checked(s.catalog.select(ctx, Vector(source.ref), ScientificQuery(artifacts = ArtifactPredicate.All(Vector(
      ArtifactPredicate.Suffix("bold"), ArtifactPredicate.Entity("echo", "2"))))))
    _ = assertEquals(selected.hits.size, 1)
    destination <- raw(s).create(ctx, s.dataset.custodian)
    ready <- checked(inputs(s).preparePrivate(ctx, source.ref, selected.id, destination.id, mask, contract))
    accepted <- checked(raw(s).accept(ctx, ready.candidate.id, key("multi-bundle")))
    release <- checked(inputs(s).bundle(ctx, accepted.release))
  yield release
  private def collect(s: RepositorySystem, ctx: AccessContext, draft: Draft, evidence: ProcessingEvidence): IO[Preview] =
    evidence.expected.traverse_(f => checked(submissions(s).collectStaged(ctx, draft.id, f.path))) *> checked(submissions(s).prepare(ctx, draft.id))

  private val reference = p("sub-01/func/sub-01_task-cue_run-01_sbref.nii")
  private val epis = Vector("AP", "PA").map(d => p(s"sub-01/fmap/sub-01_dir-${d}_epi.nii"))
  private def metadata(root: Path, path: RelPath)(f: io.circe.Json => io.circe.Json): Unit =
    val file = root.resolve("source").resolve(path.value.stripSuffix(".nii") + ".json")
    val value = new io.circe.jawn.JawnParser().parse(Files.readString(file)).toOption.get
    val _ = Files.writeString(file, f(value).spaces2)
  private def patch(root: Path, path: RelPath)(f: ByteBuffer => Unit): Unit =
    val file = root.resolve("source").resolve(path.value)
    val bytes = Files.readAllBytes(file)
    f(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    val _ = Files.write(file, bytes)
  private def readiness(preview: Preview): InputReadiness =
    new TemporalInputPlanner().assess(preview.candidate.manifest, preview.candidate.interpretation, preview.candidate.assessment,
      Vector(members(1)), contract).toOption.get
  private def preview(s: RepositorySystem, root: Path): IO[Preview] =
    checked(raw(s).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
  private def indexed(root: Path, echoes: Vector[Int], retainShared: Boolean = false): Unit =
    val file = root.resolve("source").resolve(reference.value)
    echoes.foreach { echo =>
      val out = file.resolveSibling(file.getFileName.toString.replace("_sbref", s"_echo-${echo}_sbref"))
      Files.copy(file, out)
      Files.writeString(out.resolveSibling(out.getFileName.toString.stripSuffix(".nii") + ".json"),
        s"""{"TaskName":"cue","EchoTime":${echo / 100.0},"PhaseEncodingDirection":"j","TotalReadoutTime":0.05}""")
    }
    if !retainShared then
      Files.delete(file)
      Files.delete(file.resolveSibling(file.getFileName.toString.stripSuffix(".nii") + ".json"))

  test("calibrated v3 expands one echo and binds shared reference and explicit EPI pair") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      ready = new TemporalInputPlanner().assess(source.manifest, source.interpretation, source.assessment, Vector(members(1)), contract).toOption.get
      _ = assert(ready.ready, ready.requirements.toString)
      _ = assertEquals(ready.roots, members)
      closure = source.interpretation.relationshipClosure(Vector(members(1))).toOption.get
      _ = assert((members ++ epis :+ reference).forall(closure.artifacts.contains))
      links = source.interpretation.relationships.get.relationships.filter(_.kind == SbrefBindings.Kind)
      _ = assertEquals(links.size, 3)
      _ = assert(links.forall(_.evidence.exists {
        case RelationshipEvidence.CompanionSelection(_, document, rule, _, _, applicable) =>
          document == reference && rule == SbrefBindings.Version && applicable == Vector(reference)
        case _ => false
      }))
      legacy = new TemporalInputPlanner().assess(source.manifest, source.interpretation, source.assessment, Vector(members(1)), TemporalStatistics.MultiEchoContract).toOption.get
      _ = assert(legacy.ready)
      other <- raw(s, false).create(s.context, s.dataset.custodian)
      v2 <- checked(raw(s, false).importNative(s.context, other.id, new NativeDirectorySource[IO](root.resolve("source"))))
      _ = assert(!readiness(v2).ready)
    yield () }
  }

  test("calibrated v3 also accepts a single-echo BOLD acquisition with its shared reference") {
    fixture { (s, root) => for
      _ <- install(root)
      _ <- IO.blocking {
        members.zipWithIndex.foreach { (member, n) =>
          Vector("nii", "json").foreach { ext =>
            val from = root.resolve("source").resolve(member.value.stripSuffix(".nii") + "." + ext)
            if n > 0 then Files.delete(from)
            else Files.move(from, from.resolveSibling(from.getFileName.toString.replace("_echo-1", "")))
          }
        }
      }
      candidate <- preview(s, root)
      selected = p(members.head.value.replace("_echo-1", ""))
      ready = new TemporalInputPlanner().assess(candidate.candidate.manifest, candidate.candidate.interpretation,
        candidate.candidate.assessment, Vector(selected), contract).toOption.get
      _ = assert(ready.ready, ready.requirements.toString)
      _ = assertEquals(ready.roots, Vector(selected))
    yield () }
  }

  test("redundant IntendedFor declarations preserve an explicit B0 pair while separate groups remain ambiguous") {
    fixture { (s, root) => for
      _ <- install(root)
      _ <- IO.blocking {
        epis.foreach(e => metadata(root, e)(_.mapObject(_.add("IntendedFor",
          io.circe.Json.arr(members.map(m => io.circe.Json.fromString("bids::" + m.value))*)))))
      }
      candidate <- preview(s, root)
      _ = assert(readiness(candidate).ready, readiness(candidate).requirements.toString)
    yield () }
  }

  test("complete indexed SBRefs and normalized numeric entities qualify") {
    fixture { (s, root) => for
      _ <- install(root)
      _ <- IO.blocking {
        indexed(root, Vector(1, 2, 3))
        Vector(1, 2, 3).foreach { echo =>
          Vector("nii", "json").foreach { ext =>
            val from = root.resolve("source").resolve(reference.value.replace("_sbref.nii", s"_echo-${echo}_sbref.$ext"))
            Files.move(from, from.resolveSibling(from.getFileName.toString.replace("run-01", "run-1").replace(s"echo-$echo", s"echo-0$echo")))
          }
        }
      }
      candidate <- preview(s, root)
      _ = assert(candidate.candidate.assessment.admissible, candidate.candidate.assessment.errors.toString)
      _ = assert(readiness(candidate).ready, readiness(candidate).requirements.toString)
    yield () }
  }

  Vector("missing-reference", "wrong-run", "incomplete-indexed", "mixed-reference", "duplicate-reference", "echo-time",
    "reference-rank", "reference-task", "reference-shift", "epi-shift", "same-direction", "wrong-axis", "missing-readout",
    "identical-named-groups", "overlapping-named-groups", "cross-session-reference", "negative-reference-echo", "incoherent-family", "invalid-direction", "negative-readout", "missing-fieldmaps", "singleton-fieldmap", "independent-singletons", "bad-reference-payload", "bad-epi-payload").foreach { variant =>
    test("calibration boundary: " + variant) {
      fixture { (s, root) => for
        _ <- install(root)
        _ <- IO.blocking {
          def file(p: RelPath) = root.resolve("source").resolve(p.value)
          def field(p: RelPath, k: String, value: io.circe.Json): Unit = metadata(root, p)(_.mapObject(_.add(k, value)))
          def remove(p: RelPath): Unit =
            Files.delete(file(p))
            Files.delete(file(p).resolveSibling(p.fileName.stripSuffix(".nii") + ".json"))
          variant match
            case "missing-reference" => remove(reference)
            case "identical-named-groups" | "overlapping-named-groups" =>
              field(p("task-cue_bold.nii"), "B0FieldSource", io.circe.Json.arr(io.circe.Json.fromString("cal1"), io.circe.Json.fromString("cal2")))
              (if variant == "identical-named-groups" then epis else epis.take(1)).foreach(e =>
                field(e, "B0FieldIdentifier", io.circe.Json.arr(io.circe.Json.fromString("cal1"), io.circe.Json.fromString("cal2"))))
            case "cross-session-reference" =>
              Vector("nii", "json").foreach { ext =>
                val from = root.resolve("source").resolve(reference.value.stripSuffix(".nii") + "." + ext)
                val to = root.resolve("source/sub-01/ses-02/func").resolve(from.getFileName.toString.replace("sub-01_", "sub-01_ses-02_"))
                Files.createDirectories(to.getParent)
                Files.move(from, to)
              }
            case "negative-reference-echo" => field(reference, "EchoTime", io.circe.Json.fromDoubleOrNull(-0.1))
            case "incoherent-family" => field(members(1), "B0FieldSource", io.circe.Json.fromString("missing"))
            case "wrong-run" =>
              Vector("nii", "json").foreach { ext =>
                val from = root.resolve("source").resolve(reference.value.stripSuffix(".nii") + "." + ext)
                Files.move(from, from.resolveSibling(from.getFileName.toString.replace("run-01", "run-2")))
              }
            case "incomplete-indexed" => indexed(root, Vector(1, 2))
            case "mixed-reference" => indexed(root, Vector(1, 2, 3), true)
            case "duplicate-reference" =>
              val out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file(reference).resolveSibling(reference.fileName + ".gz")))
              try out.write(Files.readAllBytes(file(reference))) finally out.close()
            case "echo-time" =>
              indexed(root, Vector(1, 2, 3))
              field(p(reference.value.replace("_sbref", "_echo-2_sbref")), "EchoTime", io.circe.Json.fromDoubleOrNull(0.04))
            case "reference-rank" =>
              val _ = Files.write(file(reference), MriFixtures.bytes("calibration-inputs/dataset/" + members.head.value))
            case "reference-task" => field(reference, "TaskName", io.circe.Json.fromString("other"))
            case "reference-shift" => patch(root, reference)(_.putFloat(268, 1))
            case "epi-shift" => patch(root, epis.head)(_.putFloat(292, 1))
            case "same-direction" => field(epis.last, "PhaseEncodingDirection", io.circe.Json.fromString("j"))
            case "wrong-axis" => field(epis.last, "PhaseEncodingDirection", io.circe.Json.fromString("i-"))
            case "invalid-direction" => field(epis.last, "PhaseEncodingDirection", io.circe.Json.fromString("AP"))
            case "negative-readout" => field(epis.last, "TotalReadoutTime", io.circe.Json.fromDoubleOrNull(-0.1))
            case "missing-readout" => metadata(root, reference)(_.mapObject(_.remove("TotalReadoutTime")))
            case "missing-fieldmaps" =>
              epis.foreach(remove)
              metadata(root, p("task-cue_bold.nii"))(_.mapObject(_.remove("B0FieldSource")))
            case "singleton-fieldmap" => remove(epis.last)
            case "independent-singletons" =>
              metadata(root, p("task-cue_bold.nii"))(_.mapObject(_.remove("B0FieldSource")))
              epis.foreach(e => metadata(root, e)(_.mapObject(_.remove("B0FieldIdentifier").add("IntendedFor",
                io.circe.Json.arr(members.map(m => io.circe.Json.fromString("bids::" + m.value))*)))))
            case "bad-reference-payload" => patch(root, reference)(_.putFloat(352, Float.NaN))
            case "bad-epi-payload" =>
              val _ = Files.write(file(epis.last), Files.readAllBytes(file(epis.last)).dropRight(4))
            case _ => sys.error(variant)
        }
        candidate <- preview(s, root)
        ready = readiness(candidate)
        _ = assert(!ready.ready, variant)
        _ = variant match
          case "missing-reference" | "wrong-run" => assert(ready.requirements.exists(r => r.code == "reference" && r.status == RequirementStatus.Missing))
          case "independent-singletons" | "overlapping-named-groups" | "identical-named-groups" => assert(ready.requirements.exists(r => r.code == "calibration-group" && r.status == RequirementStatus.Ambiguous))
          case "reference-shift" | "epi-shift" => assert(candidate.candidate.assessment.admissible)
          case "incomplete-indexed" | "mixed-reference" | "duplicate-reference" => assert(candidate.candidate.assessment.errors.exists(_.code == "SbrefAmbiguousOrIncomplete")); assert(ready.requirements.exists(r => r.code == "reference" && r.status == RequirementStatus.Ambiguous))
          case _ => ()
      yield () }
    }
  }

  test("calibrated readiness rejects missing or substituted exact companion evidence") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      _ = Vector(NiftiPayload.Rule, NiftiGeometry.Rule, CalibrationInputs.ReferenceGeometry, CalibrationInputs.FieldmapGeometry).foreach { rule =>
        val target = if rule == NiftiPayload.Rule || rule == NiftiGeometry.Rule then reference else members(1)
        Vector(false, true).foreach { substitute =>
          val checks = source.assessment.checks.flatMap { c =>
            if c.rule != rule || c.target != target then Vector(c)
            else if !substitute then Vector.empty
            else Vector(ContentCheck.from(c.rule, c.target, c.inputs.map(i => if i.role.startsWith("image") then
              i.copy(blob = source.manifest.byPath(members.head).asInstanceOf[Entry.File].blob) else i), c.facts, c.findings).toOption.get)
          }
          val a = Assessment.from(source.interpretation, "calibration-test", source.assessment.findings, checks)
          assert(!new TemporalInputPlanner().assess(source.manifest, source.interpretation, a, Vector(members(1)), contract).toOption.get.ready, rule)
        }
      }
    yield () }
  }

  test("SBRef curation rejects partial or split moves and preserves complete acquisition identities") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      changes = new ScientificChanges[IO](s.catalog, s.store, raw(s), new BidsChangeCompiler)
      images = members :+ reference
      edits = images.flatMap(p => Vector(p, this.p(p.value.stripSuffix(".nii") + ".json"))).map(p =>
        FileEdit.Move(p, this.p(p.value.replace("run-01", "run-2"))))
      partial <- changes.plan(s.context, source.ref, edits.dropRight(2))
      split <- changes.plan(s.context, source.ref, edits.map {
        case FileEdit.Move(from, to) if from == reference => FileEdit.Move(from, p(to.value.replace("run-2", "run-3")))
        case other => other
      })
      remove <- changes.plan(s.context, source.ref, Vector(FileEdit.Remove(reference)))
      _ = assert(partial.isLeft && split.isLeft && remove.isLeft)
      plan <- checked(changes.plan(s.context, source.ref, edits))
      revised <- checked(changes.prepare(s.context, source.ref, edits, plan.id, "Move complete functional acquisition"))
      _ = assert(revised.candidate.assessment.admissible, revised.candidate.assessment.errors.toString)
      _ = images.foreach { path =>
        assertEquals(revised.candidate.manifest.byPath(p(path.value.replace("run-01", "run-2"))).asInstanceOf[Entry.File].blob,
          source.manifest.byPath(path).asInstanceOf[Entry.File].blob)
      }
      _ <- checked(raw(s).accept(s.context, revised.candidate.id, key("calibration-move")))
    yield () }
  }

  test("curation refuses unqualified diffusion-reference image edits") {
    fixture { (s, root) => for
      _ <- install(root)
      dwiReference = p("sub-01/dwi/sub-01_sbref.nii")
      _ <- IO.blocking {
        val file = root.resolve("source").resolve(dwiReference.value)
        Files.createDirectories(file.getParent)
        val _ = Files.write(file, MriFixtures.bytes("calibration-inputs/dataset/" + reference.value))
      }
      source <- original(s, root)
      plan <- new ScientificChanges[IO](s.catalog, s.store, raw(s), new BidsChangeCompiler).plan(s.context, source.ref,
        Vector(FileEdit.Remove(dwiReference)))
      _ = assert(plan.isLeft)
    yield () }
  }

  test("calibrated export re-import retains bytes, associations, metadata and readiness") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      input <- bundle(s, s.context, source)
      _ = assert((members ++ epis :+ reference).forall(input.manifest.byPath.contains))
      _ <- checked(inputs(s).materialize(s.context, input.ref, new DirectoryExport[IO](root.resolve("roundtrip"))))
      dest <- raw(s).create(s.context, s.dataset.custodian)
      again <- checked(raw(s).importNative(s.context, dest.id, new NativeDirectorySource[IO](root.resolve("roundtrip"))))
      _ = assert(readiness(again).ready, readiness(again).requirements.toString)
      _ = assertEquals(again.candidate.manifest.files.map(f => f.path -> f.blob), input.manifest.files.map(f => f.path -> f.blob))
      _ = assertEquals(again.candidate.interpretation.relationships.get.relationships, input.interpretation.relationships.get.relationships)
      _ = assertEquals(again.candidate.interpretation.artifacts, input.interpretation.artifacts)
    yield () }
  }

  test("calibrated maps publish retained bytes, preserve historical correction, query separate echoes and enforce revocation") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      reader <- IdSource.random[IO].next[PrincipalTag].map(AccessContext(_))
      grant <- checked(s.catalog.grant(s.context, source.ref.dataset, reader.principal, Some(source.ref.release), Set(Action.ReadMetadata, Action.ReadContent)))
      input <- bundle(s, reader, source)
      output <- derivatives(s).create(reader, s.dataset.custodian)
      spec = RunSpec(input.ref, input.activity.bundle.get.id, output.id, None, contract)
      runs = new LocalProcessingRuns[IO](s.catalog, s.store, producer(s))
      evidence <- checked(runs.execute(reader, spec, key("multi-run"), root.resolve("execution")))
      _ = assertEquals(evidence.targets, members)
      _ = assertEquals(evidence.expected.size, 13)
      _ <- evidence.expected.filter(_.path.value.endsWith(".nii")).traverse_ { f =>
        s.store.read(f.blob).compile.to(Array).map { bytes =>
          val h = bids4s.NiftiHeaderFacts.parse(bytes).toOption.get
          val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
          val echo = "echo-([123])".r.findFirstMatchIn(f.path.value).get.group(1).toInt
          (0 until 8).foreach { i =>
            val expected = if f.path.value.contains("temporalMean") then echo * 100.0 + 8 + i else math.sqrt(128.0 / 3)
            assertEqualsDouble(b.getDouble(h.dataOffset.toInt + 8*i), expected, 1e-12)
          }
        }
      }
      _ <- TestFiles.remove(root.resolve("execution"))
      intent = PublicationIntent(evidence.invocation, evidence.id, reader.principal, output.id, None, key("multi-publish"))
      draft <- checked(submissions(s).publish(reader, intent))
      map = evidence.expected.find(_.path == TemporalStatistics.output(members.head, "mean")).get
      other = evidence.expected.find(_.path == TemporalStatistics.output(members(1), "mean")).get
      swapped <- submissions(s).collect(reader, draft.id, map.path, s.store.read(other.blob))
      _ = assert(swapped.left.toOption.exists(_.isInstanceOf[Problem.Integrity]))
      incomplete <- submissions(s).prepare(reader, draft.id)
      _ = assert(incomplete.isLeft)
      proposed <- collect(s, reader, draft, evidence)
      _ = assert(proposed.candidate.assessment.admissible, proposed.candidate.assessment.errors.toString)
      accepted <- checked(derivatives(s).accept(reader, proposed.candidate.id, key("multi-output")))
      maps <- checked(s.catalog.select(reader, Vector(accepted.release), ScientificQuery(artifacts = ArtifactPredicate.All(Vector(
        ArtifactPredicate.Suffix("statmap"), ArtifactPredicate.Entity("echo", "2"))))))
      _ = assertEquals(maps.hits.size, 2)
      observations = proposed.candidate.interpretation.observations.filter(_.metric == TemporalStatistics.metric("mean"))
      _ = assertEquals(observations.map(_.source.path).toSet, members.toSet)
      _ <- observations.traverse_ { observation =>
        checked(s.catalog.observations(reader, Vector(accepted.release), ObservationQuery(Vector(observation.source), observation.metric)))
          .map(rows => assertEquals(rows.size, 1))
      }
      correction <- checked(raw(s).correctMetadata(s.context, source.ref, p(reference.value.stripSuffix(".nii") + ".json"),
        """{"TotalReadoutTime":0.07}""", "Correct SBRef readout metadata"))
      _ <- checked(raw(s).accept(s.context, correction.candidate.id, key("multi-correction")))
      historical <- checked(inputs(s).bundle(reader, input.ref))
      _ = assertEquals(historical.activity.bundle, input.activity.bundle)
      _ = assertEquals(historical.interpretation.byPath(reference).fields("TotalReadoutTime").value, MetadataValue.Number(BigDecimal("0.05")))
      retry <- checked(new LocalProcessingRuns[IO](s.catalog, s.store, producer(s)).execute(reader, spec, key("multi-run"), root.resolve("unused")))
      _ = assertEquals(retry, evidence)
      _ <- IO.blocking(assert(!Files.exists(root.resolve("unused"))))
      _ <- checked(derivatives(s).exportFull(reader, accepted.release, new DirectoryExport[IO](root.resolve("maps"))))
      reimport <- derivatives(s).create(reader, s.dataset.custodian)
      roundtrip <- checked(derivatives(s).importNative(reader, reimport.id, new NativeDirectorySource[IO](root.resolve("maps"))))
      _ = assert(roundtrip.candidate.assessment.admissible, roundtrip.candidate.assessment.errors.toString)
      _ = assertEquals(roundtrip.candidate.interpretation.facts, proposed.candidate.interpretation.facts)
      _ = assert(roundtrip.candidate.interpretation.observations.isEmpty)
      _ <- checked(s.catalog.revoke(s.context, source.ref.dataset, grant.id))
      denied <- inputs(s).materialize(reader, input.ref, new DirectoryExport[IO](root.resolve("revoked")))
      _ = assertEquals(denied, Left(Problem.Denied))
      deniedPublish <- submissions(s).publish(reader, intent)
      _ = assertEquals(deniedPublish, Left(Problem.Denied))
      _ <- checked(derivatives(s).exportFull(reader, accepted.release, new DirectoryExport[IO](root.resolve("retained-derivative"))))
    yield () }
  }

  test("calibrated completed outputs survive stale publication intent and publish against an explicit new base") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      input <- bundle(s, s.context, source)
      output <- derivatives(s).create(s.context, s.dataset.custodian)
      spec = RunSpec(input.ref, input.activity.bundle.get.id, output.id, None, contract)
      runs = new LocalProcessingRuns[IO](s.catalog, s.store, producer(s))
      evidence <- checked(runs.execute(s.context, spec, key("recover-run"), root.resolve("execution")))
      intent = PublicationIntent(evidence.invocation, evidence.id, s.context.principal, output.id, None, key("recover-first"))
      first <- checked(submissions(s).publish(s.context, intent))
      stale <- checked(submissions(s).publish(s.context, intent.copy(key = key("recover-stale"))))
      proposed <- collect(s, s.context, first, evidence)
      competing <- collect(s, s.context, stale, evidence)
      accepted <- checked(derivatives(s).accept(s.context, proposed.candidate.id, key("recover-output")))
      conflict <- derivatives(s).accept(s.context, competing.candidate.id, key("recover-conflict"))
      _ = assert(conflict.left.toOption.exists(_.isInstanceOf[Problem.Conflict]))
      _ <- TestFiles.remove(root.resolve("execution"))
      reopened <- checked(submissions(s).publish(s.context, intent.copy(base = Some(accepted.release.release), key = key("recover-rebased"))))
      prepared <- collect(s, s.context, reopened, evidence)
      replacement <- checked(derivatives(s).accept(s.context, prepared.candidate.id, key("recover-replacement")))
      release <- checked(s.catalog.release(s.context, replacement.release))
      _ = assertEquals(release.activity.processing, Some(evidence))
      retry <- checked(runs.execute(s.context, spec, key("recover-run"), root.resolve("unused")))
      _ = assertEquals(retry, evidence)
      _ <- IO.blocking(assert(!Files.exists(root.resolve("unused"))))
    yield () }
  }

  test("calibrated admission rejects forged profile versions and false source sidecars") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      input <- bundle(s, s.context, source)
      evidence <- checked(producer(s).produce(s.context, input.ref, root.resolve("execution")))
      _ <- Vector("profile", "source").traverse_ { variant =>
        val file = evidence.expected.find(f => if variant == "profile" then f.path.value == "dataset_description.json" else f.path.value.endsWith("statmap.json")).get
        for
          bytes <- s.store.read(file.blob).compile.to(Array)
          text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
          modified = if variant == "profile" then text.replace("sanctity-temporal-statistics-v3", "sanctity-temporal-statistics-v1")
            else text.replace("echo-1", "echo-9").replace("echo-2", "echo-9").replace("echo-3", "echo-9")
          blob <- s.store.put(Stream.emits(modified.getBytes(java.nio.charset.StandardCharsets.UTF_8)).covary[IO])
          changed = evidence.copy(expected = evidence.expected.map(f => if f.path == file.path then f.copy(blob = blob) else f))
          output <- derivatives(s).create(s.context, s.dataset.custodian)
          draft <- checked(submissions(s).beginStaged(s.context, output.id, changed))
          _ <- changed.expected.traverse_(f => checked(submissions(s).collectStaged(s.context, draft.id, f.path)))
          result <- submissions(s).prepare(s.context, draft.id)
          _ = assert(result.isLeft || result.exists(p => !p.candidate.assessment.admissible), variant)
        yield ()
      }
    yield () }
  }


final class CalibrationInputSuite extends CatalogCalibrationInputSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-calibration-")))(TestFiles.remove).use { root =>
      SyntheticFixture.create(root.resolve("source")) *> ReferenceSystem.create(root.resolve("storage")).flatMap(run(_, root))
    }.unsafeRunSync()
