package sanctity.app

import bids4s.NiftiHeaderFacts
import munit.FunSuite
import sanctity.processing.NiftiGeometry
import sanctity.kernel.Digest
import java.nio.{ByteBuffer, ByteOrder}

final class NiftiGeometrySuite extends FunSuite:
  private def header(edit: ByteBuffer => Unit = _ => (), version: Int = 1, little: Boolean = true): NiftiHeaderFacts =
    val bytes = TemporalFixture.image(version, little)
    edit(ByteBuffer.wrap(bytes).order(if little then ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN))
    NiftiHeaderFacts.parse(bytes).toOption.get

  test("coded transforms retain separate coordinate systems and analytical voxel centres in either format/order") {
    for version <- Vector(1, 2); little <- Vector(true, false) do
      val geometry = NiftiGeometry.inspect(header(version = version, little = little)).toOption.get
      assertEquals(geometry.qform.get.code, 1)
      assertEquals(geometry.sform.get.code, 2)
      assertEquals(geometry.qform.get.point(Vector(1, 1, 0)), Vector(9.0, 3.0, 0.0))
      assertEquals(geometry.sform.get.point(Vector(1, 1, 0)), Vector(13.0, 16.0, 17.0))
      assertEquals(NiftiGeometry.agreement(Vector(geometry, geometry)), Right(0.0))
  }

  test("qfac zero follows positive fallback and a 180-degree quaternion represents the expected rotation") {
    val zero = NiftiGeometry.inspect(header(b => { b.putFloat(76, 0); () })).toOption.get
    assertEquals(zero.qform, NiftiGeometry.inspect(header()).toOption.get.qform)
    val halfTurn = NiftiGeometry.inspect(header(b => { b.putFloat(256, 1); () })).toOption.get
    assertEquals(halfTurn.qform.get.point(Vector(1, 1, 0)), Vector(9.0, -3.0, 0.0))
  }

  Vector[(String, ByteBuffer => Unit)](
    "unknown geometry" -> (b => { b.putShort(252, 0); b.putShort(254, 0); () }),
    "unknown units" -> (b => { b.put(123, 8.toByte); () }),
    "negative code" -> (b => { b.putShort(252, -1); () }),
    "future code" -> (b => { b.putShort(254, 6); () }),
    "nonfinite quaternion" -> (b => { b.putFloat(256, Float.NaN); () }),
    "oversized quaternion" -> (b => { b.putFloat(256, 1.1f); () }),
    "invalid qfac" -> (b => { b.putFloat(76, 2); () }),
    "invalid spacing" -> (b => { b.putFloat(80, 0); () }),
    "nonfinite sform" -> (b => { b.putFloat(292, Float.PositiveInfinity); () }),
    "singular sform" -> (b => { b.putFloat(280, 0); () }),
    "opposite handedness" -> (b => { b.putFloat(280, -2); () })
  ).foreach { (name, edit) => test("coded geometry rejects " + name) {
    assert(NiftiGeometry.inspect(header(edit)).isLeft)
  }}

  test("inactive form fields are ignored and a nonsingular sform shear is supported") {
    val valid = NiftiGeometry.inspect(header(b => { b.putShort(252, 0); b.putFloat(256, Float.NaN); b.putFloat(284, 0.5f); () }))
    assert(valid.isRight)
    assertEquals(valid.toOption.get.sform.get.point(Vector(1, 1, 0)), Vector(13.5, 16.0, 17.0))
  }

  test("geometry agreement distinguishes shifted, rotated and differently coded echoes from rounding noise") {
    val original = NiftiGeometry.inspect(header()).toOption.get
    val noise = NiftiGeometry.inspect(header(b => { b.putFloat(268, 7.0001f); () })).toOption.get
    assert(NiftiGeometry.agreement(Vector(original, noise)).isRight)
    Vector[ByteBuffer => Unit](
      b => { b.putFloat(268, 8); () },
      b => { b.putFloat(256, 1); () },
      b => { b.putShort(254, 3); () },
      b => { b.putShort(252, 0); () }
    ).foreach(edit => assert(NiftiGeometry.agreement(Vector(original, NiftiGeometry.inspect(header(edit)).toOption.get)).isLeft))
  }

  test("metre and micron transform units are converted to millimetres") {
    val metres = NiftiGeometry.inspect(header(b => { b.put(123, 9.toByte); () })).toOption.get
    val microns = NiftiGeometry.inspect(header(b => { b.put(123, 11.toByte); () })).toOption.get
    assertEquals(metres.qform.get.point(Vector(1, 1, 0)), Vector(9000.0, 3000.0, 0.0))
    microns.qform.get.point(Vector(1, 1, 0)).zip(Vector(0.009, 0.003, 0.0)).foreach((a, b) => assertEqualsDouble(a, b, 1e-15))
  }

  test("all acquired and synthetic echo transforms agree with the independent NiBabel oracle") {
    val cases = MriFixtures.json("multiecho-geometry-oracle.json").hcursor.downField("cases").focus.get.asObject.get
    cases.toVector.foreach { (name, expected) =>
      val parts = name.split("/", 2)
      val bytes = MriFixtures.bytes(parts(0) + "/dataset/" + parts(1))
      val cursor = expected.hcursor
      assertEquals(Digest.bytes(bytes).value, cursor.get[String]("sha256").toOption.get)
      val geometry = NiftiGeometry.inspect(NiftiHeaderFacts.parse(bytes).toOption.get).toOption.get
      Vector("qform" -> geometry.qform, "sform" -> geometry.sform).foreach { (kind, form) =>
        assertEquals(form.map(_.code).getOrElse(0), cursor.get[Int](kind + "Code").toOption.get)
        val matrix = cursor.get[Vector[Vector[Double]]](kind + "Millimetres").toOption.get
        form.get.rows.flatten.zip(matrix.flatten).foreach((a, b) => assertEqualsDouble(a, b, 1e-10))
      }
    }
  }

  test("agreement measures every pair and does not hide two opposite tolerance-edge shifts") {
    val middle = NiftiGeometry.inspect(header()).toOption.get
    val a = NiftiGeometry.inspect(header(b => { b.putFloat(268, 7.0008f); () })).toOption.get
    val b = NiftiGeometry.inspect(header(b => { b.putFloat(268, 6.9992f); () })).toOption.get
    assert(NiftiGeometry.agreement(Vector(middle, a)).isRight)
    assert(NiftiGeometry.agreement(Vector(middle, b)).isRight)
    assert(NiftiGeometry.agreement(Vector(middle, a, b)).isLeft)
  }

  test("handedness comparison remains valid when raw transform determinant underflows") {
    val h = header(b => {
      Vector(112, 120, 128).foreach(at => b.putDouble(at, 1e-150))
      Vector(400, 440, 480).foreach(at => b.putDouble(at, 1e-150))
      b.putDouble(400, -1e-150)
      ()
    }, version = 2)
    assert(NiftiGeometry.inspect(h).left.toOption.exists(_.message.contains("handedness")))
  }
