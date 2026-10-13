package sanctity.app

import bids4s.*
import _root_.io.circe.Json
import _root_.io.circe.jawn.JawnParser
import munit.FunSuite
import sanctity.kernel.Digest
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPInputStream

object MriFixtures:
  def bytes(name: String): Array[Byte] =
    val input = Option(getClass.getResourceAsStream("/mri-conformance/" + name)).getOrElse(sys.error("missing MRI fixture " + name))
    try input.readAllBytes() finally input.close()
  def json(name: String): Json = new JawnParser().parse(new String(bytes(name), UTF_8)).toOption.get
  def prefix(name: String): Array[Byte] =
    val b = bytes(name)
    if !name.endsWith(".gz") then b.take(540)
    else
      val stream = new GZIPInputStream(new ByteArrayInputStream(b))
      try stream.readNBytes(540) finally stream.close()
  val phase = "sub-01/ses-mri/fmap/sub-01_ses-mri_acq-func_phasediff.nii.gz"
  val first = phase.replace("phasediff", "magnitude1")
  val second = phase.replace("phasediff", "magnitude2")

final class MriConformanceSuite extends FunSuite:
  private def value[A](v: Either[BidsError, A]): A = v.fold(e => fail(e.message), identity)
  private def header(name: String) = NiftiHeaderFacts.parse(MriFixtures.prefix(name)).fold(e => fail(e.toString), identity)
  private def semantics(paths: Vector[String], docs: Map[String, JsonValue.Obj]) =
    val manifest = BidsManifest.fromRelativePathsChecked(paths)
    assertEquals(manifest.errors, Vector.empty)
    val resolver = BidsMetadataResolver(manifest.value, docs.map((p, j) => BidsPath(p) -> j))
    val resolved = paths.filter(p => p.endsWith(".nii.gz") || p.endsWith(".nii")).map(p => BidsPath(p) -> value(resolver.resolve(BidsPath(p)))).toMap
    (manifest.value, resolved)
  private val provenance = MriFixtures.json("ds000117/provenance.json").hcursor
  private val inventory = provenance.get[Vector[String]]("inventory").toOption.get
  private val docs = inventory.filter(_.endsWith(".json")).map(p => p -> value(BidsJson.parseObject(new String(MriFixtures.bytes("ds000117/" + p), UTF_8)))).toMap

  test("all retained independent bytes and source licenses match their provenance") {
    Vector("" -> "source.json", "ds000117/" -> "ds000117/provenance.json").foreach { (base, source) =>
      MriFixtures.json(source).hcursor.downField("files").focus.get.asObject.get.toVector.foreach { (p, record) =>
        val b = MriFixtures.bytes(base + p)
        assertEquals(Digest.bytes(b).value, record.hcursor.get[String]("sha256").toOption.get)
        assertEquals(b.length, record.hcursor.get[Int]("size").toOption.get)
      }
    }
    assertEquals(MriFixtures.json("ds000117/dataset_description.json").hcursor.get[String]("License").toOption.get, "CC0")
    assert(new String(MriFixtures.bytes("COPYING"), UTF_8).contains("MIT License"))
  }

  private val oracle = MriFixtures.json("header-oracle.json").hcursor.downField("images").focus.get.asObject.get.toVector
  oracle.foreach { (name, expected) =>
    test("portable header observations equal independent NiBabel record: " + name) {
      val h = header(name)
      def nums(v: Vector[Double]): Json = Json.arr(v.map(Json.fromDoubleOrNull)*)
      val actual = Json.obj(
        "sha256" -> Json.fromString(Digest.bytes(MriFixtures.bytes(name)).value),
        "version" -> Json.fromInt(h.version), "byteOrder" -> Json.fromString(h.byteOrder.toString),
        "dimensions" -> Json.arr(h.dimensions.map(Json.fromLong)*),
        "pixelDimensions" -> nums(h.pixelDimensions), "unitsCode" -> Json.fromInt(h.unitsCode),
        "qformCode" -> Json.fromInt(h.qformCode), "sformCode" -> Json.fromInt(h.sformCode),
        "quaternion" -> nums(h.quaternion), "qoffset" -> nums(h.qoffset),
        "sformRows" -> Json.arr(h.sformRows.map(nums)*), "datatype" -> Json.fromInt(h.datatype),
        "bitsPerVoxel" -> Json.fromInt(h.bitsPerVoxel), "dataOffset" -> Json.fromLong(h.dataOffset))
      assertEquals(actual, expected)
      val suffix = if name == "anatomical.nii" then "T1w" else if name.contains("phasediff") then "phasediff"
        else if name.contains("magnitude") then "magnitude1" else "bold"
      assertEquals(MriHeaderConsistency.check(h, suffix, JsonValue.Obj(Map.empty)).filter(_.isError), Vector.empty)
    }
  }

  test("external phasediff family and all nine declared functional targets resolve exactly") {
    val (manifest, resolved) = semantics(inventory, docs)
    val families = BidsPhaseDifferenceResolver.fromResolved(manifest, resolved)
    assertEquals(families.issues, Vector.empty)
    assertEquals(families.groups.map(_.phase.value), Vector(MriFixtures.phase))
    assertEquals(families.groups.head.magnitudes.map(_.value), Vector(MriFixtures.first, MriFixtures.second))
    val links = BidsFieldmapResolver.fromResolved(manifest, resolved)
    assert(!links.issues.exists(_.isError), links.issues.toString)
    val intended = links.associations.filter(_.sources.contains(BidsPath(MriFixtures.phase)))
    assertEquals(intended.map(_.target.value).toSet, inventory.filter(p => p.endsWith("_bold.nii.gz")).toSet)
    assertEquals(intended.size, 9)
    assert(intended.forall(_.evidence.exists(_.document.value == MriFixtures.phase.stripSuffix(".nii.gz") + ".json")))
  }

  test("explicit cross-session mutation retains consumer references and keeps magnitude matching local") {
    def move(p: String): String = if p.contains("/fmap/") then p.replace("ses-mri", "ses-cal") else p
    val (manifest, resolved) = semantics(inventory.map(move), docs.map((p, j) => move(p) -> j))
    val families = BidsPhaseDifferenceResolver.fromResolved(manifest, resolved)
    assertEquals(families.issues, Vector.empty)
    assert(families.groups.head.magnitudes.forall(_.value.contains("ses-cal")))
    val links = BidsFieldmapResolver.fromResolved(manifest, resolved)
    assert(!links.issues.exists(_.isError))
    assertEquals(links.associations.filter(_.sources.contains(BidsPath(move(MriFixtures.phase)))).size, 9)
    val missing = inventory.filterNot(_ == MriFixtures.first)
    val (m, r) = semantics(missing, docs)
    assert(BidsPhaseDifferenceResolver.fromResolved(m, r).issues.exists(_.code == BidsPhaseDifferenceIssueCode.MissingMagnitude1))
  }

  test("real header mutation detects malformed geometry while TR corrections remain visible discrepancies") {
    val original = MriFixtures.prefix("example4d.nii.gz")
    val broken = original.clone()
    java.nio.ByteBuffer.wrap(broken).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(80, 0f)
    val parsed = NiftiHeaderFacts.parse(broken).toOption.get
    assert(MriHeaderConsistency.check(parsed, "bold", JsonValue.Obj(Map.empty)).exists(i => i.code == MriHeaderIssueCode.VoxelSize && i.isError))
    val issues = MriHeaderConsistency.check(header("example4d.nii.gz"), "bold", JsonValue.Obj(Map("RepetitionTime" -> JsonValue.Num(1.5))))
    assert(issues.exists(i => i.code == MriHeaderIssueCode.RepetitionTimeMismatch && !i.isError))
    assertEquals(MriFixtures.prefix("example4d.nii.gz").toVector, original.toVector)
  }


  test("pinned official outcomes disclose the precise overlap and policy differences") {
    val records = MriFixtures.json("official-results.json").hcursor.get[Vector[Json]]("cases").toOption.get
      .map(j => j.hcursor.get[String]("case").toOption.get -> j.hcursor.get[Vector[Vector[String]]]("errors").toOption.get).toMap
    assertEquals(records("complete"), Vector.empty)
    assertEquals(records("without-optional-magnitude2"), Vector.empty)
    // These local structural rules are stricter than the pinned validator's exercised checks.
    assertEquals(records("missing-magnitude1"), Vector.empty)
    assertEquals(records("invalid-spatial-spacing"), Vector.empty)
    assert(records("missing-echo-time2").contains(Vector("SIDECAR_KEY_REQUIRED", "EchoTime2")))
    assertEquals(records("header-tr-mismatch"), Vector(Vector("REPETITION_TIME_MISMATCH", "")))
    assertEquals(MriHeaderConsistency.temporalSpacingSeconds(header("example4d.nii.gz")), Some(2000.0))
  }
