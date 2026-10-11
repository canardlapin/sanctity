package sanctity.app

import bids4s.*
import _root_.io.circe.Json
import _root_.io.circe.jawn.JawnParser
import munit.FunSuite
import sanctity.kernel.Digest
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.{GZIPInputStream, ZipInputStream}

/** Independently authored examples plus explicitly reviewed negative mutations.
  * No network or validator executable is needed for this suite.
  */
final class ConformanceSuite extends FunSuite:
  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
  private def resource(name: String): Array[Byte] =
    val stream = Option(getClass.getResourceAsStream("/conformance/" + name)).getOrElse(fail("missing fixture " + name))
    try stream.readAllBytes() finally stream.close()
  private def json(name: String): Json = parser.parse(new String(resource(name), UTF_8)).toOption.get
  private def value[A](e: Either[BidsError, A]): A = e.fold(error => fail(error.message), identity)
  private lazy val files: Map[String, Array[Byte]] =
    val stream = new ZipInputStream(new java.io.ByteArrayInputStream(resource("bids-examples.zip")))
    try
      Iterator.continually(stream.getNextEntry).takeWhile(_ != null).map { entry =>
        entry.getName -> stream.readAllBytes()
      }.toMap
    finally stream.close()
  private val context = value(BidsContext.from("conformance-mri", BidsRegistry.Builtin, Some("1.11.1"),
    "Checked filename, inheritance and selected MRI companion subset; official schema validated separately"))
  private def dataset(name: String): Map[String, String] = files.collect {
    case (path, bytes) if path.startsWith(name + "/") => path.stripPrefix(name + "/") -> new String(bytes, UTF_8)
  }
  private def semantics(name: String): (BidsManifest, BidsMetadataResolver) =
    val docs = dataset(name)
    val manifest = BidsManifest.fromRelativePathsChecked(docs.keys.toVector, context = context)
    assertEquals(manifest.errors, Vector.empty)
    val sidecars = docs.collect { case (path, text) if path.endsWith(".json") => BidsPath(path) -> value(BidsJson.parseObject(text)) }
    manifest.value -> BidsMetadataResolver(manifest.value, sidecars)

  test("external fixture bytes, inventory and pinned schema match the recorded digests") {
    val source = json("source.json").hcursor
    assertEquals(Digest.bytes(resource("bids-examples.zip")).value, source.get[String]("archiveSha256").toOption.get)
    val expected = source.get[Map[String, String]]("files").toOption.get
    assertEquals(files.keySet, expected.keySet)
    files.foreach((name, bytes) => assertEquals(Digest.bytes(bytes).value, expected(name)))
    val stream = new GZIPInputStream(new java.io.ByteArrayInputStream(resource("schema-1.11.1.json.gz")))
    val schema = try stream.readAllBytes() finally stream.close()
    assertEquals(Digest.bytes(schema).value, json("validator.json").hcursor.get[String]("schemaSha256").toOption.get)
    assertEquals(parser.parse(new String(schema, UTF_8)).toOption.get.hcursor.get[String]("bids_version").toOption.get, "1.11.1")
  }

  test("ds001 root inheritance and run-specific event selection match reviewed external paths") {
    val (manifest, metadata) = semantics("ds001")
    assertEquals(manifest.files.size, 135)
    val images = manifest.query(value(BidsQuery.exact(EntityKey.Subject, "01")))
      .filter(f => f.parsed.exists(n => n.kind == "bold" && n.extension == "nii.gz"))
    assertEquals(images.map(_.fileName), (1 to 3).toVector.map(n => f"sub-01_task-balloonanalogrisktask_run-$n%02d" + "_bold.nii.gz"))
    val target = BidsPath("sub-01/func/sub-01_task-balloonanalogrisktask_run-01_bold.nii.gz")
    val resolved = value(metadata.resolve(target))
    assertEquals(resolved.metadata.fields("RepetitionTime"), JsonValue.Num(2))
    assertEquals(resolved.metadata.fields("TaskName"), JsonValue.Str("balloon analog risk task"))
    assertEquals(resolved.origins("RepetitionTime"), BidsPath("task-balloonanalogrisktask_bold.json"))
    assertEquals(BidsMetadataValidation.resolvedSidecar(target, resolved.metadata).filter(_.severity == BidsIssueSeverity.Error), Vector.empty)
    val events = value(BidsCompanionResolver(manifest).resolve(target, BidsCompanionKind.Events))
    events.outcome match
      case BidsCompanionOutcome.Selected(file) =>
        assertEquals(file.path, BidsPath("sub-01/func/sub-01_task-balloonanalogrisktask_run-01_events.tsv"))
        assert(CheckedEvents.assess(dataset("ds001")(file.path.value)).table.nonEmpty)
      case other => fail(other.toString)
  }

  test("ds114 uses session scope, inherited gradients and both inherited and local events") {
    val (manifest, metadata) = semantics("ds114")
    assertEquals(manifest.files.size, 174)
    val target = BidsPath("sub-01/ses-test/func/sub-01_ses-test_task-fingerfootlips_bold.nii.gz")
    val resolved = value(metadata.resolve(target))
    assertEquals(resolved.metadata.fields("RepetitionTime"), JsonValue.Num(2.5))
    assertEquals(resolved.origins("RepetitionTime"), BidsPath("task-fingerfootlips_bold.json"))
    val resolver = BidsCompanionResolver(manifest)
    def selected(path: BidsPath, kind: BidsCompanionKind, expected: String): Unit =
      value(resolver.resolve(path, kind)).outcome match
        case BidsCompanionOutcome.Selected(file) => assertEquals(file.path.value, expected)
        case other => fail(other.toString)
    selected(target, BidsCompanionKind.Events, "task-fingerfootlips_events.tsv")
    selected(BidsPath("sub-01/ses-test/func/sub-01_ses-test_task-linebisection_bold.nii.gz"), BidsCompanionKind.Events,
      "sub-01/ses-test/func/sub-01_ses-test_task-linebisection_events.tsv")
    val dwi = BidsPath("sub-01/ses-test/dwi/sub-01_ses-test_dwi.nii.gz")
    selected(dwi, BidsCompanionKind.Bval, "dwi.bval")
    selected(dwi, BidsCompanionKind.Bvec, "dwi.bvec")
  }

  private val cases = json("cases.json").asArray.get.toVector
  cases.foreach { c =>
    val id = c.hcursor.get[String]("id").toOption.get
    test("pinned official-validator outcome agrees for " + id) {
      val record = json("official-results.json").hcursor.get[Vector[Json]]("cases").toOption.get
        .find(_.hcursor.get[String]("case").toOption.contains(id)).get
      assertEquals(record.hcursor.get[Vector[Vector[String]]]("errors").toOption.get,
        c.hcursor.get[Vector[Vector[String]]]("officialErrors").toOption.get)
      val mutation = c.hcursor.downField("mutation").focus.filterNot(_.isNull)
      mutation match
        case None =>
          assertEquals(record.hcursor.get[Vector[Json]]("errors").toOption.get, Vector.empty)
        case Some(m) =>
          val filename = m.hcursor.get[String]("file").toOption.get
          val field = m.hcursor.get[String]("field").toOption.get
          val original = parser.parse(dataset("ds001")(filename)).toOption.get
          val modified = original.mapObject { obj =>
            if m.hcursor.get[Boolean]("remove").toOption.contains(true) then obj.remove(field)
            else obj.add(field, m.hcursor.downField("value").focus.get)
          }
          val parsed = value(BidsJson.parseObject(modified.noSpaces))
          val findings = if filename == "dataset_description.json" then
            BidsMetadataValidation.datasetDescription(BidsPath(filename), parsed)
          else BidsMetadataValidation.resolvedSidecar(BidsPath(filename), parsed)
          assert(findings.exists(_.severity == BidsIssueSeverity.Error), findings.toString)
          val expected = if id == "conflicting-timing" then "RepetitionTime" else field
          assert(findings.exists(_.field.contains(expected)), findings.toString)
    }
  }
