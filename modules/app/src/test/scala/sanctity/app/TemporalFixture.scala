package sanctity.app

import cats.effect.IO
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Hand-authored bytes and analytical values, independent of NiBabel and the producer. */
object TemporalFixture:
  val ExpectedMeans = Vector(20.0, 22.0, 24.0, 26.0)
  val ExpectedSd = math.sqrt(128.0 / 3.0)
  def image(version: Int = 1, little: Boolean = true, datatype: Int = 4): Array[Byte] =
    val offset = if version == 1 then 352 else 544
    val width = Map(2 -> 1, 4 -> 2, 8 -> 4, 16 -> 4, 64 -> 8, 256 -> 1, 512 -> 2, 768 -> 4)(datatype)
    val b = ByteBuffer.allocate(offset + 12 * width).order(if little then ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
    b.putInt(0, if version == 1 then 348 else 540)
    def real(at: Int, n: Double): Unit = { if version == 1 then b.putFloat(at, n.toFloat) else b.putDouble(at, n); () }
    def code(at: Int, n: Int): Unit = { if version == 1 then b.putShort(at, n.toShort) else b.putInt(at, n); () }
    Vector(4, 2, 2, 1, 3, 1, 1, 1).zipWithIndex.foreach { (n, i) =>
      if version == 1 then b.putShort(40 + i * 2, n.toShort) else b.putLong(16 + i * 8, n.toLong)
    }
    b.putShort(if version == 1 then 70 else 12, datatype.toShort)
    b.putShort(if version == 1 then 72 else 14, (8 * width).toShort)
    Vector(1.0, 2.0, 3.0, 4.0, 1.5, 1.0, 1.0, 1.0).zipWithIndex.foreach((v, i) => real((if version == 1 then 76 else 104) + i * (if version == 1 then 4 else 8), v))
    if version == 1 then b.putFloat(108, offset.toFloat) else b.putLong(168, offset.toLong)
    real(if version == 1 then 112 else 176, 2)
    real(if version == 1 then 116 else 184, 10)
    if version == 1 then b.put(123, 10.toByte) else b.putInt(500, 10)
    code(if version == 1 then 252 else 344, 1)
    code(if version == 1 then 254 else 348, 2)
    real(if version == 1 then 268 else 376, 7)
    val rows = Vector(2.0, 0.0, 0.0, 11.0, 0.0, 3.0, 0.0, 13.0, 0.0, 0.0, 4.0, 17.0)
    rows.zipWithIndex.foreach((v, i) => real((if version == 1 then 280 else 400) + i * (if version == 1 then 4 else 8), v))
    b.position(if version == 1 then 344 else 4)
    b.put(if version == 1 then Array[Byte](110, 43, 49, 0) else Array[Byte](110, 43, 50, 0, 13, 10, 26, 10))
    b.position(offset)
    (1 to 12).foreach { n => datatype match
      case 2 | 256 => b.put(n.toByte)
      case 4 | 512 => b.putShort(n.toShort)
      case 8 | 768 => b.putInt(n)
      case 16 => b.putFloat(n.toFloat)
      case 64 => b.putDouble(n.toDouble)
      case _ => ()
    }
    b.array()
  def gzip(bytes: Array[Byte]): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val zip = new GZIPOutputStream(out)
    zip.write(bytes)
    zip.close()
    out.toByteArray
  def install(root: Path, version: Int = 1, little: Boolean = true): IO[Unit] = IO.blocking {
    val _ = Files.write(root.resolve("source").resolve(SyntheticFixture.firstImage), image(version, little))
  }
  def python: Path = Path.of(sys.env.getOrElse("SANCTITY_MRI_PYTHON",
    Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .map(_.resolve("target/mri-python/bin/python")).find(Files.isRegularFile(_)).getOrElse(
        throw new IllegalStateException("Run scripts/setup-mri-python.sh and export SANCTITY_MRI_PYTHON")).toString))
