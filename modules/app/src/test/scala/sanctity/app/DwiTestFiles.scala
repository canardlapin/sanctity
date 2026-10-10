package sanctity.app

import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream

/** Synthetic byte-level fixtures; no acquisition/orientation qualification implied. */
object DwiTestFiles:
  val image = "sub-01/dwi/sub-01_dwi.nii"
  val bval = "dwi.bval"
  val bvec = "dwi.bvec"
  val values = "0 1000\n"
  val vectors = "0 1\n0 0\n0 0\n"

  def nifti(version: Int = 1, little: Boolean = true, volumes: Int = 2): Array[Byte] =
    val offset = if version == 1 then 352 else 544
    val b = ByteBuffer.allocate(offset + volumes * 4).order(if little then ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
    if version == 1 then
      b.putInt(0, 348)
      b.putShort(40, 4.toShort)
      (1 to 3).foreach(i => b.putShort(40 + i * 2, 1.toShort))
      b.putShort(48, volumes.toShort)
      b.putShort(70, 16.toShort)
      b.putShort(72, 32.toShort)
      b.putFloat(108, offset.toFloat)
      Vector(110, 43, 49, 0).zipWithIndex.foreach((v, i) => b.put(344 + i, v.toByte))
    else
      b.putInt(0, 540)
      Vector(110, 43, 50, 0, 13, 10, 26, 10).zipWithIndex.foreach((v, i) => b.put(4 + i, v.toByte))
      b.putShort(12, 16.toShort)
      b.putShort(14, 32.toShort)
      b.putLong(16, 4)
      (1 to 3).foreach(i => b.putLong(16 + i * 8, 1))
      b.putLong(48, volumes.toLong)
      b.putLong(168, offset.toLong)
    b.array()

  def gzip(bytes: Array[Byte]): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val gz = new GZIPOutputStream(out)
    try gz.write(bytes)
    finally gz.close()
    out.toByteArray

  def install(root: Path, bytes: Array[Byte] = nifti(), imagePath: String = image): Unit =
    Files.createDirectories(root.resolve(imagePath).getParent)
    Files.write(root.resolve(imagePath), bytes)
    Files.writeString(root.resolve(bval), values)
    val _ = Files.writeString(root.resolve(bvec), vectors)
