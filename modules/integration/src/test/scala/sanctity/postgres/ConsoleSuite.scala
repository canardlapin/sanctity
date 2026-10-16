package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import io.circe.Json
import io.circe.jawn.JawnParser
import munit.FunSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import sanctity.application.*
import sanctity.http.{ConsoleAssets, RepositoryHttp}
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Pins the backend shapes the browser console interprets. Each assertion names the console behaviour it protects. */
final class ConsoleSuite extends FunSuite:
  import ConsoleSuite.*

  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])

  test("console assets and API share one origin, and release JSON keeps the shapes the console reads") {
    PostgresFixture.resource.use { f =>
      val app = ConsoleAssets.withApi(new RepositoryHttp[IO](f.system.catalog, f.system.repository, f.database.credentials(IdSource.random[IO])).app)
      for
        _ <- source(f.root.resolve("console"))
        proposal <- checked(f.system.repository.importNative(f.system.context, f.system.dataset.id, new NativeDirectorySource[IO](f.root.resolve("console"))))
        initial <- checked(f.system.repository.accept(f.system.context, proposal.candidate.id, IdempotencyKey.from("console").toOption.get))
        token <- checked(f.database.credentials(IdSource.random[IO]).issue(f.system.context.principal, "console", 3600))
        _ <- EmberServerBuilder.default[IO].withHost(Host.fromString("127.0.0.1").get).withPort(Port.fromInt(0).get)
          .withHttpApp(app).build.use { server =>
            EmberClientBuilder.default[IO].build.use { client =>
              val http = Http(client, s"http://127.0.0.1:${server.address.getPort}", token.secret)
              val releaseUrl = s"/v1/datasets/${initial.release.dataset.render}/releases/${initial.release.release.render}"
              for
                page <- http.raw("/")
                _ = assertEquals(page._1, Status.Ok)
                _ = assert(page._2.contains("app.js"))
                script <- http.raw("/app.js")
                _ = assertEquals(script._1, Status.Ok)
                release <- http.json(Method.GET, releaseUrl)
                _ <- IO(assertRelease(release._2, script._2))
                // A subject-level sidecar applies to the run through BIDS inheritance, as the demo now models it.
                subjectLevel = Json.obj("path" -> Json.fromString("sub-02/sub-02_task-nback_bold.json"), "text" -> Json.fromString("{\"RepetitionTime\": 2.0}\n"),
                  "reason" -> Json.fromString("Console contract: subject-level TR"))
                inherited <- http.json(Method.POST, releaseUrl + "/corrections", Some(subjectLevel))
                _ = assertEquals(inherited._1, Status.Created)
                _ = assert(inherited._2.hcursor.get[Vector[Json]]("metadataChanges").toOption.get.map(str(_, "path")).contains(nback2), "subject-level sidecar must reach the run")
                _ = assert(!header(inherited._2.hcursor.downField("candidate").focus.get).get.hcursor.get[Vector[Json]]("findings").toOption.get
                  .exists(j => str(j, "code") == "MriRepetitionTimeMismatch"), "subject-level sidecar resolves the mismatch")
                // The console's "Add sidecar" flow writes a new run-level sidecar next to the image.
                change = Json.obj("path" -> Json.fromString(newSidecar), "text" -> Json.fromString("{\n  \"RepetitionTime\": 2.0\n}\n"),
                  "reason" -> Json.fromString("Console contract: sub-02 TR from header"))
                preview <- http.json(Method.POST, releaseUrl + "/corrections", Some(change))
                _ = assertEquals(preview._1, Status.Created)
                _ <- IO(assertPreview(release._2, preview._2))
                candidate = preview._2.hcursor.downField("candidate").get[String]("id").toOption.get
                accepted <- http.json(Method.POST, s"/v1/candidates/$candidate/accept", None, Some("console-accept"))
                _ = assertEquals(accepted._1, Status.Ok)
                next = accepted._2.hcursor.downField("ref").get[String]("release").toOption.get
                head <- http.json(Method.GET, s"/v1/datasets/${initial.release.dataset.render}/releases/$next")
                _ = assert(!findings(head._2).exists(j => str(j, "code") == "MriRepetitionTimeMismatch" && pathOf(j).contains(nback2)),
                  "accepted sidecar must clear the mismatch it was written for")
                // The release names its own curation history: why, by whom, and which files differ from its parent.
                _ <- IO(assertProvenance(release._2, head._2, f.system.context.principal.render))
              yield ()
            }
          }
      yield ()
    }.unsafeRunSync()
  }

  private def assertRelease(release: Json, script: String): Unit =
    val links = release.hcursor.downField("relationships").get[Vector[Json]]("links").toOption.get
    // Relationship views label every kind; an unlabelled kind would show its raw identifier.
    val labelled = """'(bids\.[a-z0-9.-]+)': \[""".r.findAllMatchIn(script).map(_.group(1)).toSet
    assert(script.contains("'timingMetadata.RepetitionTime.value'") && script.contains("'temporalSpacingSeconds'"), "KEY_FACTS names the facts this suite pins")
    val kinds = links.map(str(_, "kind")).toSet
    assert(kinds.subsetOf(labelled), s"console has no label for ${kinds -- labelled}")
    // "Fieldmaps by run": IntendedFor links run from the corrected image to its fieldmaps.
    val intended = links.filter(str(_, "kind") == "bids.intended-for")
    assert(intended.exists(l => str(l, "from") == nback1 && to(l).contains(ap1)), intended.toString)
    assert(intended.forall(l => !str(l, "from").contains("/fmap/")), "fieldmap links must not start at a fieldmap")
    // "B0 field sources": the run's B0FieldSource selects both pepolar images and labels the link with the identifier.
    val b0 = links.filter(str(_, "kind") == "bids.b0-field-source")
    assertEquals(b0.map(l => (str(l, "from"), to(l).toSet, l.hcursor.get[String]("label").toOption)),
      Vector((rest2, Set(ap2, pa2), Some("pepolar02"))))
    // Assessment groups row-level event findings by message shape, with row and column numbers elided.
    val outside = findings(release).filter(j => str(j, "code") == "EventsOutsideWindow" && pathOf(j).contains(events1))
    assert(outside.size >= 2, s"expected one finding per late row: $outside")
    assertEquals(outside.map(j => shape(str(j, "message"))).distinct.size, 1)
    assert(outside.map(j => str(j, "message")).distinct.size == outside.size, "row findings should differ only by row")
    // Inspector: the mismatch is reported by a header check on that image whose facts carry the authored TR.
    val mismatch = header(release).get
    assert(mismatch.hcursor.get[Vector[Json]]("findings").toOption.get.exists(j => str(j, "code") == "MriRepetitionTimeMismatch"))
    assertEquals(flat(mismatch.hcursor.downField("facts").focus.get).get("timingMetadata.RepetitionTime.value").map(BigDecimal(_)), Some(BigDecimal("1.5")))
    // The finding shows both compared values (app.js KEY_FACTS): the authored TR and the header's volume spacing.
    assertEquals(flat(mismatch.hcursor.downField("facts").focus.get).get("temporalSpacingSeconds").map(BigDecimal(_)), Some(BigDecimal(2)))
    assert(findings(release).exists(j => str(j, "code") == "MriRepetitionTimeMismatch" && pathOf(j).contains(nback2)))

  private def assertProvenance(initial: Json, accepted: Json, principal: String): Unit =
    val first = initial.hcursor.downField("provenance")
    assertEquals(first.get[String]("kind").toOption, Some("native-import"))
    assert(first.downField("changesFromParent").focus.exists(_.isNull), "an import has no parent to compare with")
    val p = accepted.hcursor.downField("provenance")
    assertEquals(p.get[String]("kind").toOption, Some("metadata-correction"))
    assertEquals(p.get[String]("reason").toOption, Some("Console contract: sub-02 TR from header"))
    assertEquals(p.get[String]("proposedBy").toOption, Some(principal))
    assertEquals(p.get[String]("acceptedBy").toOption, Some(principal))
    val changes = p.downField("changesFromParent")
    assertEquals(changes.downField("added").get[Vector[String]]("paths").toOption, Some(Vector(newSidecar)))
    assertEquals(changes.downField("changed").get[Int]("count").toOption, Some(0))
    assertEquals(changes.downField("removed").get[Int]("count").toOption, Some(0))
    assert(!p.focus.get.noSpaces.contains("sha256") && p.downField("source").failed, "provenance discloses paths only")

  private def assertPreview(base: Json, preview: Json): Unit =
    val added = preview.hcursor.get[Vector[Json]]("contentChanges").toOption.get.find(j => str(j, "path") == newSidecar).get
    assert(added.hcursor.downField("before").focus.forall(_.isNull), "a new sidecar has no previous entry")
    val moved = preview.hcursor.get[Vector[Json]]("metadataChanges").toOption.get.map(str(_, "path"))
    assert(moved.contains(nback2), moved.toString)
    // "Resolved", not "Superseded": the same check (rule and target) survives and no longer carries the code,
    // even though it may still carry unrelated findings.
    val candidate = preview.hcursor.downField("candidate").focus.get
    val before = header(base).get
    val after = header(candidate).get
    assertEquals(str(after, "rule"), str(before, "rule"))
    assert(!after.hcursor.get[Vector[Json]]("findings").toOption.get.exists(j => str(j, "code") == "MriRepetitionTimeMismatch"))
    // Moved facts are shown for checks whose inputs include the edited sidecar.
    assert(after.hcursor.get[Vector[Json]]("inputs").toOption.get.exists(j => str(j, "path") == newSidecar))
    assertEquals(flat(after.hcursor.downField("facts").focus.get).get("timingMetadata.RepetitionTime.value").map(BigDecimal(_)), Some(BigDecimal(2)))

  private def header(j: Json): Option[Json] =
    val a = j.hcursor.downField("assessment")
    a.get[Vector[Json]]("checks").toOption.get.find(c => str(c, "target") == nback2 && str(c, "rule").startsWith("bids-mri-header-consistency"))

