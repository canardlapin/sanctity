package sanctity.app

import cats.effect.IO

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Tiny synthetic files for lifecycle verification, never scientific qualification. */
object SyntheticFixture:
  val firstImage = "sub-01/func/sub-01_task-rest_bold.nii"
  val secondImage = "sub-02/func/sub-02_task-rest_bold.nii"
  val sharedSidecar = "task-rest_bold.json"
  val correctedJson = """{"TaskName":"rest","RepetitionTime":1.8}""" + "\n"

  def create(root: Path): IO[Unit] = IO.blocking {
    val _ = Files.createDirectory(root)
    Vector("sub-01/func", "sub-02/func", "sourcedata/empty").foreach(path => Files.createDirectories(root.resolve(path)))
    def text(path: String, value: String): Unit =
      val _ = Files.write(root.resolve(path), value.getBytes(StandardCharsets.UTF_8))
    text("dataset_description.json", """{"Name":"Sanctity synthetic lifecycle fixture","BIDSVersion":"1.10.0","DatasetType":"raw"}""" + "\n")
    text("participants.tsv", "participant_id\tage\nsub-01\t25\nsub-02\t31\n")
    text("README", "Synthetic fixture for repository mechanics; not scientific validation.\n")
    text(sharedSidecar, """{"TaskName":"rest","RepetitionTime":1.5}""" + "\n")
    text("sub-02/func/sub-02_task-rest_bold.json", """{"RepetitionTime":2.0}""" + "\n")
    val _ = Files.write(root.resolve(firstImage), nifti(1.5f))
    val _ = Files.write(root.resolve(secondImage), nifti(2.0f))
  }

  private def nifti(tr: Float): Array[Byte] =
    val data = ByteBuffer.allocate(360).order(ByteOrder.LITTLE_ENDIAN)
    data.putInt(0, 348)
    data.putShort(40, 4.toShort)
    (1 to 3).foreach(i => data.putShort(40 + 2 * i, 1.toShort))
    data.putShort(48, 2.toShort)
    data.putShort(70, 16.toShort)
    data.putShort(72, 32.toShort)
    (1 to 3).foreach(i => data.putFloat(76 + 4 * i, 1f))
    data.putFloat(92, tr)
    data.putFloat(108, 352f)
    data.putFloat(112, 1f)
    data.put(123, 10.toByte)
    data.position(344)
    data.put(Array[Byte]('n'.toByte, '+'.toByte, '1'.toByte, 0))
    data.array()
