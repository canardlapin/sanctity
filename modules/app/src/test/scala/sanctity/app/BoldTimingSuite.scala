package sanctity.app

import bids4s.NiftiHeaderFacts
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import sanctity.application.BlobStore
import sanctity.bids.{BoldTimingChecks, MriHeaderChecks}
import sanctity.kernel.*
import java.util.UUID

final class BoldTimingSuite extends FunSuite:
  private def n(value: String): MetadataValue = MetadataValue.Number(BigDecimal(value))
  private def a(values: String*): MetadataValue = MetadataValue.Array(values.toVector.map(n))
  private val regular = Map("RepetitionTime" -> n("2"))
  private val header = NiftiHeaderFacts.parse(MriFixtures.prefix("bold-timing/v1-be-axis2.nii")).toOption.get
  private def evaluate(fields: Map[String, MetadataValue], axis: Int = 3) = BoldTimingChecks.evaluate(Some(header), axis, fields)
  private def errors(o: BoldTimingChecks.Observation) = o.issues.filter(_.severity == Severity.Error)

  Vector("i" -> Vector("0", "0.5"), "j" -> Vector("0", "0.5", "1"), "k" -> Vector("0", "0.5", "0", "0.5")).foreach { (axis, times) =>
    Vector("", "-").foreach { sign =>
      test(s"$axis$sign maps times in voxel-index order without sorting multiband onsets") {
        val fields = regular ++ Map("SliceTiming" -> a(times*), "SliceEncodingDirection" -> MetadataValue.Text(axis + sign))
        val observed = evaluate(fields, "ijk".indexOf(axis) + 1)
        assertEquals(errors(observed), Vector.empty)
        assertEquals(observed.facts("sliceTimesByVoxelIndex"), a((if sign == "-" then times.reverse else times)*))
      }
    }
  }

  test("header-only axis is used; missing axes are explicit and never guessed") {
    val fields = regular + ("SliceTiming" -> a("0", "0.5", "1"))
    val known = evaluate(fields, 2)
    assertEquals(errors(known), Vector.empty)
    assertEquals(known.facts("sliceAxis"), MetadataValue.Text("j"))
    val unknown = evaluate(fields, 0)
    assertEquals(errors(unknown), Vector.empty)
    assert(unknown.issues.exists(_.code == "AxisUnavailable"))
    assertEquals(unknown.facts("sliceMappingAvailable"), MetadataValue.Flag(false))
    assert(!unknown.facts.contains("sliceTimesByVoxelIndex"))
    val declared = evaluate(fields + ("SliceEncodingDirection" -> MetadataValue.Text("j-")), 0)
    assertEquals(declared.facts("sliceTimesByVoxelIndex"), a("1", "0.5", "0"))
  }

  Vector(
    "axis conflict" -> (Map("SliceEncodingDirection" -> MetadataValue.Text("j")), "AxisConflict"),
    "invalid direction" -> (Map("SliceEncodingDirection" -> MetadataValue.Text("z")), "Direction"),
    "slice count" -> (Map("SliceTiming" -> a("0", "0.5")), "SliceCount"),
    "negative slice" -> (Map("SliceTiming" -> a("0", "-1", "0", "0")), "Value"),
    "slice boundary" -> (Map("SliceTiming" -> a("0", "2", "0", "0")), "Window"),
    "delay boundary" -> (Map("DelayTime" -> n("2")), "Window"),
    "negative delay" -> (Map("DelayTime" -> n("-0.1")), "Value"),
    "fractional discard" -> (Map("NumberOfVolumesDiscardedByScanner" -> n("0.5")), "DiscardCount"),
    "negative discard" -> (Map("NumberOfVolumesDiscardedByUser" -> n("-1")), "DiscardCount"),
    "frame and regular TR" -> (Map("FrameAcquisitionDuration" -> n("1")), "Combination"),
    "string slice" -> (Map("SliceTiming" -> MetadataValue.Array(Vector(MetadataValue.Text("0")))), "Value"),
    "null slice" -> (Map("SliceTiming" -> MetadataValue.Null), "Value"),
    "empty slice" -> (Map("SliceTiming" -> a()), "Value"),
    "overflow" -> (Map("SliceTiming" -> a("1e309")), "Value")
  ).foreach { (name, data) =>
    test("reject malformed timing: " + name) {
      val observed = evaluate(regular ++ data._1)
      assert(errors(observed).exists(_.code == data._2), observed.toString)
      assertEquals(observed.facts("consistent"), MetadataValue.Flag(false))
    }
  }

  test("variable timing checks counts without events and does not extrapolate final duration") {
    val variable = Map("VolumeTiming" -> a("5", "6.5", "9"), "SliceTiming" -> a("0", "0.5", "0", "0.5"))
    val observed = evaluate(variable)
    assertEquals(errors(observed), Vector.empty)
    assertEquals(observed.facts("lastVolumeOnsetSeconds"), n("4"))
    assertEquals(observed.facts("volumeTimingOriginSeconds"), n("5"))
    assert(!observed.facts.contains("comparisonEndSeconds"))
    assert(errors(evaluate(variable.updated("VolumeTiming", a("0", "1")))).exists(_.code == "VolumeCount"))
    assert(errors(evaluate(variable.updated("VolumeTiming", a("0", "0", "1")))).nonEmpty)
    assert(errors(evaluate(variable.updated("FrameAcquisitionDuration", n("2")))).exists(_.code == "Window"))
    val frame = evaluate(variable.removed("SliceTiming").updated("FrameAcquisitionDuration", n("1")))
    assertEquals(errors(frame), Vector.empty)
    assertEquals(frame.facts("comparisonEndSeconds"), n("5"))
  }

  test("discard counts are evidence and never offset the stored-volume time origin") {
    val observed = evaluate(regular ++ Map("NumberOfVolumesDiscardedByScanner" -> n("4"), "NumberOfVolumesDiscardedByUser" -> n("2")))
    assertEquals(errors(observed), Vector.empty)
    assertEquals(observed.facts("lastVolumeOnsetSeconds"), n("4"))
    assertEquals(observed.facts("comparisonEndSeconds"), n("6"))
  }

  test("large timing arrays and unavailable image facts have bounded errors") {
    val observed = evaluate(regular + ("SliceTiming" -> MetadataValue.Array(Vector.fill(100001)(n("0")))))
    assert(errors(observed).exists(_.code == "Limit"))
    assert(observed.issues.size <= BoldTimingChecks.MaxFindings + 1)
    assert(errors(BoldTimingChecks.evaluate(None, 0, regular)).exists(_.code == "Image"))
  }

  private val oracle = MriFixtures.json("bold-timing/independent-results.json").hcursor.downField("images").focus.get.asObject.get
  oracle.toVector.foreach { (name, expected) =>
    test("shared bounded reader agrees with independent slice-axis observations: " + name) {
      val bytes = MriFixtures.bytes(name)
      val p = RelPath.from("sub-01/func/sub-01_task-rest_bold.nii" + (if name.endsWith(".gz") then ".gz" else "")).toOption.get
      val file: Entry.File = Entry.File(p, Id[ArtifactTag](new UUID(0, 1)), BlobRef.from(Digest.bytes(bytes), bytes.length.toLong).toOption.get)
      val manifest = Manifest.from(Vector(Entry.Directory(RelPath.from("sub-01").toOption.get),
        Entry.Directory(RelPath.from("sub-01/func").toOption.get), file)).toOption.get
      val interpretation = Interpretation.from(manifest.id, SemanticProfile("test", "b", "s", "c"),
        Vector(ArtifactMetadata(p, Map.empty, Vector.empty, None)), Vector(ArtifactFacts(p, "sub-01", None, "bold", "nii", Map("sub" -> "01", "task" -> "rest"))),
        queryVersion = Some("scientific-query-v1")).fold(problem => fail(problem.message), identity)
      (for
        reads <- Ref.of[IO, Int](0)
        source = new BlobStore[IO]:
          def put(data: Stream[IO, Byte]): IO[BlobRef] = IO.raiseError(new UnsupportedOperationException)
          def verify(blob: BlobRef): IO[Unit] = IO.unit
          def read(blob: BlobRef): Stream[IO, Byte] = Stream.eval(reads.update(_ + 1)).drain ++ Stream.emits(bytes)
        result <- new MriHeaderChecks[IO](source).inspect(manifest, interpretation)
        count <- reads.get
        _ = assertEquals(count, 1)
        check = result._1.find(_.rule == BoldTimingChecks.Rule).get
        _ = assertEquals(check.inputs, Vector(CheckInput("image", p, file.blob)))
        _ = assertEquals(check.facts("headerSliceDimension"), MetadataValue.Number(expected.hcursor.get[Int]("sliceDimension").toOption.get))
        _ = assertEquals(result._2(p).toOption.get.dimensions, expected.hcursor.get[Vector[Long]]("dimensions").toOption.get)
        _ = assertEquals(file.blob.digest.value, expected.hcursor.get[String]("sha256").toOption.get)
      yield ()).unsafeRunSync()
    }
  }