private object ConsoleSuite:
  val nback1 = "sub-01/func/sub-01_task-nback_run-1_bold.nii"
  val events1 = "sub-01/func/sub-01_task-nback_run-1_events.tsv"
  val ap1 = "sub-01/fmap/sub-01_dir-AP_epi.nii"
  val nback2 = "sub-02/func/sub-02_task-nback_run-1_bold.nii"
  val rest2 = "sub-02/func/sub-02_task-rest_bold.nii"
  val ap2 = "sub-02/fmap/sub-02_dir-AP_epi.nii"
  val pa2 = "sub-02/fmap/sub-02_dir-PA_epi.nii"
  val newSidecar = "sub-02/func/sub-02_task-nback_run-1_bold.json"

  private val parser = new JawnParser()
  def str(j: Json, field: String): String = j.hcursor.get[String](field).toOption.getOrElse("")
  def to(j: Json): Vector[String] = j.hcursor.get[Vector[String]]("to").toOption.get
  def pathOf(j: Json): Option[String] = j.hcursor.get[String]("path").toOption
  def findings(j: Json): Vector[Json] = j.hcursor.downField("assessment").get[Vector[Json]]("findings").toOption.get
  /** Mirrors app.js messageShape. */
  def shape(m: String): String = m.replaceAll("""\brow \d+""", "row …").replaceAll("""\bcolumn \d+""", "column …")
  /** Mirrors app.js flatFacts: dotted paths to scalar leaves, rendered as text. */
  def flat(j: Json, prefix: String = ""): Map[String, String] = j.asObject match
    case Some(o) => o.toVector.flatMap((k, v) => flat(v, if prefix.isEmpty then k else s"$prefix.$k")).toMap
    case None => Map(prefix -> j.asString.getOrElse(j.noSpaces))

  final case class Http(client: Client[IO], base: String, token: String):
    private def request(method: Method, target: String): Request[IO] =
      Request[IO](method, Uri.unsafeFromString(base + target)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
    def raw(target: String): IO[(Status, String)] =
      client.run(Request[IO](Method.GET, Uri.unsafeFromString(base + target))).use(r => r.as[String].map(r.status -> _))
    def json(method: Method, target: String, body: Option[Json] = None, key: Option[String] = None): IO[(Status, Json)] =
      val withBody = body.fold(request(method, target))(b => request(method, target).withEntity(b.noSpaces)
        .putHeaders(Header.Raw(CIString("Content-Type"), "application/json")))
      val req = key.fold(withBody)(k => withBody.putHeaders(Header.Raw(CIString("Idempotency-Key"), k)))
      client.run(req).use(r => r.as[String].flatMap(t => IO.fromEither(parser.parse(t))).map(r.status -> _))

  /** Two subjects of synthetic BIDS with real NIfTI-1 headers. Values are invented; this is not scientific data. */
  def source(root: Path): IO[Unit] = IO.blocking {
    def write(path: String, bytes: Array[Byte]): Unit =
      val target = root.resolve(path)
      Files.createDirectories(target.getParent)
      val _ = Files.write(target, bytes)
    def text(path: String, value: String): Unit = write(path, value.getBytes(StandardCharsets.UTF_8))
    text("dataset_description.json", """{"Name":"Sanctity console contract fixture","BIDSVersion":"1.10.0","DatasetType":"raw"}""" + "\n")
    text("README", "Synthetic fixture for the console contract; not scientific data.\n")
    text("participants.tsv", "participant_id\tage\nsub-01\t24\nsub-02\t31\n")
    text("task-nback_bold.json", """{"TaskName":"nback","RepetitionTime":1.5}""" + "\n")
    text("task-rest_bold.json", """{"TaskName":"rest","RepetitionTime":1.5}""" + "\n")
    for s <- Vector("01", "02") do
      // sub-02's nback header says 2.0 s against the inherited 1.5 s: the mismatch the console's new sidecar resolves.
      write(s"sub-$s/func/sub-${s}_task-nback_run-1_bold.nii", nifti(if s == "02" then 2.0f else 1.5f, 12))
      write(s"sub-$s/func/sub-${s}_task-rest_bold.nii", nifti(1.5f, 12))
      // Twelve volumes at 1.5 s end at 18 s; sub-01 has two rows past the window.
      val onsets = Vector(6.0, 9.0, 12.0) ++ (if s == "01" then Vector(30.0, 33.0) else Vector.empty)
      text(s"sub-$s/func/sub-${s}_task-nback_run-1_events.tsv", "onset\tduration\ttrial_type\n" + onsets.map(o => s"$o\t1.0\t2back\n").mkString)
      for (dir, pe) <- Vector("AP" -> "j-", "PA" -> "j") do
        write(s"sub-$s/fmap/sub-${s}_dir-${dir}_epi.nii", nifti(1.0f, 1))
        // sub-01 uses IntendedFor; sub-02's rest run selects its fieldmaps through B0FieldSource instead.
        val intended = if dir == "AP" then s""","IntendedFor":["bids::sub-$s/func/sub-${s}_task-nback_run-1_bold.nii"""" +
          (if s == "01" then s""","bids::sub-$s/func/sub-${s}_task-rest_bold.nii"]""" else "]") else ""
        text(s"sub-$s/fmap/sub-${s}_dir-${dir}_epi.json",
          s"""{"B0FieldIdentifier":"pepolar$s","PhaseEncodingDirection":"$pe","TotalReadoutTime":0.0581$intended}""" + "\n")
    text("sub-02/func/sub-02_task-rest_bold.json", """{"B0FieldSource":"pepolar02"}""" + "\n")
  }

  private def nifti(tr: Float, volumes: Int): Array[Byte] =
    val data = ByteBuffer.allocate(352 + 4 * 8 * volumes).order(ByteOrder.LITTLE_ENDIAN)
    data.putInt(0, 348)
    data.putShort(40, (if volumes > 1 then 4 else 3).toShort)
    (1 to 3).foreach(i => data.putShort(40 + 2 * i, 2.toShort))
    data.putShort(48, volumes.toShort)
    (5 to 7).foreach(i => data.putShort(40 + 2 * i, 1.toShort))
    data.putShort(70, 16.toShort)
    data.putShort(72, 32.toShort)
    data.putFloat(76, 1f)
    (1 to 3).foreach(i => data.putFloat(76 + 4 * i, 2f))
    data.putFloat(92, tr)
    (5 to 7).foreach(i => data.putFloat(76 + 4 * i, 1f))
    data.putFloat(108, 352f)
    data.putFloat(112, 1f)
    data.put(123, 10.toByte)
    data.position(344)
    data.put(Array[Byte]('n'.toByte, '+'.toByte, '1'.toByte, 0))
    data.array()
