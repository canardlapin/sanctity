package sanctity.app

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import sanctity.application.*
import sanctity.bids.MriViewCompiler
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport, NativeDirectorySource}
import sanctity.processing.*
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}

/** These cases run unchanged against reference and PostgreSQL catalogs. */
abstract class CatalogMultiEchoTemporalSuite extends CatalogTemporalSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit
  private def checked[A](v: IO[Either[Problem, A]]): IO[A] = v.flatMap(Checked.lift[IO, A])
  private def p(s: String): RelPath = RelPath.from(s).toOption.get
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private val members = Vector(1, 2, 3).map(i => p(s"sub-01/func/sub-01_task-cue_run-01_echo-${i}_bold.nii"))
  private val mask = DisclosureProfile(Set.empty, Set("License", "TaskName", "RepetitionTime", "EchoTime", "SliceTiming"),
    eventFields = Some(Set("onset", "duration", "trial_type", "stim_file")))
  private val contract = TemporalStatistics.MultiEchoContract
  private def raw(s: RepositorySystem, geometry: Boolean = true) = new NativeRepository[IO](s.catalog, s.store,
    new PayloadBidsInterpreter[IO](s.store, geometry), IdSource.random[IO])
  private def derivatives(s: RepositorySystem) = new NativeRepository[IO](s.catalog, s.store,
    new TemporalDerivativeInterpreter[IO](s.store), IdSource.random[IO])
  private def submissions(s: RepositorySystem) = new DerivativeSubmissions[IO](s.catalog, s.store, derivatives(s), IdSource.random[IO])
  private def producer(s: RepositorySystem) = new TemporalProducer[IO](s.catalog, s.store, raw(s), IdSource.random[IO], TemporalFixture.python)
  private def inputs(s: RepositorySystem) = new ProcessingInputs[IO](s.catalog, raw(s),
    new ScientificViews[IO](s.catalog, s.store, raw(s), new MriViewCompiler, IdSource.random[IO]), new TemporalInputPlanner)
  private def install(root: Path, corpus: String = "analysis-inputs"): IO[Unit] = TestFiles.remove(root.resolve("source")) *> IO.blocking {
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

  test("multi-echo temporal readiness expands one image, retains v1 limits and requires opt-in geometry") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      ready <- Checked.lift[IO, InputReadiness](new TemporalInputPlanner().assess(source.manifest, source.interpretation, source.assessment, Vector(members(1)), contract))
      _ = assert(ready.ready, ready.requirements.toString)
      _ = assertEquals(ready.roots, members)
      legacy <- Checked.lift[IO, InputReadiness](new TemporalInputPlanner().assess(source.manifest, source.interpretation, source.assessment,
        Vector(members(1)), TemporalStatistics.Contract))
      _ = assert(!legacy.ready)
      oldDestination <- raw(s, false).create(s.context, s.dataset.custodian)
      old <- checked(raw(s, false).importNative(s.context, oldDestination.id, new NativeDirectorySource[IO](root.resolve("source"))))
      unavailable <- Checked.lift[IO, InputReadiness](new TemporalInputPlanner().assess(old.candidate.manifest, old.candidate.interpretation,
        old.candidate.assessment, Vector(members(1)), contract))
      _ = assert(!unavailable.ready)
    yield () }
  }

  test("unselected echo payload and exact geometry evidence cannot be missing or substituted") {
    fixture { (s, root) => for
      _ <- install(root)
      source <- original(s, root)
      _ = Vector(NiftiPayload.Rule, NiftiGeometry.Rule, NiftiGeometry.FamilyRule).foreach { rule =>
        val absent = source.assessment.checks.filterNot(c => c.rule == rule && c.target == members.head)
        val assessment = Assessment.from(source.interpretation, "test-incomplete", source.assessment.findings, absent)
        val ready = new TemporalInputPlanner().assess(source.manifest, source.interpretation, assessment, Vector(members(1)), contract).toOption.get
        assert(!ready.ready, rule)
      }
      _ = Vector(NiftiPayload.Rule, NiftiGeometry.Rule, NiftiGeometry.FamilyRule).foreach { rule =>
        val changed = source.assessment.checks.map { c =>
          if c.rule != rule || c.target != members.head then c
          else ContentCheck.from(c.rule, c.target, c.inputs.map(i => i.copy(blob = source.manifest.files.find(_.path == members(1)).get.blob)),
            c.facts, c.findings).toOption.get
        }
        val assessment = Assessment.from(source.interpretation, "test-substitution", source.assessment.findings, changed)
        assert(!new TemporalInputPlanner().assess(source.manifest, source.interpretation, assessment, Vector(members(1)), contract).toOption.get.ready, rule)
      }
    yield () }
  }

  Vector("shifted-qform", "shifted-sform", "unknown-geometry", "singular-sform", "bad-quaternion", "nonfinite-payload", "truncated-payload", "gzip-crc").foreach { variant =>
    test("unselected echo blocks temporal admission: " + variant) {
      fixture { (s, root) => for
        _ <- install(root)
        _ <- IO.blocking {
          val path = root.resolve("source").resolve(members.head.value)
          val bytes = Files.readAllBytes(path)
          val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
          variant match
            case "shifted-qform" => b.putFloat(268, 5)
            case "shifted-sform" => b.putFloat(292, 5)
            case "unknown-geometry" => b.putShort(252, 0); b.putShort(254, 0)
            case "singular-sform" => b.putFloat(280, 0)
            case "bad-quaternion" => b.putFloat(256, 2)
            case "nonfinite-payload" => b.putFloat(352, Float.NaN)
            case _ => ()
          if variant == "gzip-crc" then
            val compressed = TemporalFixture.gzip(bytes)
            compressed(compressed.length - 8) = (compressed(compressed.length - 8) ^ 1).toByte
            Files.delete(path)
            val _ = Files.write(Path.of(path.toString + ".gz"), compressed)
          else
            val _ = Files.write(path, if variant == "truncated-payload" then bytes.dropRight(1) else bytes)
        }
        result <- checked(raw(s).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
        _ = assert(!result.candidate.assessment.admissible)
        ready <- Checked.lift[IO, InputReadiness](new TemporalInputPlanner().assess(result.candidate.manifest, result.candidate.interpretation,
          result.candidate.assessment, Vector(members(1)), contract))
        _ = assert(!ready.ready)
      yield () }
    }
  }

  test("expanded temporal target limit accepts sixteen echoes and rejects seventeen images") {
    fixture { (s, root) => for
      _ <- install(root)
      _ <- IO.blocking {
        val base = root.resolve("source")
        (4 to 16).foreach { i =>
          val name = members.head.value.replace("echo-1", s"echo-$i")
          Files.copy(base.resolve(members.head.value), base.resolve(name))
          val _ = Files.writeString(base.resolve(name.stripSuffix(".nii") + ".json"), s"""{"EchoTime":${i / 100.0}}""")
        }
      }
      preview <- checked(raw(s).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      c = preview.candidate
      ready <- Checked.lift[IO, InputReadiness](new TemporalInputPlanner().assess(c.manifest, c.interpretation, c.assessment, Vector(members.head), contract))
      _ = assert(ready.ready, ready.requirements.toString)
      _ = assertEquals(ready.roots.size, 16)
      additional = members.head.value.replace("run-01_echo-1", "run-02")
      _ <- IO.blocking(Files.copy(root.resolve("source").resolve(members.head.value), root.resolve("source").resolve(additional)))
      over <- checked(raw(s).importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))))
      o = over.candidate
      denied <- Checked.lift[IO, InputReadiness](new TemporalInputPlanner().assess(o.manifest, o.interpretation, o.assessment, Vector(members.head, p(additional)), contract))
      _ = assert(denied.requirements.exists(r => r.code == "temporal-recipe" && r.status == RequirementStatus.Unsupported))
    yield () }
  }

  test("three-echo maps publish retained bytes, preserve historical correction, query separate echoes and enforce revocation") {
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
      correction <- checked(raw(s).correctMetadata(s.context, source.ref, p(members(1).value.stripSuffix(".nii") + ".json"),
        """{"EchoTime":0.025}""", "Correct echo 2 metadata"))
      _ <- checked(raw(s).accept(s.context, correction.candidate.id, key("multi-correction")))
      historical <- checked(inputs(s).bundle(reader, input.ref))
      _ = assertEquals(historical.activity.bundle, input.activity.bundle)
      _ = assertEquals(historical.interpretation.byPath(members(1)).fields("EchoTime").value, MetadataValue.Number(BigDecimal("0.02")))
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

  test("multi-echo completed outputs survive stale publication intent and publish against an explicit new base") {
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

  test("multi-echo admission rejects forged profile versions and false source sidecars") {
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
          modified = if variant == "profile" then text.replace("sanctity-temporal-statistics-v2", "sanctity-temporal-statistics-v1")
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

  test("acquired CC0 big-endian four-echo excerpt qualifies, executes and admits all eight maps") {
    fixture { (s, root) => for
      _ <- install(root, "acquired-multiecho")
      source <- original(s, root)
      input <- bundle(s, s.context, source)
      _ = assertEquals(input.activity.bundle.get.readiness.roots.size, 4)
      evidence <- checked(producer(s).produce(s.context, input.ref, root.resolve("execution")))
      _ = assertEquals(evidence.expected.count(_.path.value.endsWith(".nii")), 8)
      output <- derivatives(s).create(s.context, s.dataset.custodian)
      draft <- checked(submissions(s).beginStaged(s.context, output.id, evidence))
      candidate <- collect(s, s.context, draft, evidence)
      _ = assert(candidate.candidate.assessment.admissible, candidate.candidate.assessment.errors.toString)
      _ <- checked(derivatives(s).accept(s.context, candidate.candidate.id, key("acquired-output")))
    yield () }
  }

  test("v2 declares and admits the NIfTI qfac zero-to-positive-one fallback without changing coordinates") {
    fixture { (s, root) => for
      _ <- install(root)
      _ <- IO.blocking {
        members.foreach { member =>
          val path = root.resolve("source").resolve(member.value)
          val bytes = Files.readAllBytes(path)
          ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(76, 0)
          val _ = Files.write(path, bytes)
        }
      }
      source <- original(s, root)
      input <- bundle(s, s.context, source)
      evidence <- checked(producer(s).produce(s.context, input.ref, root.resolve("execution")))
      output <- derivatives(s).create(s.context, s.dataset.custodian)
      draft <- checked(submissions(s).beginStaged(s.context, output.id, evidence))
      result <- collect(s, s.context, draft, evidence)
      _ = assert(result.candidate.assessment.admissible, result.candidate.assessment.errors.toString)
    yield () }
  }

final class MultiEchoTemporalSuite extends CatalogMultiEchoTemporalSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-multiecho-temporal-")))(TestFiles.remove).use { root =>
      SyntheticFixture.create(root.resolve("source")) *> ReferenceSystem.create(root.resolve("storage")).flatMap(run(_, root))
    }.unsafeRunSync()
