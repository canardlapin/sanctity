package sanctity.postgres

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.app.QueryOracle
import sanctity.application.*
import sanctity.bids.*
import sanctity.kernel.*
import sanctity.objects.{FileBlobStore, NativeDirectorySource}
import sanctity.processing.{LocalQcRuns, MriQcProducer}
import sanctity.postgres.JsonCodecs.given
import java.nio.file.{Files, Path}

/** Opt-in real accepted-catalog benchmark; deliberately absent from the ordinary test runner. */
object QueryBenchmark extends IOApp:
  private val seed = 17029L
  private val parser = new JawnParser()
  private def key(s: String): IdempotencyKey = IdempotencyKey.from(s).toOption.get
  private def checked[A](f: IO[Either[Problem, A]]): IO[A] = f.flatMap(Checked.lift[IO, A])
  private def write(path: Path, json: Json): IO[Unit] = IO.blocking(Files.writeString(path, json.spaces2 + "\n")).void
  private def load(path: Path): IO[Json] = IO.blocking(Files.readString(path)).flatMap(s => IO.fromEither(parser.parse(s)))
  private def ref(r: ReleaseRef): Json = Json.obj("dataset" -> Json.fromString(r.dataset.render), "release" -> Json.fromString(r.release.render))
  private def readRef(j: Json): ReleaseRef = ReleaseRef(Id.parse[DatasetTag](j.hcursor.get[String]("dataset").toOption.get).toOption.get,
    Id.parse[ReleaseTag](j.hcursor.get[String]("release").toOption.get).toOption.get)
  private def duration[A](io: IO[A]): IO[(Double, A)] = io.timed.map((d, a) => d.toNanos.toDouble / 1000000 -> a)

  def run(args: List[String]): IO[ExitCode] = for {
    _ <- IO.raiseUnless(sys.env.get("SANCTITY_TEST_DISPOSABLE").contains("1"))(new IllegalStateException("benchmark requires a disposable test database"))
    config <- Checked.lift[IO, DatabaseConfig](DatabaseConfig.environment(sys.env))
    _ <- PostgresDatabase.open[IO](config).use { db =>
      db.migrate *> (args match
        case "stage" :: directory :: Nil => stage(db, Path.of(directory))
        case "measure" :: directory :: Nil => measure(db, Path.of(directory))
        case _ => IO.raiseError(new IllegalArgumentException("QueryBenchmark stage|measure <new-workspace>")))
    }
  } yield ExitCode.Success

  private def stage(db: PostgresDatabase[IO], root: Path): IO[Unit] =
    val ids = IdSource.random[IO]
    val catalog = db.catalog(ids)
    for
      _ <- IO.blocking(Files.createDirectory(root))
      store <- FileBlobStore.open[IO](root.resolve("storage"))
      repository = new NativeRepository[IO](catalog, store, new BidsInterpreter[IO](store), ids)
      owner <- ids.next[PrincipalTag].map(AccessContext(_))
      custodian <- ids.next[CustodianTag]
      largest = {
        var low = 0
        var high = 800
        while low < high do
          val mid = (low + high + 1) / 2
          if QueryOracle.generate(seed, mid).entryCount <= 10000 then low = mid else high = mid - 1
        low
      }
      cohorts <- Vector(5, 50, largest).traverse { count =>
        val corpus = QueryOracle.generate(seed, count)
        for
          generated <- duration(corpus.install(root.resolve(s"source-$count")))
          destination <- repository.create(owner, custodian)
          prepared <- duration(checked(repository.importNative(owner, destination.id, new NativeDirectorySource[IO](root.resolve(s"source-$count")))))
          _ <- IO.raiseUnless(prepared._2.candidate.assessment.admissible)(new IllegalStateException(prepared._2.candidate.assessment.errors.toString))
          accepted <- duration(checked(repository.accept(owner, prepared._2.candidate.id, key(s"cohort-$count"))))
          _ <- IO.println(s"Indexed cohort $count: ${corpus.entryCount} entries, ${corpus.images.size} images")
        yield Json.obj("extraSubjects" -> Json.fromInt(count), "images" -> Json.fromInt(corpus.images.size),
          "inventoryEntries" -> Json.fromInt(corpus.entryCount), "ref" -> ref(accepted._2.release),
          "fixtureMs" -> Json.fromDoubleOrNull(generated._1), "prepareAndIndexMs" -> Json.fromDoubleOrNull(prepared._1),
          "acceptMs" -> Json.fromDoubleOrNull(accepted._1))
      }
      observations <- duration {
        val generated = QueryOracle.generate(seed + 1, 100)
        val corpus = generated.copy(images = generated.images.filter(_.suffix == "bold").take(100))
        for
          _ <- IO.raiseUnless(corpus.images.size == 100)(new IllegalStateException("observation fixture did not reach 100 inputs"))
          _ <- corpus.install(root.resolve("observation-source"))
          source <- repository.create(owner, custodian)
          imported <- checked(repository.importNative(owner, source.id, new NativeDirectorySource[IO](root.resolve("observation-source"))))
          accepted <- checked(repository.accept(owner, imported.candidate.id, key("observation-source")))
          selected <- checked(catalog.select(owner, Vector(accepted.release), ScientificQuery(artifacts = ArtifactPredicate.Suffix("bold"))))
          destination <- repository.create(owner, custodian)
          inputs = new ProcessingInputs[IO](catalog, repository, new ScientificViews[IO](catalog, store, repository, new MriViewCompiler, ids), new MriInputPlanner)
          proposal <- checked(inputs.preparePrivate(owner, accepted.release, selected.id, destination.id,
            DisclosureProfile(Set.empty, Set("TaskName", "RepetitionTime", "EchoTime", "SeriesDescription")), MriInputPlanner.Contract))
          frozen <- checked(repository.accept(owner, proposal.candidate.id, key("observation-bundle")))
          bundle <- checked(catalog.release(owner, frozen.release))
          executionDestination <- repository.create(owner, custodian)
          producer = new MriQcProducer[IO](catalog, store, repository, ids, Path.of("/usr/bin/python3"))
          receipt <- checked(new LocalQcRuns[IO](catalog, store, producer).execute(owner,
            RunSpec(bundle.ref, bundle.activity.bundle.get.id, executionDestination.id, None, MriInputPlanner.Contract), key("observations"), root.resolve("execution")))
          output <- repository.create(owner, custodian)
          derivatives = new NativeRepository[IO](catalog, store, new QcDerivativeInterpreter[IO](store), ids)
          submissions = new DerivativeSubmissions[IO](catalog, store, derivatives, ids)
          releases <- (0 until 100).toVector.foldLeftM(Vector.empty[ReleaseRef]) { (previous, n) =>
            val intent = PublicationIntent(receipt.invocation, receipt.id, owner.principal, output.id, previous.lastOption.map(_.release), key(s"observation-$n"))
            for
              draft <- checked(submissions.publish(owner, intent))
              _ <- receipt.expected.traverse_(o => checked(submissions.collectStaged(owner, draft.id, o.path)))
              prepared <- checked(submissions.prepare(owner, draft.id))
              accepted <- checked(derivatives.accept(owner, prepared.candidate.id, key(s"observation-$n")))
              _ <- if (n + 1) % 20 == 0 then IO.println(s"Accepted ${n + 1}/100 derivative releases") else IO.unit
            yield previous :+ accepted.release
          }
        yield Json.obj("input" -> ref(bundle.ref), "releases" -> Json.arr(releases.map(ref)*), "inputs" -> Json.fromInt(100))
      }
      version <- sql"SELECT version()".query[String].unique.transact(db.transactor)
      sizes <- sql"""SELECT relname,pg_total_relation_size(relid),pg_indexes_size(relid)
        FROM pg_catalog.pg_statio_user_tables WHERE schemaname=${db.schema} ORDER BY relname"""
        .query[(String, Long, Long)].to[Vector].transact(db.transactor)
      _ <- write(root.resolve("checkpoint.json"), Json.obj("seed" -> Json.fromLong(seed), "owner" -> Json.fromString(owner.principal.render),
        "cohorts" -> Json.arr(cohorts*), "observations" -> observations._2,
        "observationSetupMs" -> Json.fromDoubleOrNull(observations._1), "postgres" -> Json.fromString(version),
        "storage" -> Json.arr(sizes.map((name, bytes, indexes) => Json.obj("table" -> Json.fromString(name),
          "totalBytes" -> Json.fromLong(bytes), "indexBytes" -> Json.fromLong(indexes)))*)))
      _ <- doobie.Fragment.const("ANALYZE").update.run.transact(db.transactor)
      _ <- IO.println(s"Benchmark setup complete: $root")
    yield ()

  private def measure(db: PostgresDatabase[IO], root: Path): IO[Unit] =
    val catalog = db.catalog(IdSource.random[IO])
    val sourceRoot = Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("modules/postgres/src/main/scala/sanctity/postgres/PostgresCatalog.scala"))).get
    val repetitions = 3
    def samples[A](name: String)(action: IO[A])(verify: A => Unit): IO[Json] = for {
      values <- (0 to repetitions).toVector.traverse { _ => duration(action).flatTap((_, value) => IO(verify(value))) }
      warm = values.tail.map(_._1).sorted
      report = Json.obj("name" -> Json.fromString(name), "firstMs" -> Json.fromDoubleOrNull(values.head._1),
        "warmMs" -> Json.arr(values.tail.map(v => Json.fromDoubleOrNull(v._1))*),
        "warmMedianMs" -> Json.fromDoubleOrNull(warm(warm.size / 2)), "warmP95Ms" -> Json.fromDoubleOrNull(warm.last))
      _ <- IO.println(report.noSpaces)
    } yield report
    for
      checkpoint <- load(root.resolve("checkpoint.json"))
      sizes <- sql"""SELECT relname,pg_total_relation_size(relid),pg_indexes_size(relid)
        FROM pg_catalog.pg_statio_user_tables WHERE schemaname=${db.schema} ORDER BY relname"""
        .query[(String, Long, Long)].to[Vector].transact(db.transactor)
      owner = AccessContext(Id.parse[PrincipalTag](checkpoint.hcursor.get[String]("owner").toOption.get).toOption.get)
      cohorts = checkpoint.hcursor.get[Vector[Json]]("cohorts").toOption.get
      selectedCases = QueryOracle.queries(seed).filter(c => Set("all", "same-session", "lexical-subject-01", "numeric-age-18", "strict-decimal-boundary")(c.name))
      selections <- cohorts.traverse { cohort =>
        val count = cohort.hcursor.get[Int]("extraSubjects").toOption.get
        val corpus = QueryOracle.generate(seed, count)
        val at = readRef(cohort.hcursor.downField("ref").focus.get)
        for
          release <- checked(catalog.release(owner, at))
          results <- selectedCases.traverse { c =>
            val expected = corpus.expected(c.query).map(i => SelectionHit(at, RelPath.from(i.path).toOption.get, "sub-" + i.subject, i.session.map("ses-" + _)))
            for
              api <- samples(s"cohort-$count/${c.name}/catalog-and-selection")(checked(catalog.select(owner, Vector(at), c.query)))(s =>
                require(s.hits == expected, s"oracle mismatch: $seed ${c.name}"))
              sql <- samples(s"cohort-$count/${c.name}/sql")(ScientificSql.select(at, release.interpretation, c.query).transact(db.transactor))(v => require(v == expected))
              persistence <- samples(s"cohort-$count/${c.name}/selection-encoding-and-idempotent-persistence")((for
                frozen <- Checked.lift[doobie.ConnectionIO, FrozenSelection](FrozenSelection.from(Vector(at), c.query, expected))
                payload = JsonCodecs.encode(frozen)
                inserted <- sql"""INSERT INTO frozen_selection(id,owner,payload) VALUES
                  (${frozen.id.value.value},${java.util.UUID.fromString(owner.principal.render)},$payload::jsonb) ON CONFLICT DO NOTHING""".update.run
              yield inserted).transact(db.transactor))(n => require(n == 0))
              plan <- ScientificSql.explainSelection(release.interpretation, c.query).transact(db.transactor).flatMap(s => IO.fromEither(parser.parse(s)))
            yield Json.obj("case" -> Json.fromString(c.name), "rows" -> Json.fromInt(expected.size), "catalog" -> api, "sql" -> sql,
              "selectionPersistence" -> persistence, "plan" -> plan)
          }
        yield Json.obj("cohort" -> cohort, "queries" -> Json.arr(results*))
      }
      input <- checked(catalog.release(owner, readRef(checkpoint.hcursor.downField("observations").downField("input").focus.get)))
      sources = input.manifest.files.filter(_.path.value.endsWith("_bold.nii")).map(f => ReleasedArtifact(input.ref, input.interpretation.id, input.assessment.id, f.path, f.blob))
      refs = checkpoint.hcursor.downField("observations").get[Vector[Json]]("releases").toOption.get.map(readRef)
      derivative <- checked(catalog.release(owner, refs.head))
      observations <- Vector((1, 1), (100, 1), (100, 100)).traverse { (inputCount, releaseCount) =>
        val universe = refs.take(releaseCount)
        val q = ObservationQuery(sources.take(inputCount), MetricDefinition("volumes", "1", None), atLeast = Some(BigDecimal(2)), below = Some(BigDecimal(3)))
        val expected = universe.flatMap(at => q.inputs.map(source => ObservationHit(at,
          ScientificObservation(source, q.metric, BigDecimal(2), RelPath.from("qc.tsv").toOption.get, QcDerivativeInterpreter.ValueRule))))
          .sortBy(h => (h.at.dataset.render, h.at.release.render, h.observation.source.path.value))
        for
          api <- samples(s"observations-$inputCount-$releaseCount/catalog")(checked(catalog.observations(owner, universe, q)))(v => require(v == expected, "independent observation oracle mismatch"))
          plan <- ScientificSql.explainObservations(derivative.interpretation, q).transact(db.transactor).flatMap(s => IO.fromEither(parser.parse(s)))
          direct <- samples(s"observations-$inputCount-$releaseCount/single-release-sql")(
            ScientificSql.observations(refs.head, derivative.interpretation, q).transact(db.transactor))(v => require(v.size == inputCount && v.forall(_.observation.value == 2)))
        yield Json.obj("inputs" -> Json.fromInt(inputCount), "releases" -> Json.fromInt(releaseCount),
          "rows" -> Json.fromInt(expected.size), "catalog" -> api, "singleReleaseSql" -> direct, "plan" -> plan)
      }
      _ <- write(root.resolve("report.json"), Json.obj("checkpoint" -> checkpoint,
        "measurementStorage" -> Json.arr(sizes.map((name, bytes, indexes) => Json.obj("table" -> Json.fromString(name),
          "totalBytes" -> Json.fromLong(bytes), "indexBytes" -> Json.fromLong(indexes)))*),
        "measurementSources" -> Json.obj(Vector(
          "modules/integration/src/test/scala/sanctity/postgres/QueryBenchmark.scala",
          "modules/app/src/test/scala/sanctity/app/QueryOracle.scala",
          "modules/postgres/src/main/scala/sanctity/postgres/PostgresCatalog.scala",
          "modules/postgres/src/main/scala/sanctity/postgres/ScientificSql.scala").map(p => p -> Json.fromString(Digest.bytes(Files.readAllBytes(sourceRoot.resolve(p))).value))*),
        "cachePolicy" -> Json.fromString("PostgreSQL restarted after setup. First sample is per case in sequence; later cases share warmed tables. OS cache is not flushed. Warm samples follow the first call. No timing SLA is asserted."),
        "repetitions" -> Json.fromInt(repetitions), "selections" -> Json.arr(selections*), "observations" -> Json.arr(observations*)))
      _ <- IO.println(s"Oracle-verified benchmark report: $root/report.json")
    yield ()
