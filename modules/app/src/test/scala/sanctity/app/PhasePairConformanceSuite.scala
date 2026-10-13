package sanctity.app

import bids4s.*
import munit.FunSuite
import sanctity.kernel.Digest
import java.nio.charset.StandardCharsets.UTF_8

final class PhasePairConformanceSuite extends FunSuite:
  private val provenance = MriFixtures.json("phase-pair/provenance.json").hcursor
  private val inventory = provenance.downField("files").focus.get.asObject.get.keys.toVector.sorted
  private val documents = inventory.filter(_.endsWith(".json")).map { p =>
    p -> BidsJson.parseObject(new String(MriFixtures.bytes("phase-pair/dataset/" + p), UTF_8)).toOption.get
  }.toMap
  private val members = provenance.get[Vector[String]]("expectedMembers").toOption.get
  private val target = provenance.get[String]("target").toOption.get
  private def json(p: String): String = p.stripSuffix(".nii") + ".json"
  private val cases = MriFixtures.json("phase-pair/contract.json").hcursor.get[Vector[_root_.io.circe.Json]]("cases").toOption.get

  test("phase-pair dataset matches retained generator provenance and declared raw header facts") {
    inventory.foreach { path =>
      val bytes = MriFixtures.bytes("phase-pair/dataset/" + path)
      val expected = provenance.downField("files").downField(path)
      assertEquals(Digest.bytes(bytes).value, expected.get[String]("sha256").toOption.get)
      assertEquals(bytes.length, expected.get[Int]("size").toOption.get)
      if path.endsWith(".nii") then
        val header = NiftiHeaderFacts.parse(bytes).toOption.get
        assertEquals(header.dimensions, if path.contains("_bold.") then Vector(2L, 2L, 1L, 2L) else Vector(2L, 2L, 1L))
        assertEquals(header.pixelDimensions.slice(1, 4), Vector(2.0, 3.0, 4.0))
    }
  }

  cases.foreach { expected =>
    val name = expected.hcursor.get[String]("name").toOption.get
    test("independently specified phase-pair case: " + name) {
      var paths = inventory
      var docs = documents
      def changeTime(index: Int, value: Option[Double]): Unit =
        val p = json(members(index))
        docs = docs.updated(p, JsonValue.Obj(docs(p).fields - "EchoTime" ++ value.map(v => "EchoTime" -> JsonValue.Num(v))))
      def rename(from: String, to: String): Unit =
        paths = paths.map(p => if p == from then to else if p == json(from) then json(to) else p)
        docs = docs - json(from) + (json(to) -> docs(json(from)))
      name match
        case "complete-cross-session" => ()
        case "missing-phase1" => paths = paths.filterNot(_ == members(0))
        case "missing-phase2" => paths = paths.filterNot(_ == members(1))
        case "missing-magnitude1" => paths = paths.filterNot(_ == members(2))
        case "missing-magnitude2" => paths = paths.filterNot(_ == members(3))
        case "ambiguous-phase" => paths = paths :+ (members(0) + ".gz")
        case "ambiguous-magnitude" => paths = paths :+ (members(3) + ".gz")
        case "different-acquisition" => rename(members(2), members(2).replace("acq-gre", "acq-other"))
        case "run-padding" => rename(members(2), members(2).replace("run-01", "run-1"))
        case "missing-echo-time" => changeTime(1, None)
        case "reversed-echo-times" => changeTime(1, Some(0.005))
        case "magnitude-echo-mismatch" => changeTime(3, Some(0.008))
        case "missing-reference-target" =>
          val p = json(members.head)
          docs = docs.updated(p, JsonValue.Obj(docs(p).fields.updated("IntendedFor", JsonValue.Str("bids::" + target.replace("run-1", "run-99")))))
        case "mixed-representation" =>
          val p = members.head.replace("phase1", "phasediff")
          paths = paths ++ Vector(p, json(p))
          docs = docs.updated(json(p), JsonValue.Obj(Map("EchoTime1" -> JsonValue.Num(0.006), "EchoTime2" -> JsonValue.Num(0.00746))))
        case "separate-phasediff" =>
          Vector("phasediff", "magnitude1", "magnitude2").foreach { role =>
            val p = members.head.replace("acq-gre", "acq-other").replace("phase1", role)
            paths = paths ++ Vector(p, json(p))
            docs = docs.updated(json(p), JsonValue.Obj(if role == "phasediff" then
              Map("EchoTime1" -> JsonValue.Num(0.006), "EchoTime2" -> JsonValue.Num(0.00746)) else Map.empty))
          }
        case "separate-epi" =>
          val p = "sub-01/ses-cal/fmap/sub-01_ses-cal_dir-AP_epi.nii"
          paths = paths ++ Vector(p, json(p))
          docs = docs.updated(json(p), JsonValue.Obj(Map("B0FieldIdentifier" -> JsonValue.Str("other-epi"),
            "PhaseEncodingDirection" -> JsonValue.Str("j"), "TotalReadoutTime" -> JsonValue.Num(0.03))))
        case other => fail("unimplemented corpus mutation: " + other)
      val manifest = BidsManifest.fromRelativePaths(paths)
      val resolver = BidsMetadataResolver(manifest, docs.map((p, v) => BidsPath(p) -> v))
      val resolved = paths.filter(p => p.endsWith(".nii") || p.endsWith(".nii.gz")).map { p =>
        BidsPath(p) -> resolver.resolve(BidsPath(p)).toOption.get
      }.toMap
      val pairs = BidsPhasePairResolver.fromResolved(manifest, resolved)
      val fieldmaps = BidsFieldmapResolver.fromResolved(manifest, resolved)
      val findings = (pairs.issues.map(i => "PhasePair" + i.code.toString) ++
        fieldmaps.issues.filter(_.isError).map(i => "Fieldmap" + i.code.toString)).distinct.sorted
      assertEquals(pairs.groups.size, expected.hcursor.get[Int]("groups").toOption.get)
      assertEquals(findings, expected.hcursor.get[Vector[String]]("findings").toOption.get.sorted)
      if name == "complete-cross-session" then
        assertEquals(pairs.groups.head.members.map(_.value), members)
        assertEquals(pairs.groups.head.evidence.map(_.document.value).toSet, members.map(json).toSet)
        assertEquals(fieldmaps.associations.find(a => a.target.value == target && a.mechanism == BidsFieldmapMechanism.B0FieldSource)
          .get.sources.map(_.value).toSet, members.take(2).toSet)
    }
  }
