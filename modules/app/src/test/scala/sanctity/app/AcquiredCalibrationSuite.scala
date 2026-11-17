package sanctity.app

import bids4s.NiftiHeaderFacts
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import sanctity.application.*
import sanctity.kernel.*
import sanctity.processing.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

final class AcquiredCalibrationSuite extends FunSuite:
  private val oracle = MriFixtures.json("calibration-inputs/independent-results.json").hcursor.downField("images").focus.get.asObject.get.toVector
  private def path(s: String): RelPath = RelPath.from(s).toOption.get
  private def decoded(name: String, bytes: Array[Byte]): Array[Byte] =
    if !name.endsWith(".gz") then bytes
    else
      val stream = new GZIPInputStream(new ByteArrayInputStream(bytes))
      try stream.readAllBytes() finally stream.close()

  test("all synthetic and acquired calibration transforms match independent NiBabel observations") {
    oracle.foreach { (name, expected) =>
      val bytes = MriFixtures.bytes(name)
      val e = expected.hcursor
      assertEquals(Digest.bytes(bytes).value, e.get[String]("sha256").toOption.get)
      val header = NiftiHeaderFacts.parse(decoded(name, bytes)).toOption.get
      assertEquals(header.dimensions, e.get[Vector[Long]]("dimensions").toOption.get)
      val geometry = NiftiGeometry.inspect(header).toOption.get
      Vector("qform" -> geometry.qform, "sform" -> geometry.sform).foreach { (kind, form) =>
        assertEquals(form.get.code, e.get[Int](kind + "Code").toOption.get)
        form.get.rows.flatten.zip(e.get[Vector[Vector[Double]]](kind + "Millimetres").toOption.get.flatten)
          .foreach((a, b) => assertEqualsDouble(a, b, 1e-10))
      }
    }
  }

  test("acquired EPI pair has valid individual transforms but falls outside the same-grid policy") {
    val geometries = oracle.filter(_._1.contains("ds004146")).map { (name, _) =>
      NiftiGeometry.inspect(NiftiHeaderFacts.parse(decoded(name, MriFixtures.bytes(name))).toOption.get).toOption.get
    }
    assertEquals(geometries.size, 2)
    assert(NiftiGeometry.agreement(geometries).isLeft)
    assert(MriFixtures.json("calibration-inputs/independent-results.json").hcursor.get[Double]("acquiredEpiMaximumCornerDistanceMm").toOption.get > 0.001)
  }

  test("full acquired SBRef and EPI payloads agree with independent scalar observations") {
    Resource.make(IO.blocking(Files.createTempDirectory("sanctity-acquired-calibration-")))(TestFiles.remove).use { root =>
      ReferenceSystem.create(root.resolve("storage")).flatMap { system =>
        oracle.filter(_._1.startsWith("acquired-calibration/")).traverse_ { (name, expected) =>
          for
            blob <- system.store.put(Stream.emits(MriFixtures.bytes(name)).covary[IO])
            artifact <- IdSource.random[IO].next[ArtifactTag]
            result <- new NiftiPayload[IO](system.store).inspect(Entry.File(path(name), artifact, blob)).flatMap(Checked.lift[IO, PayloadObservation])
            _ = assertEquals(result.voxels, expected.hcursor.get[Long]("voxelsChecked").toOption.get)
            _ = assertEquals(result.minimum, expected.hcursor.get[Double]("minimum").toOption.get)
            _ = assertEquals(result.maximum, expected.hcursor.get[Double]("maximum").toOption.get)
          yield ()
        }
      }
    }.unsafeRunSync()
  }
