package sanctity.app

import cats.effect.IO
import io.circe.Json
import sanctity.kernel.*
import java.nio.file.{Files, Path}

/** Source facts carry independently authored numeric meaning, including deliberately nonnumeric ages.
  * Expected membership is set algebra over these source records, never a catalog interpretation.
  */
object QueryOracle:
  final case class Age(lexical: String, numeric: Option[BigDecimal])
  final case class Subject(label: String, age: Age, group: String)
  final case class Image(path: String, subject: String, session: Option[String], suffix: String, run: String,
      text: Map[String, String], numbers: Map[String, BigDecimal]):
    def entities: Map[String, String] = Map("sub" -> subject, "run" -> run) ++ session.map("ses" -> _) ++
      Option.when(suffix == "bold")("task" -> "rest")
    def sidecar: String = path.stripSuffix(".nii") + ".json"
    def document: Json = Json.obj((text.toVector.map((k, v) => k -> Json.fromString(v)) ++
      numbers.toVector.map((k, v) => k -> Json.fromBigDecimal(v))).sortBy(_._1)*)
  final case class Corpus(seed: Long, subjects: Vector[Subject], images: Vector[Image]):
    def entryCount: Int =
      val files = Vector("dataset_description.json", "participants.tsv") ++ images.flatMap(i => Vector(i.path, i.sidecar))
      val directories = files.flatMap { path =>
        val segments = path.split('/').toVector
        (1 until segments.size).map(n => segments.take(n).mkString("/"))
      }.distinct
      files.size + directories.size
    private val byPath = images.map(i => i.path -> i).toMap
    private val allImages = byPath.keySet
    private val allSubjects = images.map(_.subject).toSet

    private def intersect[A](sets: Vector[Set[A]], universe: Set[A]): Set[A] = sets.foldLeft(universe)(_ intersect _)
    private def artifacts(p: ArtifactPredicate): Set[String] = p match
      case ArtifactPredicate.All(ps) => intersect(ps.map(artifacts), allImages)
      case ArtifactPredicate.Suffix(suffix) => images.filter(_.suffix == suffix).map(_.path).toSet
      case ArtifactPredicate.Entity(name, value) => images.filter(_.entities.get(name).contains(value)).map(_.path).toSet
      case ArtifactPredicate.MetadataEquals(name, value) => images.filter(_.text.get(name).contains(value)).map(_.path).toSet
      case ArtifactPredicate.MetadataBelow(name, value) => images.filter(_.numbers.get(name).exists(_ < value)).map(_.path).toSet
    private def participants(p: ParticipantPredicate): Set[String] = p match
      case ParticipantPredicate.All(ps) => intersect(ps.map(participants), allSubjects)
      case ParticipantPredicate.FieldEquals(name, value) => subjects.filter { s =>
        Map("age" -> s.age.lexical, "group" -> s.group).get(name).contains(value)
      }.map(_.label).toSet
      case ParticipantPredicate.FieldAtLeast(name, value) =>
        if name != "age" then Set.empty else subjects.filter(_.age.numeric.exists(_ >= value)).map(_.label).toSet
      case ParticipantPredicate.HasArtifact(p) => artifacts(p).map(byPath(_).subject)
      case ParticipantPredicate.HasSession(ps) =>
        val universe = images.map(i => i.subject -> i.session).toSet
        val sessionSets = ps.map(p => artifacts(p).map(path => byPath(path).subject -> byPath(path).session))
        intersect(sessionSets, universe).map(_._1)

    def expected(query: ScientificQuery): Vector[Image] =
      val matching = artifacts(query.artifacts)
      val people = participants(query.participants)
      images.filter(i => matching(i.path) && people(i.subject)).sortBy(_.path)

    def change(path: String): Corpus = copy(images = images.map { i =>
      if i.path == path then i.copy(text = i.text.updated("SeriesDescription", "corrected"), numbers = i.numbers.updated("EchoTime", BigDecimal("0.04"))) else i
    })

    def install(root: Path): IO[Unit] = IO.blocking {
      Files.createDirectories(root)
      def write(name: String, bytes: Array[Byte]): Unit =
        val path = root.resolve(name)
        Files.createDirectories(path.getParent)
        val _ = Files.write(path, bytes)
      write("dataset_description.json", """{"Name":"Seeded scientific-query corpus","BIDSVersion":"1.11.1"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val table = "participant_id\tage\tgroup\n" + subjects.map(s => s"sub-${s.label}\t${s.age.lexical}\t${s.group}\n").mkString
      write("participants.tsv", table.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val provenance = MriFixtures.json("phase-pair/provenance.json").hcursor
      val bold = MriFixtures.bytes("phase-pair/dataset/" + provenance.get[String]("target").toOption.get)
      val anatomy = MriFixtures.bytes("phase-pair/dataset/" + provenance.get[Vector[String]]("expectedMembers").toOption.get(2))
      images.foreach { i =>
        write(i.path, if i.suffix == "bold" then bold else anatomy)
        write(i.sidecar, (i.document.noSpaces + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
      }
      val entries = Files.walk(root)
      try require(entries.count() - 1 <= 10000, "generated corpus exceeds repository inventory limit")
      finally entries.close()
    }

  private val ages = Vector(Age("89+", None), Age("18", Some(BigDecimal(18))), Age("018", None),
    Age("18.0", Some(BigDecimal(18))), Age("n/a", None), Age("-1", Some(BigDecimal(-1))),
    Age("0.5", Some(BigDecimal("0.5"))), Age("+19", None), Age("1e2", None), Age("90", Some(BigDecimal(90))))

  def generate(seed: Long, extraSubjects: Int = 5): Corpus =
    require(extraSubjects >= 0 && extraSubjects <= 800)
    val random = new scala.util.Random(seed)
    val subjects = (Vector("01", "1", "02", "03", "04") ++ Vector.tabulate(extraSubjects)(i => s"x${i + 1}")).zipWithIndex.map { (label, i) =>
      Subject(label, ages(i % ages.size), if i % 2 == 0 then "alpha" else "beta")
    }
    val images = Vector.newBuilder[Image]
    def add(subject: String, session: Option[String], suffix: String): Unit =
      val run = if random.nextBoolean() then "01" else "1"
      val stem = "sub-" + subject + session.fold("")(s => "_ses-" + s)
      val directory = "sub-" + subject + session.fold("")(s => "/ses-" + s) + (if suffix == "bold" then "/func/" else "/anat/")
      val path = directory + stem + (if suffix == "bold" then "_task-rest" else "") + "_run-" + run + "_" + suffix + ".nii"
      val text = (if suffix == "bold" then Map("TaskName" -> "rest") else Map.empty[String, String]) ++
        Option.when(random.nextInt(4) != 0)("SeriesDescription" -> (if random.nextBoolean() then "alpha" else "beta"))
      val numbers = (if suffix == "bold" then Map("RepetitionTime" -> BigDecimal("1.5")) else Map.empty[String, BigDecimal]) ++
        Option.when(random.nextInt(4) != 0)("EchoTime" -> (BigDecimal(random.nextInt(3) + 1) / BigDecimal(100)))
      images += Image(path, subject, session, suffix, run, text, numbers)
    add("01", Some("A"), "bold")
    add("01", Some("B"), "T1w")
    add("1", Some("A"), "bold")
    add("1", Some("A"), "T1w")
    add("02", None, "bold")
    add("02", None, "T1w")
    add("03", Some("A"), "T1w")
    add("04", Some("A"), "bold")
    subjects.drop(5).foreach { s =>
      Vector(None, Some("A"), Some("B")).foreach { session =>
        Vector("bold", "T1w", "T2w").foreach { suffix => if random.nextBoolean() then add(s.label, session, suffix) }
      }
    }
    val rows = images.result().map(i => if i.subject == "01" then
      i.copy(numbers = i.numbers.updated("EchoTime", if i.suffix == "bold" then BigDecimal("0.02") else BigDecimal("0.01"))) else i)
    Corpus(seed, subjects, rows.sortBy(_.path))

  final case class Case(name: String, query: ScientificQuery)
  def queries(seed: Long): Vector[Case] =
    val t1 = ArtifactPredicate.Suffix("T1w")
    val bold = ArtifactPredicate.Suffix("bold")
    val fixed = Vector(
      Case("all", ScientificQuery()),
      Case("same-session", ScientificQuery(ParticipantPredicate.HasSession(Vector(t1, bold)))),
      Case("different-sessions-allowed", ScientificQuery(ParticipantPredicate.All(Vector(ParticipantPredicate.HasArtifact(t1), ParticipantPredicate.HasArtifact(bold))))),
      Case("empty-session-conjunction", ScientificQuery(ParticipantPredicate.HasSession(Vector.empty))),
      Case("lexical-subject-1", ScientificQuery(artifacts = ArtifactPredicate.Entity("sub", "1"))),
      Case("lexical-subject-01", ScientificQuery(artifacts = ArtifactPredicate.Entity("sub", "01"))),
      Case("lexical-age", ScientificQuery(ParticipantPredicate.FieldEquals("age", "89+"))),
      Case("numeric-age-18", ScientificQuery(ParticipantPredicate.FieldAtLeast("age", BigDecimal(18)))),
      Case("numeric-age-89", ScientificQuery(ParticipantPredicate.FieldAtLeast("age", BigDecimal(89)))),
      Case("numeric-age-half", ScientificQuery(ParticipantPredicate.FieldAtLeast("age", BigDecimal("0.5")))),
      Case("missing-participant-field", ScientificQuery(ParticipantPredicate.FieldEquals("absent", "n/a"))),
      Case("missing-number", ScientificQuery(artifacts = ArtifactPredicate.MetadataBelow("absent", BigDecimal(1)))),
      Case("numeric-not-text", ScientificQuery(artifacts = ArtifactPredicate.MetadataEquals("EchoTime", "0.01"))),
      Case("text-not-numeric", ScientificQuery(artifacts = ArtifactPredicate.MetadataBelow("SeriesDescription", BigDecimal(1)))),
      Case("strict-decimal-boundary", ScientificQuery(artifacts = ArtifactPredicate.MetadataBelow("EchoTime", BigDecimal("0.02")))),
      Case("minimum-decimal-boundary", ScientificQuery(artifacts = ArtifactPredicate.MetadataBelow("EchoTime", BigDecimal("0.01")))),
      Case("corrected", ScientificQuery(artifacts = ArtifactPredicate.MetadataEquals("SeriesDescription", "corrected"))))
    val random = new scala.util.Random(seed)
    val predicates = Vector(t1, bold, ArtifactPredicate.Suffix("T2w"), ArtifactPredicate.Entity("run", "01"), ArtifactPredicate.Entity("run", "1"),
      ArtifactPredicate.MetadataEquals("SeriesDescription", "alpha"), ArtifactPredicate.MetadataBelow("EchoTime", BigDecimal("0.02")))
    fixed ++ Vector.tabulate(20) { n =>
      val a = predicates(random.nextInt(predicates.size))
      val b = predicates(random.nextInt(predicates.size))
      Case(s"seed-$seed-query-$n", ScientificQuery(ParticipantPredicate.All(Vector(
        ParticipantPredicate.HasSession(Vector(a, b)), ParticipantPredicate.All(Vector(ParticipantPredicate.FieldAtLeast("age", BigDecimal(18)))))),
        ArtifactPredicate.All(Vector(ArtifactPredicate.All(Vector(a)), b))))
    }
