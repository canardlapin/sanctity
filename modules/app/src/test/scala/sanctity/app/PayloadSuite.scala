package sanctity.app

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import sanctity.application.*
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import sanctity.processing.*
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.Files

class PayloadSuite extends FunSuite:
  private def fixture(run: RepositorySystem => IO[Unit]): Unit =
    cats.effect.Resource.make(IO.blocking(Files.createTempDirectory("sanctity-payload-")))(TestFiles.remove).use { root =>
      ReferenceSystem.create(root.resolve("storage")).flatMap(run)
    }.unsafeRunSync()
  private def check(s: RepositorySystem, bytes: Array[Byte], gzip: Boolean = false): IO[Either[Problem, PayloadObservation]] =
    for
      blob <- s.store.put(Stream.emits(bytes).covary[IO])
      id <- IdSource.random[IO].next[ArtifactTag]
      result <- new NiftiPayload[IO](s.store).inspect(Entry.File(RelPath.from(if gzip then "image.nii.gz" else "image.nii").toOption.get, id, blob))
    yield result

  test("complete payload assessment agrees with analytical scaled values across versions, endianness and scalar types") {
    fixture { s =>
      (for v <- Vector(1, 2); little <- Vector(true, false); datatype <- NiftiPayload.SupportedTypes.toVector.sorted; gzip <- Vector(false, true)
      yield (v, little, datatype, gzip)).traverse_ { (v, little, datatype, zipped) =>
        val bytes = TemporalFixture.image(v, little, datatype)
        check(s, if zipped then TemporalFixture.gzip(bytes) else bytes, zipped).map { result =>
          val observed = result.fold(p => fail(p.message), identity)
          assertEquals(observed.voxels, 12L)
          assertEqualsDouble(observed.mean, 23, 1e-12)
          assertEquals((observed.minimum, observed.maximum), (12.0, 34.0))
          assertEquals((observed.header.qformCode, observed.header.sformCode), (1, 2))
          assertEquals(observed.header.qoffset.head, 7.0)
          assertEquals(observed.header.sformRows.head.last, 11.0)
        }
      }
    }
  }

  test("truncated arrays, bad CRC/trailers, extra payload and nonfinite scaling/voxels fail explicitly") {
    fixture { s =>
      val good = TemporalFixture.image(datatype = 64)
      val crc = TemporalFixture.gzip(good)
      crc(crc.length - 8) = (crc(crc.length - 8) ^ 1).toByte
      def changed(at: Int, value: Double, single: Boolean): Array[Byte] =
        val bytes = good.clone()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if single then b.putFloat(at, value.toFloat) else b.putDouble(at, value)
        bytes
      Vector((good.dropRight(1), false), (good ++ Array[Byte](0), false), (crc, true),
        (TemporalFixture.gzip(good).dropRight(4), true), (changed(352, Double.NaN, false), false),
        (changed(112, Double.PositiveInfinity, true), false), (changed(116, Double.NaN, true), false),
        (changed(352, 1e100, false), false)).traverse_ { (bytes, gzip) => check(s, bytes, gzip).map(r => assert(r.isLeft)) }
    }
  }

  test("disabled scaling is identity and 64-bit integer precision is outside this recipe") {
    fixture { s =>
      val identity = TemporalFixture.image()
      ByteBuffer.wrap(identity).order(ByteOrder.LITTLE_ENDIAN).putFloat(112, 0).putFloat(116, Float.NaN)
      val unsupported = TemporalFixture.image(datatype = 64)
      ByteBuffer.wrap(unsupported).order(ByteOrder.LITTLE_ENDIAN).putShort(70, 1024.toShort)
      for
        plain <- check(s, identity)
        _ = assertEqualsDouble(plain.toOption.get.mean, 6.5, 1e-12)
        rejected <- check(s, unsupported)
        _ = assert(rejected.isLeft)
      yield ()
    }
  }

  test("header-only acceptance does not establish temporal readiness; corrupt payload is rejected by the opt-in profile") {
    cats.effect.Resource.make(IO.blocking(Files.createTempDirectory("sanctity-payload-profile-")))(TestFiles.remove).use { root =>
      for
        _ <- SyntheticFixture.create(root.resolve("source"))
        _ <- IO.blocking {
          val bytes = TemporalFixture.image(datatype = 64)
          ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putDouble(352, Double.NaN)
          val _ = Files.write(root.resolve("source").resolve(SyntheticFixture.firstImage), bytes)
        }
        s <- ReferenceSystem.create(root.resolve("storage"))
        header <- s.repository.importNative(s.context, s.dataset.id, new NativeDirectorySource[IO](root.resolve("source"))).flatMap(Checked.lift[IO, Preview])
        _ = assert(header.candidate.assessment.admissible)
        c = header.candidate
        readiness = new TemporalInputPlanner().assess(c.manifest, c.interpretation, c.assessment,
          Vector(RelPath.from(SyntheticFixture.firstImage).toOption.get), TemporalStatistics.Contract).toOption.get
        _ = assert(!readiness.ready)
        full <- new PayloadBidsInterpreter[IO](s.store).prepare(c.manifest)
        _ = assert(!full._2.admissible)
        _ = assert(full._2.errors.exists(_.code == "NiftiPayload"))
      yield ()
    }.unsafeRunSync()
  }
